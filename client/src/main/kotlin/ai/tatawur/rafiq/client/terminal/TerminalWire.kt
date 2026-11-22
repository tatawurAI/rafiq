package ai.tatawur.rafiq.client.terminal

import ai.tatawur.rafiq.protocol.api.TerminalEnded
import ai.tatawur.rafiq.protocol.api.TerminalMessage
import ai.tatawur.rafiq.protocol.api.TerminalResize
import ai.tatawur.rafiq.protocol.api.TerminalSize
import ai.tatawur.rafiq.protocol.api.TerminalSnapshot
import ai.tatawur.rafiq.protocol.api.TerminalUnknown
import ai.tatawur.rafiq.protocol.api.terminalJson
import kotlinx.serialization.SerializationException
import java.io.ByteArrayOutputStream

/** A size in character cells. */
data class CellSize(
    val cols: Int,
    val rows: Int,
)

/**
 * The tmux window the mirrored pane is in, for the Terminal header. The name comes from your
 * server's tmux and is shown as plain text: control characters are dropped and it is cut to
 * [MAX_NAME] characters, so a hostile window name cannot break the header.
 */
data class MirroredWindow(
    val index: Int,
    val name: String,
) {
    companion object {
        const val MAX_NAME = 40

        /** The window of [snapshot], or null when the bridge did not send one. */
        fun of(snapshot: TerminalSnapshot): MirroredWindow? =
            snapshot.window?.let { window ->
                val clean = window.name.filterNot { it.isISOControl() }.trim()
                MirroredWindow(window.index, clean.take(MAX_NAME))
            }
    }
}

/** What the emulator must do with one step of `WS /terminal`, after the frames are paired up. */
sealed interface TerminalEvent {
    /**
     * Resize the emulator to [size], then write [vt]. [vt] repaints the screen from any state,
     * including the middle of an escape sequence left by an earlier frame.
     */
    class Snapshot(
        val size: CellSize,
        val vt: ByteArray,
        val window: MirroredWindow? = null,
    ) : TerminalEvent

    /** Pane output to write as it is. */
    class Output(
        val bytes: ByteArray,
    ) : TerminalEvent

    /** The pane's real size changed, for example because the desk took it back. */
    data class Size(
        val size: CellSize,
    ) : TerminalEvent

    /** The pane is gone or the bridge ended the view. The socket closes after it. */
    data class Ended(
        val reason: String,
    ) : TerminalEvent
}

/**
 * Pairs the socket's frames into [TerminalEvent]s. A `snapshot` text frame is always followed by one
 * binary frame holding the screen, so that binary frame belongs to the snapshot, not to the stream.
 * Text frames this build does not understand are ignored.
 */
class TerminalStreamDecoder {
    private var pending: TerminalSnapshot? = null

    /** Decodes one text frame, or returns null when it only arms the next binary frame. */
    fun onText(text: String): TerminalEvent? {
        val message =
            try {
                terminalJson.decodeFromString(TerminalMessage.serializer(), text)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        return when (message) {
            is TerminalSnapshot -> {
                pending = message
                null
            }

            is TerminalSize -> {
                TerminalEvent.Size(CellSize(message.cols, message.rows))
            }

            is TerminalEnded -> {
                TerminalEvent.Ended(message.reason)
            }

            is TerminalResize, TerminalUnknown, null -> {
                null
            }
        }
    }

    /** Decodes one binary frame: the screen of a pending snapshot, or plain pane output. */
    fun onBinary(bytes: ByteArray): TerminalEvent {
        val snapshot = pending ?: return TerminalEvent.Output(bytes)
        pending = null
        return TerminalEvent.Snapshot(
            CellSize(snapshot.cols, snapshot.rows),
            snapshotToVt(snapshot, bytes),
            MirroredWindow.of(snapshot),
        )
    }
}

/** The up-going resize message for a viewport of [size]. */
fun resizeMessage(size: CellSize): String =
    terminalJson.encodeToString(TerminalMessage.serializer(), TerminalResize(size.cols, size.rows))

private const val ESC: Byte = 0x1b

// CAN aborts any escape sequence a previous frame left open, so the reset after it is parsed as one.
private const val CAN: Byte = 0x18

/**
 * Turns `capture-pane -e -p` output into bytes that repaint an emulator from any state: an abort of
 * any open sequence, a full reset, the pane's modes, then each line placed at its own row, then the
 * cursor. Lines are placed by row rather than joined with newlines, so a line that fills the width
 * never wraps into the next one. The bridge may separate lines with `\n` or `\r\n`.
 *
 * Bracketed paste and application cursor keys are restored, so a paste and the arrow keys encode the
 * way the program expects. Mouse reporting is not: the view sends keys only, and taps open the
 * keyboard.
 */
fun snapshotToVt(
    snapshot: TerminalSnapshot,
    screen: ByteArray,
): ByteArray {
    val out = ByteArrayOutputStream(screen.size)
    out.write(CAN.toInt())
    // RIS: clears the screen, scrollback and modes, and homes the cursor.
    out.write(byteArrayOf(ESC, 'c'.code.toByte()))
    if (snapshot.modes.bracketedPaste) out.write("\u001b[?2004h".toByteArray())
    if (snapshot.modes.appCursorKeys) out.write("\u001b[?1h".toByteArray())
    var row = 0
    var start = 0

    fun emitLine(end: Int) {
        if (row >= snapshot.rows) return
        var stop = end
        if (stop > start && screen[stop - 1] == '\r'.code.toByte()) stop--
        out.write("\u001b[${row + 1};1H".toByteArray())
        out.write(screen, start, stop - start)
        row++
    }
    for (i in screen.indices) {
        if (screen[i] == '\n'.code.toByte()) {
            emitLine(i)
            start = i + 1
        }
    }
    if (start < screen.size) emitLine(screen.size)
    out.write("\u001b[0m\u001b[${snapshot.cursorY + 1};${snapshot.cursorX + 1}H".toByteArray())
    return out.toByteArray()
}

/** How many whole cells of [cellWidthPx] x [cellHeightPx] fit in a viewport, at least one each way. */
fun viewportCells(
    widthPx: Int,
    heightPx: Int,
    cellWidthPx: Float,
    cellHeightPx: Float,
): CellSize =
    CellSize(
        cols = (widthPx / cellWidthPx).toInt().coerceAtLeast(1),
        rows = (heightPx / cellHeightPx).toInt().coerceAtLeast(1),
    )
