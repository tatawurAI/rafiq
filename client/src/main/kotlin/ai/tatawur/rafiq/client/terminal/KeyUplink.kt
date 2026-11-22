package ai.tatawur.rafiq.client.terminal

import java.io.ByteArrayOutputStream

/**
 * Decides what of the emulator's output may go up `WS /terminal`, and how it is framed. The emulator
 * writes both the keys you press and its own automatic answers to terminal queries into one stream.
 * In control mode tmux has already answered the program's queries, so a second answer would reach
 * the program as typed input. Only key encodings on an allow-list go up (see [KeyEncodings]).
 * Everything else is dropped.
 *
 * Framing:
 * - Enter goes up in a frame of its own, never with typed text, because the agent reads a burst
 *   that holds a carriage return as a paste.
 * - A bracketed paste starts a new frame and keeps its newlines. A long one is split into frames
 *   of at most [MAX_FRAME_BYTES], on character boundaries. The bridge carries the paste state
 *   across frames, and closes a socket that sends a frame over 16 KiB.
 *
 * Holds state between calls. A sequence or a UTF-8 character cut off at the end of one chunk is
 * kept and finished with the next, as the bridge does. ESC and exactly one more byte at the end of
 * a chunk are complete: Alt with that key. ESC alone at the end is the Esc key.
 */
class KeyUplink {
    private var inPaste = false
    private var skippingString = false
    private var carry = ByteArray(0)

    /** The frames to send for one chunk of emulator output, in order. Empty when nothing is allowed. */
    fun frames(chunk: ByteArray): List<ByteArray> {
        val bytes = if (carry.isEmpty()) chunk else carry + chunk
        carry = ByteArray(0)
        val frames = Frames()
        var i = if (skippingString) skipString(bytes) else 0
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
                textStep(bytes, at, frames)
            }

            ahead.contentEquals(PASTE_END) -> {
                frames.write(bytes, at, at + PASTE_END.size)
                frames.flush()
                inPaste = false
                at + PASTE_END.size
            }

            ahead.size < PASTE_END.size && PASTE_END.copyOf(ahead.size).contentEquals(ahead) -> {
                // The end marker is split across two chunks: finish it with the next one.
                carryFrom(bytes, at)
            }

            else -> {
                // Not the end marker: the paste was cut off. Filter as usual again.
                frames.flush()
                inPaste = false
                at
            }
        }
    }

    private fun textStep(
        bytes: ByteArray,
        at: Int,
        frames: Frames,
    ): Int {
        val token = KeyEncodings.text(bytes, at)
        return when (token.kind) {
            KeyEncodings.Kind.INCOMPLETE -> carryFrom(bytes, at)
            KeyEncodings.Kind.KEY -> token.end.also { frames.write(bytes, at, it) }
            else -> token.end
        }
    }

    private fun keyStep(
        bytes: ByteArray,
        at: Int,
        frames: Frames,
    ): Int {
        val token = KeyEncodings.token(bytes, at)
        return when (token.kind) {
            KeyEncodings.Kind.PASTE_START -> {
                frames.flush()
                frames.write(bytes, at, token.end)
                inPaste = true
                token.end
            }

            KeyEncodings.Kind.KEY -> {
                if (bytes[token.end - 1] == CR) {
                    frames.alone(bytes.copyOfRange(at, token.end))
                } else {
                    frames.write(bytes, at, token.end)
                }
                token.end
            }

            KeyEncodings.Kind.INCOMPLETE -> {
                carryFrom(bytes, at)
            }

            KeyEncodings.Kind.DROP -> {
                token.end
            }
        }
    }

    /**
     * Keeps the tail from [at] for the next chunk and returns the end. A tail longer than
     * [MAX_CARRY] can only be a string that has not ended, so it is skipped up to its terminator.
     */
    private fun carryFrom(
        bytes: ByteArray,
        at: Int,
    ): Int {
        if (bytes.size - at <= MAX_CARRY) carry = bytes.copyOfRange(at, bytes.size) else skippingString = true
        return bytes.size
    }

    /** The index after the terminator of the string being skipped, or the end while it goes on. */
    private fun skipString(bytes: ByteArray): Int {
        val end = KeyEncodings.stringEnd(bytes, 0)
        if (end != null) skippingString = false
        return end ?: bytes.size
    }

    private class Frames {
        val list = mutableListOf<ByteArray>()
        private val text = ByteArrayOutputStream()

        /** Adds one whole token, starting a new frame first if it would not fit in this one. */
        fun write(
            bytes: ByteArray,
            from: Int,
            to: Int,
        ) {
            if (text.size() + (to - from) > MAX_FRAME_BYTES) flush()
            text.write(bytes, from, to - from)
        }

        fun flush() {
            if (text.size() > 0) list += text.toByteArray()
            text.reset()
        }

        fun alone(frame: ByteArray) {
            flush()
            list += frame
        }
    }

    companion object {
        /** The largest frame that goes up: half the bridge's 16 KiB limit. */
        const val MAX_FRAME_BYTES = 8 * 1024

        private const val MAX_CARRY = 4 * 1024
        private const val ESC: Byte = 0x1b
        private const val CR: Byte = 0x0d
        private val PASTE_END = "\u001b[201~".toByteArray(Charsets.US_ASCII)
    }
}

