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
 * - Escape on its own, and Escape plus one letter or symbol (Alt+key);
 * - arrows, Home, End, Insert, Delete, page keys and function keys, with modifiers 2 to 16, the keypad
 *   (`ESC O` and a final), and `CSI n;m u` keys;
 * - the bracketed paste markers, with the pasted text between them.
 *
 * Everything else goes, including a string that an OSC, DCS, APC, PM or SOS opener starts, and any CSI
 * sequence with a final byte that is not a key. Escape followed by exactly one of those opener letters at the
 * end of a frame is Alt plus that key. With more after it, it opens a string.
 *
 * One filter serves one socket, because a key can be split over two frames. What the end of a frame cuts
 * short (an Escape, `ESC [` and parameters, a multi-byte character) is held and joined to the next frame. A
 * held lone Escape is released by [flush], which the caller runs after a short wait, so the Escape key works.
 */
@Suppress("TooManyFunctions") // One function for each kind of sequence the allow-list knows.
class TerminalInputFilter {
    private var inPaste = false
    private var inString = false
    private var stringLength = 0
    private var held = ByteArray(0)

    /** True after a paste start marker with no end marker yet. */
    @get:Synchronized
    val isPasting: Boolean get() = inPaste

    /** True when the end of the last frame was cut short and is waiting for the next one. */
    @get:Synchronized
    val hasHeld: Boolean get() = held.isNotEmpty()

    /** The keys in [input], after what an earlier frame left held, in order. */
    @Synchronized
    fun filter(input: ByteArray): ByteArray {
        val data = if (held.isEmpty()) input else held + input
        held = ByteArray(0)
        val out = ByteArrayOutputStream(data.size)
        var i = 0
        while (i < data.size) {
            val used =
                when {
                    inString -> inStringBody(data, i)
                    inPaste -> pasted(data, i, out)
                    else -> typed(data, i, out)
                }
            if (used == INCOMPLETE) {
                held = data.copyOfRange(i, data.size)
                break
            }
            i += used
        }
        return out.toByteArray()
    }

    /**
     * Releases what a frame left held when no more came: a lone Escape is the Escape key, and `ESC [` or
     * `ESC O` is Alt plus that key. Anything else held is dropped, as it will never finish.
     */
    @Synchronized
    fun flush(): ByteArray {
        val keys =
            held.takeIf {
                !inPaste && !inString && it.isNotEmpty() && it[0].toInt() == ESC &&
                    (it.size == 1 || (it.size == ALT_LENGTH && it[1].toInt().toChar() in "[O"))
            }
        held = ByteArray(0)
        return keys ?: ByteArray(0)
    }

    /** Skips the body of a string up to its terminator. A string that never ends is given up after a limit. */
    @Suppress("ReturnCount") // Early exits keep a parser flat.
    private fun inStringBody(
        data: ByteArray,
        at: Int,
    ): Int {
        var i = at
        while (i < data.size) {
            val b = data[i].toInt() and BYTE
            if (b == BEL) return end(i + 1 - at)
            if (b == ESC) {
                val next = data.getOrNull(i + 1)?.toInt() ?: return if (i > at) consume(i - at) else INCOMPLETE
                return if (next == '\\'.code) end(i + 2 - at) else end(i - at)
            }
            i++
        }
        return consume(data.size - at)
    }

    private fun consume(count: Int): Int {
        stringLength += count
        if (stringLength > MAX_STRING) inString = false
        return count
    }

    private fun end(count: Int): Int {
        inString = false
        return count
    }

    /** One step inside a paste. Text and line breaks pass. The end marker leaves paste mode. */
    private fun pasted(
        data: ByteArray,
        at: Int,
        out: ByteArrayOutputStream,
    ): Int {
        val b = data[at].toInt() and BYTE
        return when {
            b == ESC -> pastedEscape(data, at, out)
            b == TAB || b == CR || b == LF -> 1.also { out.write(b) }
            else -> text(data, at, out)
        }
    }

