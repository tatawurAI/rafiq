package ai.tatawur.rafiq.bridge.tmux

import java.io.ByteArrayOutputStream

/**
 * Lets through only what a person typing could have sent, and drops the rest.
 *
 * The phone's emulator can also produce replies to the pane program's questions (cursor position, device
 * attributes, colours). tmux already answers those, so a reply that reached the pane would be read as typing.
 * A deny-list of known replies misses the next kind, so this is an allow-list of key encodings:
 *
 * - printable UTF-8 text, and the single control bytes a keyboard makes (Enter, Tab, Backspace, Ctrl+letter);
 * - Escape on its own, and Escape plus a letter or symbol (Alt+key);
 * - arrows, Home, End, Insert, Delete, page keys and function keys, with or without Shift, Alt and Ctrl;
 * - the bracketed paste markers, with the pasted text between them.
 *
 * Everything else goes, including sequences that open a string (OSC, DCS) and any CSI sequence with a final
 * byte that is not a key. One filter serves one socket, because a paste can span frames.
 */
class TerminalInputFilter {
    private var inPaste = false

    /** True after a paste start marker with no end marker yet. */
    val isPasting: Boolean get() = inPaste

    /** The part of [input] that is a key or paste, in order. Never returns more bytes than it was given. */
    fun filter(input: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(input.size)
        var i = 0
        while (i < input.size) {
            i += if (inPaste) pasted(input, i, out) else typed(input, i, out)
        }
        return out.toByteArray()
    }

    /** One step inside a paste. Text and line breaks pass. The end marker leaves paste mode. Returns bytes used. */
    @Suppress("ReturnCount") // Early exits keep a parser flat.
    private fun pasted(
        input: ByteArray,
        at: Int,
        out: ByteArrayOutputStream,
    ): Int {
        val b = input[at].toInt() and BYTE
        if (b == ESC) {
            val csi = csi(input, at)
            if (csi != null && csi.text == PASTE_END) {
                out.write(PASTE_END_BYTES)
                inPaste = false
            }
            // Anything else after Escape inside a paste is not text: it is skipped, never typed.
            return csi?.length ?: 1
        }
        if (b == TAB || b == CR || b == LF) {
            out.write(b)
            return 1
        }
        return text(input, at, out)
    }

    /** One step outside a paste. Returns bytes used. */
    private fun typed(
        input: ByteArray,
        at: Int,
        out: ByteArrayOutputStream,
    ): Int {
        val b = input[at].toInt() and BYTE
        return when {
            b == ESC -> {
                escape(input, at, out)
            }

            b < SPACE || b == DEL -> {
                out.write(b)
                1
            }

            else -> {
                text(input, at, out)
            }
        }
    }

    /** Copies one well-formed, printable UTF-8 character. Returns bytes used, at least one. */
    private fun text(
        input: ByteArray,
        at: Int,
        out: ByteArrayOutputStream,
    ): Int {
        val length = utf8Length(input, at)
        if (length == 0) return 1
        val codePoint = String(input, at, length, Charsets.UTF_8).codePointAt(0)
        val printable = codePoint >= SPACE && codePoint != DEL && codePoint !in C1_CONTROLS
        if (printable) out.write(input, at, length)
        return length
    }

    private fun escape(
        input: ByteArray,
        at: Int,
        out: ByteArrayOutputStream,
    ): Int {
        val next = input.getOrNull(at + 1)?.toInt()?.and(BYTE)
        return when {
            next == null || next == ESC || next < SPACE -> {
                out.write(ESC)
                1
            }

            next == '['.code -> {
                csiKey(input, at, out)
            }

            next == 'O'.code -> {
                ss3Key(input, at, out)
            }

            next in STRING_OPENERS -> {
                skipString(input, at)
            }

            next in SPACE..DEL -> {
                out.write(ESC)
                out.write(next)
                2
            }

            else -> {
                1
            }
        }
    }

    private fun csiKey(
        input: ByteArray,
        at: Int,
        out: ByteArrayOutputStream,
    ): Int {
        val csi = csi(input, at) ?: return 1
        if (csi.text == PASTE_START) {
            out.write(PASTE_START_BYTES)
            inPaste = true
        } else if (KEY_CSI.matches(csi.text)) {
            out.write(input, at, csi.length)
        }
        return csi.length
    }

    private fun ss3Key(
        input: ByteArray,
        at: Int,
        out: ByteArrayOutputStream,
    ): Int {
        val final =
            input
                .getOrNull(at + SS3_FINAL)
                ?.toInt()
                ?.and(BYTE)
                ?.toChar() ?: return SS3_FINAL
        if (final in SS3_KEYS) out.write(input, at, SS3_FINAL + 1)
        return SS3_FINAL + 1
    }

