package ai.tatawur.rafiq.client.terminal

import java.io.ByteArrayOutputStream

/**
 * Decides what of the emulator's output may go up `WS /terminal`, and how it is framed. The emulator
 * writes both the keys you press and its own automatic answers to terminal queries into one stream.
 * In control mode tmux has already answered the program's queries, so a second answer would reach
 * the program as typed input. Only key encodings on an allow-list go up (see [KeyEncodings]).
 * Everything else is dropped.
 *
 * Enter goes up in a frame of its own, never with typed text, because the agent reads a burst that
 * holds a carriage return as a paste. A bracketed paste stays in one frame, newlines and all.
 *
 * Holds state between calls: a paste can span several calls of the emulator's output callback.
 */
class KeyUplink {
    private var inPaste = false
    private var carry = ByteArray(0)

    /** The frames to send for one chunk of emulator output, in order. Empty when nothing is allowed. */
    fun frames(chunk: ByteArray): List<ByteArray> {
        val bytes = if (carry.isEmpty()) chunk else carry + chunk
        carry = ByteArray(0)
        val frames = Frames()
        var i = 0
        while (i < bytes.size) {
            i = if (inPaste) pasteStep(bytes, i, frames) else keyStep(bytes, i, frames)
        }
        frames.flush()
        return frames.list
    }

    private fun pasteStep(
        bytes: ByteArray,
        at: Int,
        frames: Frames,
    ): Int {
        val ahead = bytes.copyOfRange(at, minOf(bytes.size, at + PASTE_END.size))
        // A pasted text never holds ESC (see [sanitizePaste]), so the only ESC here is the end marker.
        return when {
            bytes[at] != ESC -> {
                frames.text.write(bytes[at].toInt())
                at + 1
            }

            ahead.contentEquals(PASTE_END) -> {
                frames.text.write(PASTE_END)
                frames.flush()
                inPaste = false
                at + PASTE_END.size
            }

            ahead.size < PASTE_END.size && PASTE_END.copyOf(ahead.size).contentEquals(ahead) -> {
                // The end marker is split across two chunks: finish it with the next one.
                carry = ahead
                bytes.size
            }

            else -> {
                // Not the end marker: the paste was cut off. Filter as usual again.
                frames.flush()
                inPaste = false
                at
            }
        }
    }

    private fun keyStep(
        bytes: ByteArray,
        at: Int,
        frames: Frames,
    ): Int {
        val token = KeyEncodings.token(bytes, at)
        if (token.kind == KeyEncodings.Kind.PASTE_START) {
            frames.flush()
            frames.text.write(bytes, at, token.end - at)
            inPaste = true
        } else if (token.kind == KeyEncodings.Kind.KEY && bytes[token.end - 1] == CR) {
            frames.alone(bytes.copyOfRange(at, token.end))
        } else if (token.kind == KeyEncodings.Kind.KEY) {
            frames.text.write(bytes, at, token.end - at)
        }
        return token.end
    }

    private class Frames {
        val list = mutableListOf<ByteArray>()
        val text = ByteArrayOutputStream()

        fun flush() {
            if (text.size() > 0) list += text.toByteArray()
            text.reset()
        }

        fun alone(frame: ByteArray) {
            flush()
            list += frame
        }
    }

    private companion object {
        const val ESC: Byte = 0x1b
        const val CR: Byte = 0x0d
        val PASTE_END = "\u001b[201~".toByteArray(Charsets.US_ASCII)
    }
}

/**
 * The allow-list of key encodings that may go up.
 *
 * Allowed:
 * - text, including UTF-8, and the C0 control keys (Ctrl-letter, Tab, Backspace, Enter);
 * - Esc alone, and Alt with any one character (`ESC c`);
 * - cursor and editing keys: `CSI A`..`D`, `H`, `F`, `Z`, `CSI n ~` for Insert, Delete, Home, End,
 *   Page Up and Down and F5 to F12, each with an optional modifier, and `CSI n ; m u`;
 * - `SS3` keys: arrows in application mode, Home, End, F1 to F4 and the keypad;
 * - `CSI 1 ; m P`, `Q` and `S` for F1, F2 and F4 with a modifier;
 * - the start of a bracketed paste, `CSI 200 ~`.
 *
 * Dropped: anything with a private marker (`?`, `>`, `<`, `=`) or an intermediate byte, which covers
 * device attributes, mode reports and SGR mouse reports; X10 mouse reports; cursor position and
 * status reports; focus reports; and every OSC, DCS, APC, PM and SOS string. One real key is
 * dropped with them: F3 with a modifier is `CSI 1 ; m R`, the same shape as a cursor position report.
 */
internal object KeyEncodings {
    enum class Kind { KEY, PASTE_START, DROP }

    /** One key encoding or one dropped sequence, ending before [end]. */
    class Token(
        val end: Int,
        val kind: Kind,
    )

    /** The token that starts at [at]. */
    fun token(
        bytes: ByteArray,
        at: Int,
    ): Token =
        when {
            bytes[at] != ESC -> Token(at + 1, Kind.KEY)

            // Esc alone, or ESC [, ESC O and the string starters alone: Esc or Alt with that key.
            at + 2 >= bytes.size -> Token(bytes.size, Kind.KEY)

            else -> escape(bytes, at)
        }

    private fun escape(
        bytes: ByteArray,
        at: Int,
    ): Token =
        when (bytes[at + 1].toInt().toChar()) {
            '[' -> csi(bytes, at)
            'O' -> Token(if (bytes[at + 2].toInt().toChar() in SS3_KEYS) at + SS3_LENGTH else at + 2, Kind.KEY)
            ']', 'P', '_', '^', 'X' -> Token(stringEnd(bytes, at + 2), Kind.DROP)
            else -> Token(at + 2 + utf8Continuations(bytes, at + 1), Kind.KEY)
        }

