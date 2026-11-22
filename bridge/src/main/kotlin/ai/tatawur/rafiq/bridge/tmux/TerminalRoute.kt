package ai.tatawur.rafiq.bridge.tmux

import ai.tatawur.rafiq.bridge.server.ConnectionRegistry
import ai.tatawur.rafiq.bridge.sessions.TMUX_SESSION_PREFIX
import ai.tatawur.rafiq.protocol.api.TerminalEnded
import ai.tatawur.rafiq.protocol.api.TerminalMessage
import ai.tatawur.rafiq.protocol.api.TerminalResize
import ai.tatawur.rafiq.protocol.api.TerminalSize
import ai.tatawur.rafiq.protocol.api.TerminalSnapshot
import ai.tatawur.rafiq.protocol.api.terminalJson
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private val log = LoggerFactory.getLogger("ai.tatawur.rafiq.bridge.tmux.Terminal")

private val CLAIM_INTERVAL = 1.seconds
private val FIRST_RESIZE_WAIT = 2.seconds
private val END_SEND_WAIT = 1.seconds
private val ESCAPE_WAIT = 60.milliseconds
private const val DEFAULT_COLS = 80
private const val DEFAULT_ROWS = 24

/** Switches the emulator to the alternate screen, so a snapshot of a full-screen program lands where it belongs. */
private val ENTER_ALTERNATE_SCREEN = "\u001b[?1049h\u001b[2J\u001b[H".toByteArray()

/**
 * `WS /terminal?session=<id>`: mirrors one pane of an allowed tmux session to the phone and takes its keys.
 *
 * Binary frames carry terminal bytes, text frames carry [TerminalMessage] JSON. The phone's first `resize`
 * sets the attach size, so the desk is never resized to a guess (it waits 2 s, then uses 80x24). A snapshot
 * and then the live stream follow. Closing the socket detaches, which hands the window size back to the desk.
 *
 * Nothing here runs unless the gate allows the session, and the order is: gate, device limit, then tmux.
 *
 * @param connections where the socket registers, so revoking its device closes it.
 * @param deviceOf names the device a call belongs to, for the per-device limit and the audit line.
 */
fun Route.terminalRoute(
    gate: TerminalGate,
    connections: ConnectionRegistry,
    deviceOf: (ApplicationCall) -> String,
) {
    webSocket("/terminal") {
        val name =
            call.request.queryParameters["session"]
                ?.takeIf { it.startsWith(TMUX_SESSION_PREFIX) }
                ?.removePrefix(TMUX_SESSION_PREFIX)
                ?.takeIf(gate.settings::allows)
        if (name == null) {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "no such session"))
            return@webSocket
        }
        val device = deviceOf(call)
        val slot = gate.slots.acquire(device)
        if (slot == null) {
            close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "too many terminals open"))
            return@webSocket
        }
        val connection = TerminalConnection(gate, this, name, device, call.request.origin.remoteHost)
        // Revoking the device ends the view at once and detaches it, like any other close.
        val registration = connections.register(device, "terminal") { reason -> connection.end(reason) }
        try {
            connection.run()
        } finally {
            registration.close()
            slot.close()
        }
    }
}