    /** Skips an OSC, DCS or similar string up to its terminator, so its body is not read as typing. */
    @Suppress("ReturnCount") // Early exits keep a parser flat.
    private fun skipString(
        input: ByteArray,
        at: Int,
    ): Int {
        var i = at + 2
        while (i < input.size) {
            val b = input[i].toInt() and BYTE
            if (b == BEL) return i + 1 - at
            if (b == ESC && input.getOrNull(i + 1)?.toInt() == '\\'.code) return i + 2 - at
            i++
        }
        return input.size - at
    }

    private class Csi(
        val text: String,
        val length: Int,
    )

    /** Reads `ESC [`, parameter and intermediate bytes and a final byte at [at]. Null when it is not a CSI. */
    @Suppress("ReturnCount") // Early exits keep a parser flat.
    private fun csi(
        input: ByteArray,
        at: Int,
    ): Csi? {
        if (input.getOrNull(at + 1)?.toInt() != '['.code) return null
        var i = at + 2
        while (i < input.size) {
            val b = input[i].toInt() and BYTE
            if (b in FINAL_BYTES) return Csi(String(input, at + 2, i - at - 1, Charsets.ISO_8859_1), i + 1 - at)
            if ((b !in PARAMETER_BYTES && b !in INTERMEDIATE_BYTES) || i - at > MAX_CSI_LENGTH) return Csi("", i - at)
            i++
        }
        // Cut short: nothing after the Escape is a key, so all of it goes.
        return Csi("", input.size - at)
    }

    /** Length of the well-formed UTF-8 character at [at], or 0 when the bytes there are not one. */
    @Suppress("ReturnCount") // Early exits keep a parser flat.
    private fun utf8Length(
        input: ByteArray,
        at: Int,
    ): Int {
        val lead = input[at].toInt() and BYTE
        val length =
            when {
                lead < ASCII_LIMIT -> 1
                lead in TWO_BYTE_LEADS -> 2
                lead in THREE_BYTE_LEADS -> THREE_BYTES
                lead in FOUR_BYTE_LEADS -> FOUR_BYTES
                else -> return 0
            }
        if (at + length > input.size) return 0
        for (k in 1 until length) if ((input[at + k].toInt() and CONTINUATION_MASK) != CONTINUATION) return 0
        return length
    }

    private companion object {
        const val BYTE = 0xFF
        const val ESC = 0x1B
        const val BEL = 0x07
        const val TAB = 0x09
        const val LF = 0x0A
        const val CR = 0x0D
        const val SPACE = 0x20
        const val DEL = 0x7F
        const val ASCII_LIMIT = 0x80
        const val CONTINUATION_MASK = 0xC0
        const val CONTINUATION = 0x80
        const val MAX_CSI_LENGTH = 24
        const val SS3_FINAL = 2
        const val THREE_BYTES = 3
        const val FOUR_BYTES = 4
        val C1_CONTROLS = 0x80..0x9F
        val TWO_BYTE_LEADS = 0xC2..0xDF
        val THREE_BYTE_LEADS = 0xE0..0xEF
        val FOUR_BYTE_LEADS = 0xF0..0xF4
        val PARAMETER_BYTES = 0x30..0x3F
        val INTERMEDIATE_BYTES = 0x20..0x2F
        val FINAL_BYTES = 0x40..0x7E

        /** `P` DCS, `]` OSC, `X` SOS, `^` PM, `_` APC. Alt plus one of these is not sent, it opens a string. */
        val STRING_OPENERS = setOf('P'.code, ']'.code, 'X'.code, '^'.code, '_'.code)
        val SS3_KEYS = "ABCDHFPQRS".toSet()

        const val PASTE_START = "200~"
        const val PASTE_END = "201~"
        val PASTE_START_BYTES = "\u001b[200~".toByteArray()
        val PASTE_END_BYTES = "\u001b[201~".toByteArray()

        /**
         * Arrows, Home, End and Shift+Tab, with optional `1;modifier`. F1 to F4 with a modifier. And `number ~`
         * keys: Home, Insert, Delete, End, page up and down, and F1 to F12, with an optional modifier. `R` is left
         * out on purpose: a cursor position report ends in `R`, and F3 has its own SS3 form.
         */
        val KEY_CSI =
            Regex("(1;[2-8])?[ABCDHFZ]|1;[2-8][PQS]|(1|2|3|4|5|6|7|8|11|12|13|14|15|17|18|19|20|21|23|24)(;[2-8])?~")
    }
}