/**
 * The allow-list of key encodings that may go up. The bridge applies the same list, and both sides
 * pin it with the same test vectors.
 *
 * Allowed:
 * - text as valid UTF-8, and the C0 control keys (Ctrl-letter, Tab, Backspace, Enter);
 * - Esc alone, and Alt with any one character (`ESC c`);
 * - cursor and editing keys: `CSI A`..`D`, `H`, `F`, `Z`, `CSI n ~` for Insert, Delete, Home, End,
 *   Page Up and Down and F5 to F12, each with an optional modifier 2 to 16, and `CSI n ; m u`;
 * - `SS3` keys: arrows in application mode, Home, End, F1 to F4 and the keypad
 *   (`jklmnopqrstuvwxyMX`). `SS3` with any other final is dropped;
 * - `CSI 1 ; m P`, `Q` and `S` for F1, F2 and F4 with a modifier;
 * - the start of a bracketed paste, `CSI 200 ~`.
 *
 * Dropped: anything with a private marker (`?`, `>`, `<`, `=`) or an intermediate byte, which covers
 * device attributes, mode reports and SGR mouse reports; X10 mouse reports; cursor position and
 * status reports; focus reports; every OSC, DCS, APC, PM and SOS string; C1 controls written as
 * UTF-8; and bytes that are not valid UTF-8. One real key is dropped with them: F3 with a modifier
 * is `CSI 1 ; m R`, the same shape as a cursor position report.
 */
internal object KeyEncodings {
    enum class Kind { KEY, PASTE_START, DROP, INCOMPLETE }

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
            bytes[at] != ESC -> text(bytes, at)

            // Esc alone, or ESC and one more byte: Esc, or Alt with that key, complete.
            at + 2 >= bytes.size -> Token(bytes.size, Kind.KEY)

