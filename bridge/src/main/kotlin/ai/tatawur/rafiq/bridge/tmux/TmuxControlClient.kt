package ai.tatawur.rafiq.bridge.tmux

import ai.tatawur.rafiq.protocol.api.TerminalModes
import ai.tatawur.rafiq.protocol.api.TerminalSnapshot
import ai.tatawur.rafiq.protocol.api.TerminalWindow
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/** What a [TmuxControlClient] reports, in the order tmux produced it. */
sealed interface TermItem {
    class Output(
        val bytes: ByteArray,
    ) : TermItem

    /**
     * The answer to a snapshot request, in stream order, so it joins exactly with the [Output] around it.
     *
     * @property alternate whether the pane is on the alternate screen, as a full-screen program uses.
     * @property text the screen as text with colours, one line per row.
     */
    class Screen(
        val snapshot: TerminalSnapshot,
        val alternate: Boolean,
        val text: ByteArray,
    ) : TermItem

    /**
     * A snapshot could not be taken: the pane is gone, or the reply did not have one line for each row, so it
     * cannot be trusted as a screen.
     */
    data object ScreenFailed : TermItem

    /** The target window's layout changed, so the pane size may have changed. */
    data object LayoutChanged : TermItem

    /** tmux paused the pane because this client fell behind. Output while paused is lost, so a snapshot follows. */
    data object Paused : TermItem

    /**
     * A session or window changed or went. The pane may be gone, or may only have left another session of the
     * group: closing the view session of another phone sends this window notice to every client.
     */
    data object MaybeGone : TermItem

    /** The consumer is too far behind. Output is dropped until the next [Screen]. */
    data object Overflowed : TermItem

    class Ended(
        val reason: String,
    ) : TermItem
}

/**
 * A tmux control-mode client run as a plain child process, with no pseudo-terminal.
 *
 * It attaches through its own grouped session (`new-session -t <session> -s <view>`), so choosing a window
 * here never moves the desk's current window. The view session is `destroy-unattached`, so it disappears
 * when the client's stdin closes, including when the bridge dies and the pipe closes with it.
 *
 * Size. A control client counts for `window-size latest` only after it has sent a size, and wins the size
 * back from the desk only with `switch-client`. So a size is `refresh-client -C` then `switch-client`. This
 * class never uses `ignore-size`, `-g` or `resize-window`.
 *
 * The reader thread never waits for the consumer. [sink] is called on it and must return at once.
 *
 * @param sessionId the tmux id (`$3`) of the session to mirror, so a rename cannot change what is attached.
 * @param viewName the name of the view session, which starts with [TmuxServer.VIEW_PREFIX].
 */