/** One open terminal socket, from attach to detach. */
@Suppress("TooManyFunctions") // One function for each thing a terminal socket does.
private class TerminalConnection(
    private val gate: TerminalGate,
    private val socket: DefaultWebSocketSession,
    private val sessionName: String,
    private val device: String,
    private val remote: String,
) {
    private val limits = gate.settings.limits
    private val filter = TerminalInputFilter()
    private val budget = InputBudget(limits.maxFrameBytes, limits.maxInputBytesPerSecond)
    private val opened = TimeSource.Monotonic.markNow()
    private val lastActivity = AtomicLong(System.nanoTime())
    private val lastClaim = AtomicLong(0)
    private val bytesDown = AtomicLong()
    private val bytesUp = AtomicLong()
    private val resizes = AtomicLong()
    private val ended = AtomicBoolean(false)
    private val resizeBucket = TokenBucket(limits.resizeBurst, limits.resizesPerSecond)

    @Volatile
    private var requestedSize: Pair<Int, Int>? = null

    @Volatile
    private var scope: CoroutineScope? = null
    private var escapeFlush: Job? = null

    @Suppress("TooGenericExceptionCaught", "ReturnCount") // Whatever stops the attach, the phone is told.
    suspend fun run() {
        if (!tmuxIsNewEnough()) {
            socket.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "tmux 3.2 or newer is needed"))
            return
        }
        val session = runCatching { gate.server.session(sessionName) }.getOrNull()
        if (session == null) {
            socket.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "no such session"))
            return
        }
        // A phone that left before its first size is not attached at a guess.
        val first = FirstFrames.read(socket)
        if (first == null || !socket.isActive) return
        requestedSize = first.requested
        val queue = TermQueue(limits.maxPendingOutputBytes)
        val client =
            TmuxControlClient(
                gate.server,
                session.id,
                gate.viewPrefix + UUID.randomUUID().toString().take(VIEW_ID_LENGTH),
                limits,
                queue::offer,
            )
        log.info("Terminal opened: device={} session={} remote={}", device, sessionName, remote)
        try {
            if (attach(client, first)) serve(client, queue, first.early)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            // The phone going away mid-stream ends up here too. It is not an error worth more than a line.
            log.info("Terminal for session {} stopped: {}", sessionName, failure.toString())
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                // A paste cut off by the socket closing would leave the pane waiting for its end marker.
                if (filter.isPasting) client.sendInput("\u001b[201~".toByteArray())
                client.close()
            }
            queue.close()
            log.info(
                "Terminal closed: device={} session={} remote={} durationMs={} bytesDown={} bytesUp={} resizes={}",
                device,
                sessionName,
                remote,
                opened.elapsedNow().inWholeMilliseconds,
                bytesDown.get(),
                bytesUp.get(),
                resizes.get(),
            )
        }
    }

    private suspend fun tmuxIsNewEnough(): Boolean = gate.server.version()?.let(TmuxServer::isSupported) == true

    /** Attaches at the size the phone asked for. Tells the phone and returns false when tmux refuses. */
    @Suppress("TooGenericExceptionCaught") // Whatever tmux does wrong, the phone gets one plain sentence.
    private suspend fun attach(
        client: TmuxControlClient,
        first: FirstFrames,
    ): Boolean =
        try {
            withContext(Dispatchers.IO) {
                client.start(first.cols, first.rows)
                client.requestSnapshot()
            }
            true
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            log.warn("Terminal could not attach to session {}: {}", sessionName, failure.toString())
            runCatching { send(TerminalEnded("Could not attach to the tmux session.")) }
            false
        }

    @Volatile
    private var lastSize: Pair<Int, Int>? = null

    private suspend fun serve(
        client: TmuxControlClient,
        queue: TermQueue,
        early: List<Frame.Binary>,
    ) = coroutineScope {
        scope = this
        val downstream =
            launch(Dispatchers.IO) {
                var open = true
                while (open) {
                    val item = queue.next()
                    open = item != null && deliver(item, client)
                }
            }
        val watchdog = launch { closeWhenIdle() }
        try {
            early.forEach { handle(it, client) }
            for (frame in socket.incoming) {
                if (!handle(frame, client)) break
            }
        } finally {
            downstream.cancel()
            watchdog.cancel()
        }
    }

    /** Passes one thing tmux reported on to the phone. Returns false when the view is over. */
    private suspend fun deliver(
        item: TermItem,
        client: TmuxControlClient,
    ): Boolean =
        when (item) {
            is TermItem.Output -> {
                // Pane output that reaches the phone counts as activity, so watching an agent work never idles out.
                true.also {
                    sendBytes(item.bytes)
                    lastActivity.set(System.nanoTime())
                }
            }

            is TermItem.Screen -> {
                true.also { sendScreen(item) }
            }

            TermItem.LayoutChanged -> {
                onLayoutChange(client)
            }

            TermItem.Paused, TermItem.Overflowed -> {
                true.also {
                    client.resume()
                    client.requestSnapshot()
                }
            }

            TermItem.MaybeGone -> {
                stillThere(client.paneExists(), "pane")
            }

            TermItem.ScreenFailed -> {
                stillThere(false, "pane")
            }

            is TermItem.Ended -> {
                false.also { end(item.reason) }
            }
        }

    private suspend fun sendScreen(item: TermItem.Screen) {
        lastSize = item.snapshot.cols to item.snapshot.rows
        send(item.snapshot)
        sendBytes((if (item.alternate) ENTER_ALTERNATE_SCREEN else ByteArray(0)) + item.text)
    }

    /** The desk may have taken the size back. The phone learns the new size and gets a screen laid out for it. */
    private suspend fun onLayoutChange(client: TmuxControlClient): Boolean {
        val size = runCatching { client.paneSize() }.getOrNull()
        if (size != null && size != lastSize) {
            lastSize = size
            send(TerminalSize(size.first, size.second))
            client.requestSnapshot()
        }
        return stillThere(size != null, "pane")
    }

    /** Ends the view with a reason when [exists] is false. Returns [exists]. */
    private suspend fun stillThere(
        exists: Boolean,
        what: String,
    ): Boolean {
        if (!exists) end("The tmux $what is gone.")
        return exists
    }

    /** Handles one frame from the phone. Returns false when the socket was closed because of it. */
    private suspend fun handle(
        frame: Frame,
        client: TmuxControlClient,
    ): Boolean {
        // Once the view is ending, nothing more from the phone reaches the pane, however long the close takes.
        if (ended.get()) return false
        return when (frame) {
            is Frame.Text -> onText(frame.readText(), client)
            is Frame.Binary -> onKeys(frame.readBytes(), client)
            else -> true
        }
    }

    /**
     * A text frame counts toward the input budget. A size change also costs a token. A repeat of the last size
     * is free.
     */
    @Suppress("ReturnCount") // Each refusal leaves at once.
    private suspend fun onText(
        text: String,
        client: TmuxControlClient,
    ): Boolean {
        lastActivity.set(System.nanoTime())
        if (!budget.allows(text.length)) return refuse("too much input")
        val message = runCatching { terminalJson.decodeFromString(TerminalMessage.serializer(), text) }.getOrNull()
        if (message !is TerminalResize || message.cols <= 0 || message.rows <= 0) return true
        val size = message.cols to message.rows
        if (size == requestedSize) return true
        if (!resizeBucket.tryTake()) return refuse("too many resizes")
        requestedSize = size
        resizes.incrementAndGet()
        withContext(Dispatchers.IO) { client.resize(message.cols, message.rows) }
        lastClaim.set(System.nanoTime())
        return true
    }

    private suspend fun refuse(reason: String): Boolean {
        if (ended.compareAndSet(false, true)) {
            withTimeoutOrNull(END_SEND_WAIT) { socket.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, reason)) }
        }
        return false
    }

    private suspend fun onKeys(
        bytes: ByteArray,
        client: TmuxControlClient,
    ): Boolean {
        lastActivity.set(System.nanoTime())
        if (!budget.allows(bytes.size)) return refuse("too much input")
        bytesUp.addAndGet(bytes.size.toLong())
        escapeFlush?.cancel()
        val keys = filter.filter(bytes)
        // A frame that ends on a lone Escape is the Escape key unless the rest of a sequence follows at once.
        if (filter.hasHeld) escapeFlush = scope?.launch(Dispatchers.IO) { releaseHeldKey(client) }
        if (keys.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                client.sendInput(keys)
                // The desk may have typed since, and tmux gives the size to whoever acted last.
                val now = System.nanoTime()
                if (now - lastClaim.get() >= CLAIM_INTERVAL.inWholeNanoseconds) {
                    lastClaim.set(now)
                    client.claimSize()
                }
            }
        }
        return true
    }

    private suspend fun releaseHeldKey(client: TmuxControlClient) {
        delay(ESCAPE_WAIT)
        val keys = filter.flush()
        if (keys.isNotEmpty() && !ended.get()) client.sendInput(keys)
    }

    private suspend fun closeWhenIdle() {
        val idle = limits.idleTimeout
        while (socket.isActive) {
            delay((idle / IDLE_CHECKS).coerceIn(MIN_IDLE_CHECK, MAX_IDLE_CHECK))
            if (System.nanoTime() - lastActivity.get() >= idle.inWholeNanoseconds) {
                end("Closed after being idle.")
                return
            }
        }
    }

    /**
     * Tells the phone why, then closes the socket, which ends the view and detaches it. It runs once, and
     * from the moment it starts nothing more from the phone is read. A phone that has stopped reading cannot
     * hold it up: the message and the close each get [END_SEND_WAIT], and then the socket is cancelled.
     */
    suspend fun end(reason: String) {
        if (!ended.compareAndSet(false, true)) return
        withTimeoutOrNull(END_SEND_WAIT) { runCatching { send(TerminalEnded(reason)) } }
        val closed =
            withTimeoutOrNull(END_SEND_WAIT) {
                socket.close(CloseReason(CloseReason.Codes.NORMAL, reason.take(CLOSE_REASON_MAX)))
            }
        if (closed == null) socket.cancel()
    }

    private suspend fun send(message: TerminalMessage) {
        socket.send(Frame.Text(terminalJson.encodeToString(TerminalMessage.serializer(), message)))
    }

    private suspend fun sendBytes(bytes: ByteArray) {
        bytesDown.addAndGet(bytes.size.toLong())
        socket.send(Frame.Binary(true, bytes))
    }

    private companion object {
        const val VIEW_ID_LENGTH = 8
        const val IDLE_CHECKS = 4
        const val CLOSE_REASON_MAX = 100
        val MIN_IDLE_CHECK = 50.milliseconds
        val MAX_IDLE_CHECK = 5.seconds
    }
}

