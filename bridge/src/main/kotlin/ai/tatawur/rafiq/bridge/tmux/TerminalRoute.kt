package ai.tatawur.rafiq.bridge.tmux

import ai.tatawur.rafiq.bridge.server.AuditLog
import ai.tatawur.rafiq.bridge.server.ConnectionRegistry
import ai.tatawur.rafiq.bridge.sessions.SessionException
import ai.tatawur.rafiq.bridge.sessions.TMUX_SESSION_PREFIX
import ai.tatawur.rafiq.protocol.api.ApiError
import ai.tatawur.rafiq.protocol.api.ApiErrorDetail
import ai.tatawur.rafiq.protocol.api.MAX_HISTORY_LINES
import ai.tatawur.rafiq.protocol.api.TerminalEnded
import ai.tatawur.rafiq.protocol.api.TerminalHistory
import ai.tatawur.rafiq.protocol.api.TerminalListWindows
import ai.tatawur.rafiq.protocol.api.TerminalMessage
import ai.tatawur.rafiq.protocol.api.TerminalNotice
import ai.tatawur.rafiq.protocol.api.TerminalResize
import ai.tatawur.rafiq.protocol.api.TerminalSelectWindow
import ai.tatawur.rafiq.protocol.api.TerminalSize
import ai.tatawur.rafiq.protocol.api.TerminalSnapshot
import ai.tatawur.rafiq.protocol.api.terminalJson
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/** How often an open view checks that its session may still be opened. */
private val ACCESS_CHECK_INTERVAL = 1.seconds

/** Checks in a row the session must fail before the view ends, so one bad scan never ends a live view. */
private const val ACCESS_MISSES_TO_END = 3
private val FIRST_RESIZE_WAIT = 2.seconds
private val TRY_AGAIN = CloseReason.Codes.TRY_AGAIN_LATER
private val END_SEND_WAIT = 1.seconds
private val RESIZE_RETRY = 100.milliseconds
private val ESCAPE_WAIT = 60.milliseconds
private const val HISTORY_RETRY_SECONDS = 1
private const val DEFAULT_COLS = 80
private const val DEFAULT_ROWS = 24

/** Switches the emulator to the alternate screen, so a snapshot of a full-screen program lands where it belongs. */
private val ENTER_ALTERNATE_SCREEN = "\u001b[?1049h\u001b[2J\u001b[H".toByteArray()

/**
 * `WS /terminal?session=<id>`: mirrors one pane of an allowed tmux session to the phone and takes its keys.
 *
 * Binary frames carry terminal bytes, text frames carry [TerminalMessage] JSON. The phone's first `resize`
 * sets the attach size, so the desk is never resized to a guess (it waits 2 s, then uses 80x24). A snapshot
 * and then the live stream follow. Closing the socket detaches, which hands the window size back to the desk,
 * or, with no other client showing the window, puts back the size it had before the phone took it.
 *
 * The phone may ask for the session's windows (`list_windows`) and show another one (`select_window`). Only the
 * phone's own view session changes window. When the shown window closes, the view moves to the session's
 * current window and the phone is told with a `notice` and a fresh snapshot.
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
        val name = gate.allowedName(call.request.queryParameters["session"])
        if (name == null) {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "no such session"))
            return@webSocket
        }
        val device = deviceOf(call)
        val slot =
            when (val result = gate.slots.acquire(device)) {
                is SlotResult.Granted -> {
                    result.slot
                }

                SlotResult.AtLimit -> {
                    null.also { close(CloseReason(TRY_AGAIN, "too many terminals open")) }
                }

                SlotResult.TooManyOpens -> {
                    null.also { close(CloseReason(TRY_AGAIN, "too many terminal opens, wait a moment")) }
                }
            }
        if (slot == null) return@webSocket
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

/**
 * `GET /terminal/history?session=<id>&window=<index>&lines=<n>`: read-only scrollback of the pane the phone
 * shows, as [TerminalHistory]. `lines` is capped at [MAX_HISTORY_LINES], and is that cap when left out. Without
 * `window`, the session's current window. Reading never changes the window, its size or what the desk sees.
 *
 * It sits behind the device token like every route, and reaches only an allowed session, by the same rule as
 * `WS /terminal`. Anything else is `404 session_not_found`, so the answer does not say which tmux sessions
 * exist. A device that reads too often, or a read while the bridge runs all it may at once, gets `429
 * too_many_requests` with `Retry-After` ([HistoryLimits]). Each read is written to the audit log.
 */
