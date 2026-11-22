package ai.tatawur.rafiq.client.terminal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** One frame from `WS /terminal`, before the snapshot pairing. */
sealed interface TerminalFrame {
    /** The handshake succeeded. [uplink] sends up this socket until it closes. */
    class Opened(
        val uplink: TerminalUplink,
    ) : TerminalFrame

    class Text(
        val text: String,
    ) : TerminalFrame

    class Binary(
        val bytes: ByteArray,
    ) : TerminalFrame
}

/** Sends up an open `WS /terminal` socket. */
interface TerminalUplink {
    /** One binary frame of key bytes. */
    fun sendBinary(bytes: ByteArray)

    /** One text frame, a JSON message. */
    fun sendText(text: String)
}

/** Opens `WS /terminal` sockets. :android implements it with OkHttp. */
fun interface TerminalPort {
    /**
     * A cold flow that opens `WS /terminal?session=<sessionId>` when collected and closes it when
     * collection stops. Closing the socket detaches the bridge's view client, so the desk gets its
     * size back. It completes when the bridge closes the socket.
     *
     * @throws IOException when the handshake is refused or the connection fails or drops.
     */
    fun open(sessionId: String): Flow<TerminalFrame>
}

/**
 * The phone fell too far behind the pane: more frames or bytes waited than the port holds. The
 * port closes the socket and [TerminalLink] opens a new one, which starts with a fresh snapshot.
 */
class TerminalOverflowException : IOException("The phone fell behind the pane.")

/** Where the Terminal view's socket stands. */
sealed interface TerminalStatus {
    /** Not attached: the view is not showing, or it has not started yet. */
    data object Detached : TerminalStatus

    data object Connecting : TerminalStatus

    data object Live : TerminalStatus

    /** The bridge ended the view or closed the socket. [reason] is the bridge's, or null if it gave none. */
    data class Ended(
        val reason: String?,
    ) : TerminalStatus

    /** The socket could not open or dropped. */
    data class Failed(
        val message: String?,
    ) : TerminalStatus
}

/**
 * One Terminal view's connection to a tmux-backed session. [attach] holds the socket while it is
 * collected. The Android screen collects it only while Terminal shows and the app is started, so
 * every way of leaving Terminal (the toggle, back, the cover screen, the app going to the
 * background) cancels the collection and closes the socket. A socket that drops ends the
 * collection too, and nothing goes up after that.
 *
 * Keys go through [KeyUplink], so only key encodings go up and Enter has a frame of its own. The
 * viewport the phone would like goes up once right after the socket opens and then debounced, so a
 * fold or a keyboard animation does not resize the pane many times.
 */
