package ai.tatawur.rafiq.bridge.tmux

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * One command sent to tmux, waiting for its reply. Replies come back in the order commands were sent.
 *
 * @property lineCount for a reply whose lines a pane controls, such as a screen capture: how many lines the
 * reply has, asked when its block begins. The reader then takes exactly that many lines as text, so a line
 * the pane printed can never be read as the end of the block or as a notification. Null for a reply that
 * cannot carry pane text, or when the count is not known.
 */
internal class PendingReply(
    val future: CompletableFuture<List<String>>?,
    val onReply: ((ok: Boolean, lines: List<String>) -> Unit)?,
    val lineCount: (() -> Int?)? = null,
) {
    companion object {
        /** For a command whose reply nobody reads. Holds no state, so one instance serves them all. */
        val IGNORED = PendingReply(null, null)
    }
}

/** The window and pane a control client mirrors, which its reader needs to pick the right notifications. */
internal class ControlTarget {
    @Volatile
    var windowId: String = ""

    @Volatile
    var paneId: String = ""

    /** True once the reader has stopped, so a command sent after it fails at once instead of waiting. */
    @Volatile
    var ended: Boolean = false

    /** The `%begin` line of the last reply to a command of ours, for tests that need its time and number. */
    @Volatile
    var lastBegin: String = ""
}

/**
 * Reads what a tmux control-mode client prints: replies to commands, and notifications such as pane output.
 * Runs on its own thread until tmux closes the stream, and never waits for [sink].
 */