    private fun csi(
        bytes: ByteArray,
        at: Int,
    ): Token {
        val paramsEnd = skip(bytes, at + 2, PARAMETER_BYTES)
        val finalAt = skip(bytes, paramsEnd, INTERMEDIATE_BYTES)
        val params = String(bytes, at + 2, paramsEnd - at - 2, Charsets.US_ASCII)
        val plain = finalAt == paramsEnd && params.all { it.isDigit() || it == ';' }
        return when {
            finalAt >= bytes.size -> {
                Token(bytes.size, Kind.DROP)
            }

            // An X10 mouse report is CSI M and three raw bytes, which would otherwise pass as text.
            plain && params.isEmpty() && bytes[finalAt] == 'M'.code.toByte() -> {
                Token(minOf(finalAt + 1 + X10_MOUSE_BYTES, bytes.size), Kind.DROP)
            }

            plain -> {
                Token(finalAt + 1, csiKind(if (params.isEmpty()) emptyList() else params.split(';'), bytes[finalAt]))
            }

            else -> {
                Token(finalAt + 1, Kind.DROP)
            }
        }
    }

    private fun csiKind(
        parts: List<String>,
        final: Byte,
    ): Kind =
        when (final.toInt().toChar()) {
            'A', 'B', 'C', 'D', 'H', 'F' -> keyIf(parts.isEmpty() || isModifiedOne(parts))
            'Z' -> keyIf(parts.isEmpty())
            'P', 'Q', 'S' -> keyIf(isModifiedOne(parts))
            '~' -> tilde(parts)
            'u' -> keyIf(isCodeWithOptionalModifier(parts))
            else -> Kind.DROP
        }

    private fun tilde(parts: List<String>): Kind {
        val code = parts.firstOrNull()?.toIntOrNull()
        return when {
            code == PASTE_START_CODE && parts.size == 1 -> Kind.PASTE_START
            code in TILDE_KEYS && isCodeWithOptionalModifier(parts) -> Kind.KEY
            else -> Kind.DROP
        }
    }

    private fun keyIf(allowed: Boolean): Kind = if (allowed) Kind.KEY else Kind.DROP

    private fun isModifiedOne(parts: List<String>): Boolean = parts.size == 2 && parts[0] == "1" && isModifier(parts[1])

    /** A key code, then an optional xterm modifier parameter: 1 plus the Shift, Alt, Ctrl and Meta bits. */
    private fun isCodeWithOptionalModifier(parts: List<String>): Boolean =
        parts.firstOrNull()?.toIntOrNull() != null &&
            (parts.size == 1 || (parts.size == 2 && isModifier(parts[1])))

    private fun isModifier(text: String): Boolean = text.toIntOrNull()?.let { it in MODIFIERS } == true

    /** The index after an OSC, DCS, APC, PM or SOS string whose body starts at [from], or the end. */
    private fun stringEnd(
        bytes: ByteArray,
        from: Int,
    ): Int {
        var i = from
        var end = bytes.size
        while (i < bytes.size && end == bytes.size) {
            if (bytes[i] == BEL) {
                end = i + 1
            } else if (bytes[i] == ESC && i + 1 < bytes.size && bytes[i + 1] == BACKSLASH) {
                end = i + 2
            }
            i++
        }
        return end
    }

    private const val ESC: Byte = 0x1b
    private const val BEL: Byte = 0x07
    private const val BACKSLASH: Byte = 0x5c
    private const val PASTE_START_CODE = 200
    private const val X10_MOUSE_BYTES = 3
    private const val SS3_LENGTH = 3
    private val PARAMETER_BYTES = 0x30..0x3f
    private val INTERMEDIATE_BYTES = 0x20..0x2f

    // Insert, Delete, Home, End, Page Up, Page Down, and F5 to F12 in the xterm encoding.
    private val TILDE_KEYS = setOf(1, 2, 3, 4, 5, 6, 15, 17, 18, 19, 20, 21, 23, 24)

    // xterm modifier parameter: 1 plus the Shift, Alt, Ctrl and Meta bits.
    private val MODIFIERS = 2..16

    // Arrows, Home and End in application mode, F1 to F4, and the keypad in application mode.
    private const val SS3_KEYS = "ABCDHFPQRSjklmnopqrstuvwxyMX"
}

private fun skip(
    bytes: ByteArray,
    from: Int,
    range: IntRange,
): Int {
    var i = from
    while (i < bytes.size && bytes[i].toInt() in range) i++
    return i
}

/** How many UTF-8 continuation bytes follow the lead byte at [at], so Alt with é stays whole. */
private fun utf8Continuations(
    bytes: ByteArray,
    at: Int,
): Int {
    var n = 0
    while (at + 1 + n < bytes.size && (bytes[at + 1 + n].toInt() and UTF8_TAG_MASK) == UTF8_CONTINUATION) n++
    return n
}

private const val UTF8_TAG_MASK = 0xC0
private const val UTF8_CONTINUATION = 0x80

/**
 * Makes clipboard text safe to paste into the pane. ESC and the other control characters except Tab,
 * line feed and carriage return are removed, so a copied text can never end a bracketed paste early
 * or type a key sequence. C1 controls go too.
 */
fun sanitizePaste(text: String): String =
    text.filter { c ->
        c == '\t' || c == '\n' || c == '\r' || (c >= ' ' && c != '\u007f' && c !in '\u0080'..'\u009f')
    }