    @Suppress("ReturnCount") // Early exits keep a parser flat.
    private fun pastedEscape(
        data: ByteArray,
        at: Int,
        out: ByteArrayOutputStream,
    ): Int {
        if (at + 1 >= data.size) return INCOMPLETE
        val csi = csi(data, at) ?: return 1
        if (csi.incomplete) return INCOMPLETE
        if (csi.text == PASTE_END) {
            out.write(PASTE_END_BYTES)
            inPaste = false
        }
        // Anything else after Escape inside a paste is not text: it is skipped, never typed.
        return csi.length
    }

    /** One step outside a paste. */
    private fun typed(
        data: ByteArray,
        at: Int,
        out: ByteArrayOutputStream,
    ): Int {
        val b = data[at].toInt() and BYTE
        return when {
            b == ESC -> escape(data, at, out)
            b < SPACE || b == DEL -> 1.also { out.write(b) }
            else -> text(data, at, out)
        }
    }

    /** Copies one well-formed, printable UTF-8 character. Holds a character the frame cut short. */
    @Suppress("ReturnCount") // Early exits keep a parser flat.
    private fun text(
        data: ByteArray,
        at: Int,
        out: ByteArrayOutputStream,
    ): Int {
        val length = utf8Length(data, at)
        if (length == CUT_SHORT) return INCOMPLETE
        if (length == 0) return 1
        val codePoint = String(data, at, length, Charsets.UTF_8).codePointAt(0)
        if (codePoint >= SPACE && codePoint != DEL && codePoint !in C1_CONTROLS) out.write(data, at, length)
        return length
    }

    @Suppress("ReturnCount") // Early exits keep a parser flat.
    private fun escape(
        data: ByteArray,
        at: Int,
        out: ByteArrayOutputStream,
    ): Int {
        val next = data.getOrNull(at + 1)?.toInt()?.and(BYTE) ?: return INCOMPLETE
        return when {
            next == ESC || next < SPACE -> {
                1.also { out.write(ESC) }
            }

            next == '['.code -> {
                csiKey(data, at, out)
            }

            next == 'O'.code -> {
                ss3Key(data, at, out)
            }

            next in STRING_OPENERS -> {
                stringOrAlt(data, at, out)
            }

            next in SPACE..DEL -> {
                out.write(ESC)
                out.write(next)
                ALT_LENGTH
            }

            else -> {
                1
            }
        }
    }

    /** `ESC` and an opener letter alone at the end is Alt plus the letter. With content after it, it is a string. */
    private fun stringOrAlt(
        data: ByteArray,
        at: Int,
        out: ByteArrayOutputStream,
    ): Int {
        if (at + ALT_LENGTH >= data.size) {
            out.write(ESC)
            out.write(data[at + 1].toInt())
        } else {
            inString = true
            stringLength = 0
        }
        return ALT_LENGTH
    }

    @Suppress("ReturnCount") // Early exits keep a parser flat.
    private fun csiKey(
        data: ByteArray,
        at: Int,
        out: ByteArrayOutputStream,
    ): Int {
        val csi = csi(data, at) ?: return 1
        if (csi.incomplete) return INCOMPLETE
        if (csi.text == PASTE_START) {
            out.write(PASTE_START_BYTES)
            inPaste = true
        } else if (KEY_CSI.matches(csi.text)) {
            out.write(data, at, csi.length)
        }
        return csi.length
    }

    private fun ss3Key(
        data: ByteArray,
        at: Int,
        out: ByteArrayOutputStream,
    ): Int {
        val final =
            data
                .getOrNull(at + SS3_FINAL)
                ?.toInt()
                ?.and(BYTE)
                ?.toChar() ?: return INCOMPLETE
        if (final in SS3_KEYS) out.write(data, at, SS3_FINAL + 1)
        return SS3_FINAL + 1
    }