fun Route.terminalHistoryRoute(
    gate: TerminalGate,
    deviceOf: (ApplicationCall) -> String,
    audit: AuditLog,
) {
    get("/terminal/history") {
        val query = call.request.queryParameters
        val window = query["window"]?.let { it.toIntOrNull()?.takeIf { index -> index >= 0 } ?: invalidQuery() }
        val asked = query["lines"]?.let { it.toIntOrNull()?.takeIf { n -> n > 0 } ?: invalidQuery() }
        val lines = (asked ?: MAX_HISTORY_LINES).coerceAtMost(MAX_HISTORY_LINES)
        val name = gate.allowedName(query["session"]) ?: sessionNotFound()
        val device = deviceOf(call)
        val permit = gate.historyLimits.tryStart(device)
        if (permit == null) {
            call.response.header(HttpHeaders.RetryAfter, HISTORY_RETRY_SECONDS.toString())
            call.respond(
                HttpStatusCode.TooManyRequests,
                ApiError(ApiErrorDetail("too_many_requests", "Too many scrollback reads. Wait a moment.")),
            )
            return@get
        }
        permit.use {
            if (runCatching { gate.server.session(name) }.getOrNull() == null) sessionNotFound()
            val history =
                gate.server.history(name, window, lines)
                    ?: throw SessionException.NotFound("no_such_window", "That window is not in this session.")
            audit.terminalHistory(device, TMUX_SESSION_PREFIX + name, window, lines)
            val lineCount = if (history.text.isEmpty()) 0 else history.text.count { it == '\n' } + 1
            call.respond(TerminalHistory(history.text, lineCount, history.cols, history.alternateScreen))
        }
    }
}

private fun sessionNotFound(): Nothing = throw SessionException.NotFound("session_not_found", "No such tmux session.")

