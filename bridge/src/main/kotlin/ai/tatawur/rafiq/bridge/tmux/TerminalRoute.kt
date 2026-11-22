package ai.tatawur.rafiq.bridge.tmux

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private val log = LoggerFactory.getLogger("ai.tatawur.rafiq.bridge.tmux.Terminal")

private val CLAIM_INTERVAL = 1.seconds
private val FIRST_RESIZE_WAIT = 2.seconds
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
 * @param deviceOf names the device a call belongs to, for the per-device limit and the audit line.
 */
fun Route.terminalRoute(
    gate: TerminalGate,
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
        try {
            TerminalConnection(gate, this, name, device, call.request.origin.remoteHost).run()
        } finally {
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

    @Suppress("TooGenericExceptionCaught") // Whatever stops the attach, the phone is told and the client is closed.
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
        val first = FirstFrames.read(socket)
        val queue = TermQueue(limits.maxPendingOutputBytes)
        val client =
            TmuxControlClient(
                gate.server,
                session.id,
                TmuxServer.VIEW_PREFIX + UUID.randomUUID().toString().take(VIEW_ID_LENGTH),
                limits,
                queue::offer,
            )
        log.info("Terminal opened: device={} session={} remote={}", device, sessionName, remote)
        try {
            withContext(Dispatchers.IO) {
                client.start(first.cols, first.rows)
                client.requestSnapshot()
            }
            serve(client, queue, first.early)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            log.warn("Terminal could not attach to session {}: {}", sessionName, failure.toString())
            runCatching { send(TerminalEnded("Could not attach to the tmux session.")) }
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

    private suspend fun tmuxIsNewEnough(): Boolean {
        val version = gate.server.version() ?: return false
        val (major, minor) = TmuxServer.MINIMUM_VERSION
        return version.first * VERSION_BASE + version.second >= major * VERSION_BASE + minor
    }

    @Volatile
    private var lastSize: Pair<Int, Int>? = null

    private suspend fun serve(
        client: TmuxControlClient,
        queue: TermQueue,
        early: List<Frame.Binary>,
    ) = coroutineScope {
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
                true.also { sendBytes(item.bytes) }
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

            TermItem.SessionsChanged -> {
                stillThere(runCatching { client.sessionExists() }.getOrDefault(false), "session")
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
        when (frame) {
            is Frame.Text -> onText(frame.readText(), client)
            is Frame.Binary -> return onKeys(frame.readBytes(), client)
            else -> Unit
        }
        return true
    }

    private suspend fun onText(
        text: String,
        client: TmuxControlClient,
    ) {
        lastActivity.set(System.nanoTime())
        val message = runCatching { terminalJson.decodeFromString(TerminalMessage.serializer(), text) }.getOrNull()
        if (message is TerminalResize && message.cols > 0 && message.rows > 0) {
            resizes.incrementAndGet()
            withContext(Dispatchers.IO) { client.resize(message.cols, message.rows) }
            lastClaim.set(System.nanoTime())
        }
    }

    private suspend fun onKeys(
        bytes: ByteArray,
        client: TmuxControlClient,
    ): Boolean {
        lastActivity.set(System.nanoTime())
        if (!budget.allows(bytes.size)) {
            socket.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "too much input"))
            return false
        }
        bytesUp.addAndGet(bytes.size.toLong())
        val keys = filter.filter(bytes)
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

    private suspend fun end(reason: String) {
        runCatching { send(TerminalEnded(reason)) }
        socket.close(CloseReason(CloseReason.Codes.NORMAL, reason.take(CLOSE_REASON_MAX)))
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
        const val VERSION_BASE = 1000
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
) {
    companion object {
        suspend fun read(socket: DefaultWebSocketSession): FirstFrames {
            var cols = DEFAULT_COLS
            var rows = DEFAULT_ROWS
            val early = mutableListOf<Frame.Binary>()
            withTimeoutOrNull(FIRST_RESIZE_WAIT) {
                for (frame in socket.incoming) {
                    if (frame is Frame.Binary) {
                        early += frame
                    } else if (frame is Frame.Text) {
                        val message =
                            runCatching {
                                terminalJson.decodeFromString(
                                    TerminalMessage.serializer(),
                                    frame.readText(),
                                )
                            }.getOrNull()
                        if (message is TerminalResize && message.cols > 0 && message.rows > 0) {
                            cols = message.cols
                            rows = message.rows
                            break
                        }
                    }
                }
            }
            return FirstFrames(cols, rows, early)
        }
    }
}