    private class Csi(
        val text: String,
        val length: Int,
        val incomplete: Boolean = false,
    )

    /**
     * Reads `ESC [`, parameter and intermediate bytes and a final byte at [at]. Null when it is not a CSI. One
     * the frame cut short is [Csi.incomplete], unless it is too long to be a key, which is dropped.
     */
    @Suppress("ReturnCount") // Early exits keep a parser flat.
    private fun csi(
        data: ByteArray,
        at: Int,
    ): Csi? {
        if (data.getOrNull(at + 1)?.toInt() != '['.code) return null
        var i = at + 2
        while (i < data.size) {
            val b = data[i].toInt() and BYTE
            if (b in FINAL_BYTES) return Csi(String(data, at + 2, i - at - 1, Charsets.ISO_8859_1), i + 1 - at)
            if ((b !in PARAMETER_BYTES && b !in INTERMEDIATE_BYTES) || i - at > MAX_CSI_LENGTH) return Csi("", i - at)
            i++
        }
        return if (data.size - at >
            MAX_CSI_LENGTH
        ) {
            Csi("", data.size - at)
        } else {
            Csi("", data.size - at, incomplete = true)
        }
    }

    /** Length of the well-formed UTF-8 character at [at], 0 when the bytes there are not one, or [CUT_SHORT]. */
    @Suppress("ReturnCount") // Early exits keep a parser flat.
    private fun utf8Length(
        data: ByteArray,
        at: Int,
    ): Int {
        val lead = data[at].toInt() and BYTE
        val length =
            when {
                lead < ASCII_LIMIT -> 1
                lead in TWO_BYTE_LEADS -> 2
                lead in THREE_BYTE_LEADS -> THREE_BYTES
                lead in FOUR_BYTE_LEADS -> FOUR_BYTES
                else -> return 0
            }
        val present = minOf(length, data.size - at)
        for (k in 1 until present) if ((data[at + k].toInt() and CONTINUATION_MASK) != CONTINUATION) return 0
        return if (present < length) CUT_SHORT else length
    }

    private companion object {
        const val INCOMPLETE = -1
        const val CUT_SHORT = -2
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
        const val MAX_STRING = 4096
        const val ALT_LENGTH = 2
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

        /** `P` DCS, `]` OSC, `X` SOS, `^` PM, `_` APC. Alone at the end they are Alt plus the letter. */
        val STRING_OPENERS = setOf('P'.code, ']'.code, 'X'.code, '^'.code, '_'.code)

        /** Arrows, Home, End, F1 to F4, and the keypad finals of application mode. */
        val SS3_KEYS = "ABCDHFPQRSjklmnopqrstuvwxyMX".toSet()

        const val PASTE_START = "200~"
        const val PASTE_END = "201~"
        val PASTE_START_BYTES = "\u001b[200~".toByteArray()
        val PASTE_END_BYTES = "\u001b[201~".toByteArray()

        /** The modifier of a key, 2 to 16: 1 plus the bits for Shift, Alt, Ctrl, Meta and the lock keys. */
        private const val MOD = "(?:[2-9]|1[0-6])"

        /**
         * Arrows, Home, End and Shift+Tab, with an optional `1;modifier`. F1 to F4 with a modifier. The `number ~`
         * keys (Home, Insert, Delete, End, page up and down, F1 to F12) with an optional modifier. And
         * `number;modifier u` keys. `R` is left out on purpose: a cursor position report ends in `R`, and F3 has
         * its own SS3 form.
         */
        val KEY_CSI =
            Regex(
                "(1;$MOD)?[ABCDHFZ]|1;$MOD[PQS]|" +
                    "(1|2|3|4|5|6|7|8|11|12|13|14|15|17|18|19|20|21|23|24)(;$MOD)?~|" +
                    "[0-9]{1,7}(;$MOD)?u",
            )
    }
}