private fun invalidQuery(): Nothing =
    throw SessionException.Invalid("invalid_query", "window and lines are whole numbers.")

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
    private val abuseBucket = TokenBucket(limits.abuseResizes, limits.abuseResizes / ABUSE_WINDOW_SECONDS)
    private val windowBucket = TokenBucket(WINDOW_REQUEST_BURST, WINDOW_REQUESTS_PER_SECOND)
    private val keyLock = Mutex()
    private val resizeLock = Mutex()

    @Volatile
    private var pendingSize: Pair<Int, Int>? = null
    private var resizeApplier: Job? = null

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
                gate.sessionSizes,
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
        val access = launch { closeWhenNoLongerAllowed() }
        try {
            early.forEach { handle(it, client) }
            for (frame in socket.incoming) {
                if (!handle(frame, client)) break
            }
        } finally {
            downstream.cancel()
            watchdog.cancel()
            access.cancel()
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
                true.also {
                    failedScreens = 0
                    sendScreen(item)
                }
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
                client.paneExists() || follow(client)
            }

            TermItem.ScreenFailed -> {
                onScreenFailed(client)
            }

            is TermItem.Ended -> {
                false.also { end(item.reason) }
            }
        }

    /** Snapshots that failed in a row while the pane was still there. */
    private var failedScreens = 0

    /**
     * A failed snapshot is never sent as a short screen. While the pane is there, a fresh one is asked for, a
     * few times at most, so a pane that keeps breaking its reply cannot keep the bridge busy.
     */
    private suspend fun onScreenFailed(client: TmuxControlClient): Boolean =
        when {
            !client.paneExists() -> {
                follow(client)
            }

            ++failedScreens <= MAX_FAILED_SCREENS -> {
                true.also { client.requestSnapshot() }
            }

            else -> {
                false.also { end("The screen of the tmux pane could not be read.") }
            }
        }

    /**
     * The mirrored pane went away. A pane that closed in a window that is still there gives way to that window's
     * active pane. A window that closed gives way to the session's current window, and the phone is told. Only
     * a session that is gone ends the view.
     */
    private suspend fun follow(client: TmuxControlClient): Boolean {
        val followed = withContext(Dispatchers.IO) { client.followAfterLoss() }
        if (followed == TmuxControlClient.Followed.SESSION_WINDOW) {
            send(
                TerminalNotice(
                    WINDOW_CLOSED,
                    "The window you were watching closed. This is the session's current window.",
                ),
            )
        }
        if (followed == TmuxControlClient.Followed.NOTHING) return stillThere(false, "session")
        client.requestSnapshot()
        return true
    }

    private suspend fun sendScreen(item: TermItem.Screen) {
        lastSize = item.snapshot.cols to item.snapshot.rows
        // The screen's bytes must be the very next frame after its snapshot, whatever else is being sent.
        sendLock.withLock {
            sendFrame(item.snapshot)
            sendBytesFrame((if (item.alternate) ENTER_ALTERNATE_SCREEN else ByteArray(0)) + item.text)
        }
    }

    /** The desk may have taken the size back. The phone learns the new size and gets a screen laid out for it. */
    private suspend fun onLayoutChange(client: TmuxControlClient): Boolean {
        // A pane that closed in a split window changes the layout too, and then has no size to read.
        val size = runCatching { client.paneSize() }.getOrNull() ?: return follow(client)
        if (size != lastSize) {
            lastSize = size
            send(TerminalSize(size.first, size.second))
            client.requestSnapshot()
        }
        return true
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
        if (message is TerminalListWindows || message is TerminalSelectWindow) return onWindowRequest(message, client)
        if (message !is TerminalResize || message.cols <= 0 || message.rows <= 0) return true
        val size = message.cols to message.rows
        if (size == requestedSize) return true
        // Many sizes in a short time is a fold or keyboard animation: only the newest matters. Far more than
        // that is abuse.
        if (!abuseBucket.tryTake()) return refuse("too many resizes")
        requestedSize = size
        resizeLock.withLock {
            if (resizeBucket.tryTake()) {
                pendingSize = null
                applySize(size, client)
            } else {
                pendingSize = size
                if (resizeApplier?.isActive !=
                    true
                ) {
                    resizeApplier = scope?.launch(Dispatchers.IO) { applyWhenAllowed(client) }
                }
            }
        }
        return true
    }

    /**
     * `list_windows` answers with the session's windows. `select_window` shows one of them in the phone's view
     * session only, and answers with a fresh snapshot, or a `notice` when the session has no such window. Each
     * starts tmux commands, so a phone that sends them far faster than a person picks windows is closed.
     */
    private suspend fun onWindowRequest(
        message: TerminalMessage,
        client: TmuxControlClient,
    ): Boolean {
        if (!windowBucket.tryTake()) return refuse("too many window requests")
        when (message) {
            is TerminalListWindows -> {
                send(withContext(Dispatchers.IO) { client.windows() })
            }

            is TerminalSelectWindow -> {
                if (withContext(Dispatchers.IO) { client.selectWindow(message.index) }) {
                    resizes.incrementAndGet()
                    lastClaim.set(System.nanoTime())
                    client.requestSnapshot()
                } else {
                    send(TerminalNotice(NO_SUCH_WINDOW, "That window is not in this session."))
                }
            }

            else -> {
                Unit
            }
        }
        return true
    }

    private suspend fun applySize(
        size: Pair<Int, Int>,
        client: TmuxControlClient,
    ) {
        resizes.incrementAndGet()
        withContext(Dispatchers.IO) { client.resize(size.first, size.second) }
        lastClaim.set(System.nanoTime())
    }

    /** Applies the newest size that was asked for while the bucket was empty, as soon as a token returns. */
    private suspend fun applyWhenAllowed(client: TmuxControlClient) {
        while (!ended.get()) {
            delay(RESIZE_RETRY)
            val ready =
                resizeLock.withLock {
                    val size = pendingSize
                    if (size != null && resizeBucket.tryTake()) size.also { pendingSize = null } else null
                }
            if (ready != null) applySize(ready, client)
            if (pendingSize == null) return
        }
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
        // Filtering and sending are one step under the lock, as are flushing and sending in releaseHeldKey, so a
        // held Escape can never reach the pane after the key that came after it.
        keyLock.withLock {
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
        }
        return true
    }

    private suspend fun releaseHeldKey(client: TmuxControlClient) {
        delay(ESCAPE_WAIT)
        // Not cancellable from here: a key that was taken out of the filter must reach the pane.
        withContext(NonCancellable + Dispatchers.IO) {
            keyLock.withLock {
                val keys = filter.flush()
                if (keys.isNotEmpty() && !ended.get()) client.sendInput(keys)
            }
        }
    }

    /**
     * The gate is checked at connect, and again here while the view is open: a scan can drop the session, for
     * example when its agent exits and the session is not named as a shell. The view ends and detaches only after
     * [ACCESS_MISSES_TO_END] checks in a row find it dropped, because one failed tmux poll or one scan where agent
     * detection flapped must not end every open view.
     */
    private suspend fun closeWhenNoLongerAllowed() {
        var misses = 0
        while (socket.isActive) {
            delay(ACCESS_CHECK_INTERVAL)
            misses = if (gate.allowedName(TMUX_SESSION_PREFIX + sessionName) == null) misses + 1 else 0
            if (misses >= ACCESS_MISSES_TO_END) {
                end("Terminal is no longer allowed for this session.")
                return
            }
        }
    }

    private suspend fun closeWhenIdle() {
        val idle = limits.idleTimeout
        while (socket.isActive) {
            delay((idle / IDLE_CHECKS).coerceIn(MIN_IDLE_CHECK, MAX_IDLE_CHECK))
            if (opened.elapsedNow() >= limits.maxAge) {
                end("This terminal was open for the longest time allowed. Open it again.")
                return
            }
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

    /** Frames go out from the stream and from answers to the phone's requests, one at a time. */
    private val sendLock = Mutex()

    private suspend fun send(message: TerminalMessage) = sendLock.withLock { sendFrame(message) }

    private suspend fun sendBytes(bytes: ByteArray) = sendLock.withLock { sendBytesFrame(bytes) }

    private suspend fun sendFrame(message: TerminalMessage) {
        socket.send(Frame.Text(terminalJson.encodeToString(TerminalMessage.serializer(), message)))
    }

    private suspend fun sendBytesFrame(bytes: ByteArray) {
        bytesDown.addAndGet(bytes.size.toLong())
        socket.send(Frame.Binary(true, bytes))
    }

    private companion object {
        const val VIEW_ID_LENGTH = 8
        const val IDLE_CHECKS = 4
        const val ABUSE_WINDOW_SECONDS = 10.0
        const val CLOSE_REASON_MAX = 100
        const val MAX_FAILED_SCREENS = 3
        const val WINDOW_REQUEST_BURST = 20
        const val WINDOW_REQUESTS_PER_SECOND = 2.0
        const val WINDOW_CLOSED = "window_closed"
        const val NO_SUCH_WINDOW = "no_such_window"
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