internal class ControlReader(
    input: InputStream,
    private val replies: ConcurrentLinkedQueue<PendingReply>,
    private val target: ControlTarget,
    private val sink: (TermItem) -> Unit,
) {
    private val input = BufferedInputStream(input)
    private var block: MutableList<String>? = null
    private var terminator = ""
    private var ours = false

    /** Lines of the open block still to be taken as text, or [UNCOUNTED]. */
    private var textLines = UNCOUNTED

    /** The open block had more lines than its count said, so its reply is a failure. */
    private var overran = false

    fun run() {
        var running = true
        while (running) {
            val line = readLine(input)
            running = line != null && handle(line)
        }
        val gone = IllegalStateException("tmux control client exited")
        target.ended = true
        replies.forEach { it.future?.completeExceptionally(gone) }
        sink(TermItem.Ended("tmux closed the connection."))
    }

    /** Handles one line. Returns false when tmux said it is exiting. */
    private fun handle(line: ByteArray): Boolean {
        val text = String(line, Charsets.ISO_8859_1)
        val open = block
        when {
            // A counted line is pane text whatever it looks like, so it is never compared with the terminator.
            open != null && textLines > 0 -> {
                open.add(text)
                textLines--
            }

            // Only the line that repeats this block's time and command number ends it.
            open != null && (text.startsWith("%end $terminator") || text.startsWith("%error $terminator")) -> {
                if (ours) finish(open, ok = text.startsWith("%end ") && !overran)
                block = null
            }

            open != null -> {
                // A counted block that goes on past its count is out of step with its count. It is read to its
                // terminator as before, and fails.
                if (textLines == 0) overran = true
                open.add(text)
            }

            text.startsWith("%begin ") -> {
                begin(text)
            }

            text.startsWith("%exit") -> {
                return false
            }

            else -> {
                notification(text, line)
            }
        }
        return true
    }

    private fun begin(text: String) {
        block = mutableListOf()
        val fields = text.split(' ')
        terminator = "${fields.getOrNull(1)} ${fields.getOrNull(2)} "
        // The third field is 1 for the reply to a command we sent, 0 for the attach itself.
        ours = fields.getOrNull(FLAGS_FIELD) == "1"
        overran = false
        textLines = if (ours) replies.peek()?.lineCount?.invoke() ?: UNCOUNTED else UNCOUNTED
        if (ours) target.lastBegin = text
    }

    private fun notification(
        text: String,
        line: ByteArray,
    ) {
        when {
            text.startsWith("%output ") -> {
                route(line, skipFields = OUTPUT_FIELDS)
            }

            text.startsWith("%extended-output ") -> {
                route(line, skipFields = EXTENDED_OUTPUT_FIELDS)
            }

            text.startsWith("%pause ") -> {
                sink(TermItem.Paused)
            }

            text.startsWith("%layout-change ") -> {
                if (fieldOf(text) == target.windowId) sink(TermItem.LayoutChanged)
            }

            // A window killed while this view shows it is already unlinked from the view's session when tmux
            // reports it, so it comes as the unlinked kind.
            text.startsWith("%window-close ") || text.startsWith("%unlinked-window-close ") -> {
                if (fieldOf(text) == target.windowId) sink(TermItem.MaybeGone)
            }

            text.startsWith("%sessions-changed") -> {
                sink(TermItem.MaybeGone)
            }
        }
    }

    private fun finish(
        lines: List<String>,
        ok: Boolean,
    ) {
        val pending = replies.poll() ?: return
        pending.onReply?.invoke(ok, lines)
        val future = pending.future ?: return
        if (ok) {
            future.complete(
                lines,
            )
        } else {
            future.completeExceptionally(IllegalStateException(lines.joinToString("\n")))
        }
    }

    /** The first argument of a notification, such as the window id in `%layout-change @1 ...`. */
    private fun fieldOf(line: String): String? = line.split(' ').getOrNull(1)?.trim()

    /** `%output %pane data` and `%extended-output %pane age : data`. The data is the rest of the line. */
    private fun route(
        line: ByteArray,
        skipFields: Int,
    ) {
        var index = 0
        var seen = 0
        while (seen < skipFields && index < line.size) {
            if (line[index] == SPACE) seen++
            index++
        }
        val pane = String(line, 0, index, Charsets.ISO_8859_1).split(' ')[1]
        if (pane == target.paneId) sink(TermItem.Output(decodeOctal(line, index)))
    }

    companion object {
        private const val UNCOUNTED = -1
        private const val FLAGS_FIELD = 3
        private const val OUTPUT_FIELDS = 2
        private const val EXTENDED_OUTPUT_FIELDS = 4
        private const val OCTAL_DIGITS = 3
        private const val OCTAL_BITS = 3
        private const val SPACE = ' '.code.toByte()
        private const val BACKSLASH = '\\'.code.toByte()
        private const val NEWLINE = '\n'.code.toByte()
        private const val ZERO = '0'.code.toByte()
        private const val SEVEN = '7'.code.toByte()

        /** tmux writes bytes below 0x20 and the backslash as `\ooo`. Everything else, UTF-8 included, is raw. */
        fun decodeOctal(
            line: ByteArray,
            from: Int,
        ): ByteArray {
            val out = ByteArrayOutputStream(line.size - from)
            var i = from
            while (i < line.size) {
                val isEscape =
                    line[i] == BACKSLASH && i + OCTAL_DIGITS < line.size &&
                        (1..OCTAL_DIGITS).all { isOctal(line[i + it]) }
                if (isEscape) {
                    out.write(octalValue(line, i + 1))
                    i += OCTAL_DIGITS + 1
                } else {
                    out.write(line[i].toInt())
                    i++
                }
            }
            return out.toByteArray()
        }

        private fun octalValue(
            line: ByteArray,
            at: Int,
        ): Int = (0 until OCTAL_DIGITS).fold(0) { value, k -> (value shl OCTAL_BITS) or (line[at + k] - ZERO) }

        private fun isOctal(b: Byte) = b in ZERO..SEVEN

        private fun readLine(input: InputStream): ByteArray? {
            val buffer = ByteArrayOutputStream()
            while (true) {
                val next = input.read()
                if (next < 0) return if (buffer.size() == 0) null else buffer.toByteArray()
                if (next.toByte() == NEWLINE) return buffer.toByteArray()
                buffer.write(next)
            }
        }
    }
}
