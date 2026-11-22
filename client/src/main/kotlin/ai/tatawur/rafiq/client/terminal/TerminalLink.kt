package ai.tatawur.rafiq.client.terminal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

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
) {
    @Volatile private var uplink: TerminalUplink? = null

    // A fresh allow-list per socket, so a paste cut off by a drop does not carry into the next one.
    @Volatile private var keys = KeyUplink()
    private val viewport = MutableStateFlow<CellSize?>(null)
    private val currentStatus = MutableStateFlow<TerminalStatus>(TerminalStatus.Detached)

    val status: StateFlow<TerminalStatus> = currentStatus.asStateFlow()

    /** The viewport in cells that fits the phone's layout. The pane keeps its own size until tmux agrees. */
    fun setViewport(size: CellSize) {
        viewport.value = size
    }

    /** Sends what the emulator wrote for your keys and paste, after the allow-list. */
    fun sendKeys(bytes: ByteArray) {
        val target = uplink ?: return
        val filter = keys
        synchronized(filter) { filter.frames(bytes) }.forEach(target::sendBinary)
    }

    /**
     * Holds the socket while collected and emits what the emulator must apply. Cancelling the
     * collector closes the socket.
     */
    @OptIn(FlowPreview::class) // debounce
    fun attach(): Flow<TerminalEvent> =
        channelFlow {
            currentStatus.value = TerminalStatus.Connecting
            val decoder = TerminalStreamDecoder()
            val pane = PaneFilter()
            var ended: String? = null
            var resizer: Job? = null
            keys = KeyUplink()
            try {
                port.open(sessionId).collect { frame ->
                    when (frame) {
                        is TerminalFrame.Opened -> {
                            uplink = frame.uplink
                            currentStatus.value = TerminalStatus.Live
                            // The attach does not wait for the debounce. Later changes do.
                            viewport.value?.let { frame.uplink.sendText(resizeMessage(it)) }
                            resizer?.cancel()
                            resizer =
                                launch {
                                    viewport
                                        .drop(1)
                                        .filterNotNull()
                                        .debounce(resizeDebounce)
                                        .collect { size -> uplink?.sendText(resizeMessage(size)) }
                                }
                        }

                        is TerminalFrame.Text -> {
                            val event = decoder.onText(frame.text)
                            if (event is TerminalEvent.Ended) ended = event.reason
                            if (event != null) send(event)
                        }

                        is TerminalFrame.Binary -> {
                            when (val event = decoder.onBinary(frame.bytes)) {
                                is TerminalEvent.Snapshot -> {
                                    pane.reset()
                                    send(TerminalEvent.Snapshot(event.size, pane.filter(event.vt)))
                                }

                                is TerminalEvent.Output -> {
                                    val safe = pane.filter(event.bytes)
                                    if (safe.isNotEmpty()) send(TerminalEvent.Output(safe))
                                }

                                else -> {
                                    send(event)
                                }
                            }
                        }
                    }
                }
                currentStatus.value = TerminalStatus.Ended(ended)
            } catch (cancelled: CancellationException) {
                currentStatus.value = TerminalStatus.Detached
                throw cancelled
            } catch (failure: IOException) {
                currentStatus.value = TerminalStatus.Failed(failure.message)
            } finally {
                uplink = null
                // The socket is gone: the resize sender goes with it, so the flow can complete.
                resizer?.cancel()
            }
        }

    companion object {
        val DEFAULT_RESIZE_DEBOUNCE: Duration = 150.milliseconds
    }
}