@Suppress("TooManyFunctions") // One function for each thing the terminal view asks tmux to do.
class TmuxControlClient(
    private val server: TmuxServer,
    private val sessionId: String,
    private val viewName: String,
    private val limits: TerminalLimits,
    private val sink: (TermItem) -> Unit,
) : AutoCloseable {
    private lateinit var process: Process
    private val replies = ConcurrentLinkedQueue<PendingReply>()
    private val writeLock = Any()
    private val target = ControlTarget()

    /** The pane being mirrored. Set by [start]. */
    val paneId: String get() = target.paneId

    /**
     * Attaches, claims the window size for the phone and sets [paneId] to the pane being mirrored: the
     * active pane of the session's current window.
     *
     * @throws IllegalStateException when tmux refuses a command. The client is closed first.
     */
    @Suppress("TooGenericExceptionCaught") // Whatever went wrong, the half-attached client must be closed first.
    fun start(
        cols: Int,
        rows: Int,
    ) {
        process = server.start("-C", "new-session", "-t", sessionId, "-s", viewName)
        val reader = ControlReader(process.inputStream, replies, target, sink)
        Thread(reader::run, "tmux-control-reader-$viewName").apply { isDaemon = true }.start()
        try {
            // Set after attach: set before it, the session is destroyed at once.
            command("set-option destroy-unattached on")
            command("refresh-client -f pause-after=${limits.pauseAfterSeconds}")
            command("refresh-client -C ${limits.clampCols(cols)}x${limits.clampRows(rows)}")
            val here = command("display-message -p '#{window_id} #{pane_id}'").first().trim().split(' ')
            target.windowId = here[0]
            target.paneId = here[1]
            claimSize()
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }

    /** The phone's viewport changed. Both commands are needed: the first sets the size, the second wins `latest`. */
    fun resize(
        cols: Int,
        rows: Int,
    ) {
        send("refresh-client -C ${limits.clampCols(cols)}x${limits.clampRows(rows)}", PendingReply.IGNORED)
        claimSize()
    }

    /** Takes the window size back, for after the desk typed. tmux gives `latest` to whoever acted last. */
    fun claimSize() {
        send("switch-client -t '$viewName'", PendingReply.IGNORED)
    }

    /** Raw key bytes from the phone, passed as hex so tmux never reads a key name out of them. */
    fun sendInput(bytes: ByteArray) {
        for (chunk in bytes.asList().chunked(INPUT_CHUNK)) {
            val hex = chunk.joinToString(" ") { "%02x".format(it.toInt() and BYTE) }
            send("send-keys -t $paneId -H $hex", PendingReply.IGNORED)
        }
    }

    /**
     * Asks for the screen. It arrives later as [TermItem.Screen], in order with the output. The size, cursor and
     * modes are read in the same command line as the capture, so no output can fall between them.
     */
    fun requestSnapshot() {
        // Both callbacks run on the reader thread, the second after the first.
        var rows: Int? = null
        var meta = ""
        val read = "display-message -p -t $paneId '$META_FORMAT'"
        val capture = "capture-pane -e -p -t $paneId"
        synchronized(writeLock) {
            replies.add(
                PendingReply(null, { ok, lines ->
                    meta = lines.firstOrNull().orEmpty()
                    rows =
                        if (ok) {
                            meta
                                .trim()
                                .split(' ')
                                .getOrNull(1)
                                ?.toIntOrNull()
                        } else {
                            null
                        }
                }),
            )
            // The capture is the one reply a pane writes, so it is read by count: one line for each row.
            replies.add(
                PendingReply(null, { ok, lines ->
                    val whole = ok && rows != null && lines.size == rows
                    sink(if (whole) screen(meta, lines) else TermItem.ScreenFailed)
                }, lineCount = { rows }),
            )
            write("$read ; $capture")
        }
    }

    /** The `%begin` line of the last reply to one of this client's commands. */
    internal val lastReplyBegin: String get() = target.lastBegin

    /** The size of the mirrored pane in columns and rows. Waits for tmux. */
    fun paneSize(): Pair<Int, Int> {
        val n = command("display-message -p -t $paneId '#{pane_width} #{pane_height}'").first().trim().split(' ')
        return n[0].toInt() to n[1].toInt()
    }

    /** True when the pane being mirrored is still in the session. Waits for tmux. */
    fun paneExists(): Boolean =
        runCatching { command("list-panes -s -t '$sessionId' -F '#{pane_id}'").any { it.trim() == paneId } }
            .getOrDefault(false)

    /** Output produced while a pane was paused is lost, so the caller sends a snapshot after this. */
    fun resume() {
        send("refresh-client -A '$paneId:continue'", PendingReply.IGNORED)
    }

    /** Sends [text] and waits for its reply. @throws IllegalStateException when tmux answers with an error. */
    private fun command(text: String): List<String> {
        val future = CompletableFuture<List<String>>()
        send(text, PendingReply(future, null))
        return try {
            future.get(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (failure: ExecutionException) {
            throw IllegalStateException(failure.cause?.message ?: "tmux refused the command.", failure)
        }
    }

    private fun send(
        text: String,
        reply: PendingReply,
    ) {
        synchronized(writeLock) {
            replies.add(reply)
            write(text)
        }
    }

    @Suppress("TooGenericExceptionCaught") // A dead pipe is the same as a closed client: the reader reports it.
    private fun write(text: String) {
        try {
            process.outputStream.write((text + "\n").toByteArray())
            process.outputStream.flush()
        } catch (_: Exception) {
            // tmux exited. The reader sees the end of its output and reports it.
        }
    }

    /**
     * Ends the client. Closing stdin is enough for tmux: it detaches on end of input, which is also what
     * happens when the bridge dies. The close runs on its own thread and takes no lock, because a write stuck
     * on a full pipe holds the stream, and then the child is killed after [CLOSE_WAIT_SECONDS].
     */
    override fun close() {
        if (!::process.isInitialized) return
        Thread { runCatching { process.outputStream.close() } }.apply { isDaemon = true }.start()
        if (!process.waitFor(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    private companion object {
        const val INPUT_CHUNK = 256
        const val COMMAND_TIMEOUT_SECONDS = 10L
        const val CLOSE_WAIT_SECONDS = 2L
        const val BYTE = 0xFF
        const val CURSOR_X = 2
        const val CURSOR_Y = 3
        const val ALTERNATE_ON = 4
        const val BRACKETED_PASTE = 5
        const val APP_CURSOR_KEYS = 6
        const val MOUSE_ANY = 7
        const val MOUSE_BUTTON = 8
        const val MOUSE_STANDARD = 9
        const val WINDOW_INDEX = 10
        const val META_FIELDS = 12

        private fun window(fields: List<String>): TerminalWindow? =
            fields.getOrNull(WINDOW_INDEX)?.toIntOrNull()?.let {
                TerminalWindow(it, fields.getOrNull(WINDOW_INDEX + 1).orEmpty())
            }

        const val META_FORMAT =
            "#{pane_width} #{pane_height} #{cursor_x} #{cursor_y} #{alternate_on} #{bracket_paste_flag} " +
                "#{keypad_cursor_flag} #{mouse_any_flag} #{mouse_button_flag} #{mouse_standard_flag} " +
                "#{window_index} #{window_name}"

        /** Reads the numbers of [META_FORMAT]: size, cursor, alternate screen, then paste, keypad and mouse flags. */
        fun screen(
            meta: String,
            lines: List<String>,
        ): TermItem.Screen {
            // The window name comes last and may hold spaces.
            val fields = meta.trim().split(' ', limit = META_FIELDS)
            val n = fields.map { it.toIntOrNull() ?: 0 }

            fun at(index: Int) = n.getOrElse(index) { 0 }

            fun flag(index: Int) = at(index) != 0
            val mouse = flag(MOUSE_ANY) || flag(MOUSE_BUTTON) || flag(MOUSE_STANDARD)
            val modes = TerminalModes(flag(BRACKETED_PASTE), flag(APP_CURSOR_KEYS), mouse)
            return TermItem.Screen(
                snapshot = TerminalSnapshot(at(0), at(1), at(CURSOR_X), at(CURSOR_Y), modes, window(fields)),
                alternate = flag(ALTERNATE_ON),
                text = lines.joinToString("\r\n").toByteArray(Charsets.ISO_8859_1),
            )
        }
    }
}