class TerminalLink(
    private val port: TerminalPort,
    private val sessionId: String,
    private val resizeDebounce: Duration = DEFAULT_RESIZE_DEBOUNCE,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    @Volatile private var uplink: TerminalUplink? = null

    @Volatile private var outgoing: Channel<ByteArray>? = null

    // Told each time a chunk ends on a string opener, so it is released if nothing follows.
    @Volatile private var heldOpener: Channel<Unit>? = null

    @Volatile private var keys = KeyUplink()
    private val viewport = MutableStateFlow<CellSize?>(null)
    private val currentStatus = MutableStateFlow<TerminalStatus>(TerminalStatus.Detached)

    val status: StateFlow<TerminalStatus> = currentStatus.asStateFlow()

    /** The viewport in cells that fits the phone's layout. The pane keeps its own size until tmux agrees. */
    fun setViewport(size: CellSize) {
        viewport.value = size
    }

    /**
     * Sends what the emulator wrote for your keys and paste, after the allow-list. The frames queue
     * up and go out paced (see [attach]), so a long paste never trips the bridge's input limits.
     */
    fun sendKeys(bytes: ByteArray) {
        val queue = outgoing ?: return
        val filter = keys
        val (frames, holding) = synchronized(filter) { filter.frames(bytes) to filter.holdingOpener }
        frames.forEach { queue.trySend(it) }
        if (holding) heldOpener?.trySend(Unit)
    }

    /** Asks the bridge for the session's windows. A [TerminalEvent.Windows] answers. Nothing while detached. */
    fun listWindows() {
        uplink?.sendText(listWindowsMessage())
    }

    /**
     * Asks the bridge to show window [index] on the phone. Only the phone's own view changes, never the desk's.
     * A fresh snapshot answers, or a [TerminalEvent.Notice] when there is no such window.
     */
    fun selectWindow(index: Int) {
        uplink?.sendText(selectWindowMessage(index))
    }

    /**
     * Holds the socket while collected and emits what the emulator must apply. Cancelling the
     * collector closes the socket.
     *
     * It opens nothing until the screen has measured a viewport, so the pane is never resized to a
     * guess. When the phone falls too far behind the pane ([TerminalOverflowException]) it closes
     * the socket and opens a new one: the bridge starts every socket with a fresh snapshot, so
     * nothing on screen is lost.
     */
    fun attach(): Flow<TerminalEvent> =
        channelFlow {
            currentStatus.value = TerminalStatus.Connecting
            try {
                viewport.filterNotNull().first()
                currentStatus.value = holdUntilEnded(this)
            } catch (cancelled: CancellationException) {
                currentStatus.value = TerminalStatus.Detached
                throw cancelled
            } catch (failure: IOException) {
                currentStatus.value = TerminalStatus.Failed(failure.message)
            }
        }

    /**
     * Holds sockets until one ends for good, and returns how it ended. After an overflow it waits,
     * from [FIRST_REOPEN_DELAY] doubling up to [MAX_REOPEN_DELAY], then opens a new socket. Three
     * overflows within [OVERFLOW_WINDOW] stop it with [FELL_BEHIND], so a phone that cannot keep
     * up stops reconnecting until you ask.
     */
    private suspend fun holdUntilEnded(events: ProducerScope<TerminalEvent>): TerminalStatus {
        val overflows = ArrayDeque<TimeMark>()
        var wait = FIRST_REOPEN_DELAY
        while (true) {
            try {
                return TerminalStatus.Ended(holdSocket(events))
            } catch (_: TerminalOverflowException) {
                overflows.addLast(timeSource.markNow())
                while (overflows.first().elapsedNow() > OVERFLOW_WINDOW) overflows.removeFirst()
                if (overflows.size >= MAX_OVERFLOWS) return TerminalStatus.Failed(FELL_BEHIND)
                currentStatus.value = TerminalStatus.Connecting
                delay(wait)
                wait = minOf(wait * 2, MAX_REOPEN_DELAY)
            }
        }
    }

    /** One socket, from open to close. Returns the bridge's reason if it ended the view. */
    @OptIn(FlowPreview::class) // debounce
    private suspend fun holdSocket(events: ProducerScope<TerminalEvent>): String? =
        coroutineScope {
            val decoder = TerminalStreamDecoder()
            val pane = PaneFilter()
            val queue = Channel<ByteArray>(Channel.UNLIMITED)
            val held = Channel<Unit>(Channel.CONFLATED)
            var ended: String? = null
            val helpers = mutableListOf<Job>()
            // A fresh allow-list per socket, so a paste cut off by a drop does not carry into the next one.
            keys = KeyUplink()
            try {
                port.open(sessionId).collect { frame ->
                    when (frame) {
                        is TerminalFrame.Opened -> {
                            uplink = frame.uplink
                            outgoing = queue
                            currentStatus.value = TerminalStatus.Live
                            // The attach does not wait for the debounce. Later changes do.
                            var sent = viewport.value
                            sent?.let { frame.uplink.sendText(resizeMessage(it)) }
                            heldOpener = held
                            helpers += launch { sendPaced(queue, frame.uplink) }
                            helpers += launch { releaseHeldOpeners(held, queue) }
                            // Compares with what was sent instead of skipping the first value, so a
                            // change between the open and this collector starting still goes up.
                            helpers +=
                                launch {
                                    viewport
                                        .filterNotNull()
                                        .debounce(resizeDebounce)
                                        .collect { size ->
                                            if (size != sent) {
                                                sent = size
                                                uplink?.sendText(resizeMessage(size))
                                            }
                                        }
                                }
                        }

                        is TerminalFrame.Text -> {
                            val event = decoder.onText(frame.text)
                            if (event is TerminalEvent.Ended) ended = event.reason
                            if (event != null) events.send(event)
                        }

                        is TerminalFrame.Binary -> {
                            paneEvent(decoder.onBinary(frame.bytes), pane)?.let { events.send(it) }
                        }
                    }
                }
                ended
            } finally {
                uplink = null
                outgoing = null
                heldOpener = null
                queue.close()
                // The socket is gone: its helpers go with it, so the flow can complete.
                helpers.forEach { it.cancel() }
            }
        }

    private fun paneEvent(
        event: TerminalEvent,
        pane: PaneFilter,
    ): TerminalEvent? =
        when (event) {
            is TerminalEvent.Snapshot -> {
                pane.reset()
                TerminalEvent.Snapshot(event.size, pane.filter(event.vt), event.window, event.mouse)
            }

            is TerminalEvent.Output -> {
                pane.filter(event.bytes).takeIf { it.isNotEmpty() }?.let { TerminalEvent.Output(it) }
            }

            else -> {
                event
            }
        }

    /**
     * Releases a held string opener as Alt with that key when no chunk followed it for
     * [KeyUplink.OPENER_HOLD]. A newer hold restarts the wait. If a chunk came, it already took
     * the opener, and there is nothing to release.
     */
    private suspend fun releaseHeldOpeners(
        held: Channel<Unit>,
        queue: Channel<ByteArray>,
    ) {
        held.receiveAsFlow().collectLatest {
            delay(KeyUplink.OPENER_HOLD)
            val filter = keys
            synchronized(filter) { filter.release() }.forEach { queue.trySend(it) }
        }
    }

    /** Sends queued key frames, pausing after each so the rate stays under [MAX_UP_BYTES_PER_SECOND]. */
    private suspend fun sendPaced(
        queue: Channel<ByteArray>,
        target: TerminalUplink,
    ) {
        for (frame in queue) {
            target.sendBinary(frame)
            // A single key costs well under a millisecond, so typing is never held back.
            delay(frame.size * MILLIS_PER_SECOND / MAX_UP_BYTES_PER_SECOND)
        }
    }

    companion object {
        val DEFAULT_RESIZE_DEBOUNCE: Duration = 150.milliseconds

        /** The [TerminalStatus.Failed] message after [MAX_OVERFLOWS] overflows in [OVERFLOW_WINDOW]. */
        const val FELL_BEHIND = "The screen could not keep up. Tap to retry."

        private val FIRST_REOPEN_DELAY = 500.milliseconds
        private val MAX_REOPEN_DELAY = 8.seconds
        private val OVERFLOW_WINDOW = 30.seconds
        private const val MAX_OVERFLOWS = 3

        /** About half the bridge's 64 KiB/s input limit. */
        const val MAX_UP_BYTES_PER_SECOND = 32 * 1024L
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