            else -> escape(bytes, at)
        }

    /** One character of text or one C0 control at [at]. */
    fun text(
        bytes: ByteArray,
        at: Int,
    ): Token {
        val lead = bytes[at].toInt() and BYTE_MASK
        val end = at + utf8Length(lead)
        val available = minOf(end, bytes.size)
        val continuationsOk = (at + 1 until available).all { isContinuation(bytes[it]) }
        return when {
            end == at || !continuationsOk -> Token(at + 1, Kind.DROP)
            end > bytes.size -> Token(bytes.size, Kind.INCOMPLETE)
            lead == C1_LEAD && (bytes[at + 1].toInt() and BYTE_MASK) < C1_END -> Token(end, Kind.DROP)
            else -> Token(end, Kind.KEY)
        }
    }

    private fun escape(
        bytes: ByteArray,
        at: Int,
    ): Token =
        when (bytes[at + 1].toInt().toChar()) {
            '[' -> {
                csi(bytes, at)
            }

            'O' -> {
                Token(at + SS3_LENGTH, if (bytes[at + 2].toInt().toChar() in SS3_KEYS) Kind.KEY else Kind.DROP)
            }

            ']', 'P', '_', '^', 'X' -> {
                stringEnd(bytes, at + 2)?.let { Token(it, Kind.DROP) }
                    ?: Token(bytes.size, Kind.INCOMPLETE)
            }

            else -> {
                Token(at + 2 + utf8Continuations(bytes, at + 1), Kind.KEY)
            }
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
                Token(bytes.size, Kind.INCOMPLETE)
            }

            // An X10 mouse report is CSI M and three raw bytes, which would otherwise pass as text.
            plain && params.isEmpty() && bytes[finalAt] == 'M'.code.toByte() -> {
                if (finalAt + 1 + X10_MOUSE_BYTES > bytes.size) {
                    // Its three bytes are still to come: hold it, so they are not sent as text.
                    Token(bytes.size, Kind.INCOMPLETE)
                } else {
                    Token(finalAt + 1 + X10_MOUSE_BYTES, Kind.DROP)
                }
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

    private fun isModifiedOne(parts: List<String>): Boolean = parts.size == 2 && parts[0] == "1" && isModifier(parts[1])

    /** A key code, then an optional xterm modifier parameter: 1 plus the Shift, Alt, Ctrl and Meta bits. */
    private fun isCodeWithOptionalModifier(parts: List<String>): Boolean =
        parts.firstOrNull()?.toIntOrNull() != null &&
            (parts.size == 1 || (parts.size == 2 && isModifier(parts[1])))

    private fun isModifier(text: String): Boolean = text.toIntOrNull()?.let { it in MODIFIERS } == true

    /** The index after the BEL or ST that ends a string body starting at [from], or null if it has not ended. */
    fun stringEnd(
        bytes: ByteArray,
        from: Int,
    ): Int? {
        var i = from
        var end: Int? = null
        while (i < bytes.size && end == null) {
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
    private const val BYTE_MASK = 0xFF

    // U+0080 to U+009F in UTF-8: C2 80 to C2 9F.
    private const val C1_LEAD = 0xC2
    private const val C1_END = 0xA0
    private val PARAMETER_BYTES = 0x30..0x3f
    private val INTERMEDIATE_BYTES = 0x20..0x2f

    // Insert, Delete, Home, End, Page Up, Page Down, and F5 to F12 in the xterm encoding.
    private val TILDE_KEYS = setOf(1, 2, 3, 4, 5, 6, 15, 17, 18, 19, 20, 21, 23, 24)

    // xterm modifier parameter: 1 plus the Shift, Alt, Ctrl and Meta bits.
    private val MODIFIERS = 2..16

    // Arrows, Home and End in application mode, F1 to F4, and the keypad in application mode.
    private const val SS3_KEYS = "ABCDHFPQRSjklmnopqrstuvwxyMX"
}

private fun keyIf(allowed: Boolean): KeyEncodings.Kind = if (allowed) KeyEncodings.Kind.KEY else KeyEncodings.Kind.DROP

private fun skip(
    bytes: ByteArray,
    from: Int,
    range: IntRange,
): Int {
    var i = from
    while (i < bytes.size && bytes[i].toInt() in range) i++
    return i
}

/** How many bytes the UTF-8 character with lead byte [lead] takes, or 0 for a byte that cannot lead. */
@Suppress("MagicNumber") // The UTF-8 lead byte ranges, as in RFC 3629.
private fun utf8Length(lead: Int): Int =
    when (lead) {
        in 0x00..0x7F -> 1
        in 0xC2..0xDF -> 2
        in 0xE0..0xEF -> 3
        in 0xF0..0xF4 -> 4
        else -> 0
    }

private fun isContinuation(byte: Byte): Boolean = (byte.toInt() and UTF8_TAG_MASK) == UTF8_CONTINUATION

/** How many UTF-8 continuation bytes follow the lead byte at [at], so Alt with é stays whole. */
private fun utf8Continuations(
    bytes: ByteArray,
    at: Int,
): Int {
    var n = 0
    while (at + 1 + n < bytes.size && isContinuation(bytes[at + 1 + n])) n++
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

/** The largest paste the view sends, in UTF-8 bytes. Longer ones are refused whole, with a notice. */
const val MAX_PASTE_BYTES = 256 * 1024

/**
 * The text to paste for [clipboard]: sanitised, or null when it is longer than [MAX_PASTE_BYTES].
 * A long paste is refused rather than cut, so half a command never reaches the pane.
 */
fun preparePaste(clipboard: String): String? {
    val safe = sanitizePaste(clipboard)
    return safe.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_PASTE_BYTES }
}