/** The size the phone asked for first, and any key frames it sent before the bridge was ready for them. */
private class FirstFrames(
    val cols: Int,
    val rows: Int,
    val early: List<Frame.Binary>,
    /** What the phone asked for, before the clamp, or null when it asked for nothing in time. */
    val requested: Pair<Int, Int>?,
) {
    companion object {
        /** Null when the socket closed before a size came. A phone that is merely slow gets 80x24 after 2 s. */
        suspend fun read(socket: DefaultWebSocketSession): FirstFrames? {
            val early = mutableListOf<Frame.Binary>()
            var asked: Pair<Int, Int>? = null
            val outcome =
                withTimeoutOrNull(FIRST_RESIZE_WAIT) {
                    for (frame in socket.incoming) {
                        if (frame is Frame.Binary) {
                            early += frame
                        } else if (frame is Frame.Text) {
                            val message = decode(frame.readText())
                            if (message is TerminalResize && message.cols > 0 && message.rows > 0) {
                                asked = message.cols to message.rows
                                return@withTimeoutOrNull true
                            }
                        }
                    }
                    false
                }
            if (outcome == false) return null
            return FirstFrames(asked?.first ?: DEFAULT_COLS, asked?.second ?: DEFAULT_ROWS, early, asked)
        }

        private fun decode(text: String): TerminalMessage? =
            runCatching { terminalJson.decodeFromString(TerminalMessage.serializer(), text) }.getOrNull()
    }
}
