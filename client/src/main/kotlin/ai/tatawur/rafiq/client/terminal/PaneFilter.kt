package ai.tatawur.rafiq.client.terminal

import java.io.ByteArrayOutputStream

/**
 * Strips the string sequences the emulator must never see from pane output. Pane bytes are
 * untrusted: anything the agent prints, or any file it shows, arrives here.
 *
 * Every OSC string is removed except OSC 8, the hyperlink, which the view opens only after you
 * confirm its `https` target. That removes clipboard writes (OSC 52), the window title (OSC 0, 1 and
 * 2), colour changes and queries, notifications and inline images (OSC 1337). Every DCS, APC, PM and
 * SOS string is removed too, which covers sixel and kitty images and status queries. The view also
 * passes explicit emulator callbacks for these, so this filter does not rely on the library's
 * defaults and the library's defaults do not rely on this filter.
 *
 * C1 controls written as UTF-8 (U+0080 to U+009F) are read as their 7-bit ESC forms, so U+009D
 * is an OSC and U+0090 a DCS and they are filtered the same way. A C1 control with no string or
 * sequence behind it is dropped.
 *
 * It keeps state between frames, because a sequence can be split across two of them. [reset] it when
 * a snapshot repaints the screen.
 */
class PaneFilter {
    private enum class State { GROUND, ESCAPE, OSC, OSC_ESCAPE, DROPPED, DROPPED_ESCAPE }

    private var state = State.GROUND
    private val osc = ByteArrayOutputStream()
    private var oscOverflow = false
    private var pendingC1Lead = false

    /** Forgets any half-read sequence. */
    fun reset() {
        state = State.GROUND
        pendingC1Lead = false
        osc.reset()
        oscOverflow = false
    }

    /** The part of [bytes] the emulator may see, with the state carried over to the next call. */
    fun filter(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size)
        for (b in bytes) decode(b, out)
        return out.toByteArray()
    }

    /** Turns a C1 control in UTF-8 into its 7-bit form before the state machine sees it. */
    private fun decode(
        b: Byte,
        out: ByteArrayOutputStream,
    ) {
        val code = b.toInt() and BYTE_MASK
        when {
            pendingC1Lead && code in C1_RANGE -> {
                pendingC1Lead = false
                c1(code, out)
            }

            pendingC1Lead -> {
                pendingC1Lead = false
                step(C1_LEAD.toByte(), out)
                decode(b, out)
            }

            code == C1_LEAD -> {
                pendingC1Lead = true
            }

            else -> {
                step(b, out)
            }
        }
    }

    private fun c1(
        code: Int,
        out: ByteArrayOutputStream,
    ) {
        val final =
            when (code) {
                C1_CSI -> '['
                C1_OSC -> ']'
                C1_DCS, C1_SOS, C1_PM, C1_APC -> 'P'
                C1_ST -> '\\'
                else -> null
            } ?: return
        step(ESC, out)
        step(final.code.toByte(), out)
    }

    private fun step(
        b: Byte,
        out: ByteArrayOutputStream,
    ) {
        when (state) {
            State.GROUND -> {
                if (b == ESC) state = State.ESCAPE else out.write(b.toInt())
            }

            State.ESCAPE -> {
                escape(b, out)
            }

            State.OSC -> {
                oscByte(b, out)
            }

            State.OSC_ESCAPE -> {
                if (b == BACKSLASH) {
                    endOsc(out, byteArrayOf(ESC, BACKSLASH))
                } else {
                    // ESC inside an OSC that is not ST aborts it and starts a new sequence.
                    dropOsc()
                    state = State.ESCAPE
                    escape(b, out)
                }
            }

            State.DROPPED -> {
                when (b) {
                    ESC -> state = State.DROPPED_ESCAPE
                    BEL, CAN, SUB -> state = State.GROUND
                    else -> Unit
                }
            }

            State.DROPPED_ESCAPE -> {
                state = if (b == BACKSLASH) State.GROUND else State.DROPPED
            }
        }
    }

    private fun escape(
        b: Byte,
        out: ByteArrayOutputStream,
    ) {
        state =
            when (b.toInt().toChar()) {
                ']' -> {
                    State.OSC
                }

                'P', '_', '^', 'X' -> {
                    State.DROPPED
                }

                else -> {
                    out.write(ESC.toInt())
                    // A second ESC may start a string of its own, so it stays in this state.
                    if (b == ESC) return
                    out.write(b.toInt())
                    State.GROUND
                }
            }
    }

    private fun oscByte(
        b: Byte,
        out: ByteArrayOutputStream,
    ) {
        when (b) {
            BEL -> {
                endOsc(out, byteArrayOf(BEL))
            }

            ESC -> {
                state = State.OSC_ESCAPE
            }

            CAN, SUB -> {
                dropOsc()
                state = State.GROUND
            }

            else -> {
                if (osc.size() < MAX_LINK_OSC) osc.write(b.toInt()) else oscOverflow = true
            }
        }
    }

    private fun endOsc(
        out: ByteArrayOutputStream,
        terminator: ByteArray,
    ) {
        val body = osc.toByteArray()
        if (!oscOverflow && body.startsWith(HYPERLINK)) {
            out.write(byteArrayOf(ESC, ']'.code.toByte()))
            out.write(body)
            out.write(terminator)
        }
        dropOsc()
        state = State.GROUND
    }

    private fun dropOsc() {
        osc.reset()
        oscOverflow = false
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private companion object {
        const val ESC: Byte = 0x1b
        const val BEL: Byte = 0x07
        const val CAN: Byte = 0x18
        const val SUB: Byte = 0x1a
        const val BACKSLASH: Byte = 0x5c

        // OSC 8 is "8;params;uri". Longer ones are dropped rather than cut, so a link is never altered.
        const val MAX_LINK_OSC = 4096
        val HYPERLINK = "8;".toByteArray(Charsets.US_ASCII)

        const val BYTE_MASK = 0xFF

        // U+0080 to U+009F in UTF-8 are C2 80 to C2 9F.
        const val C1_LEAD = 0xC2
        val C1_RANGE = 0x80..0x9F
        const val C1_DCS = 0x90
        const val C1_SOS = 0x98
        const val C1_CSI = 0x9B
        const val C1_ST = 0x9C
        const val C1_OSC = 0x9D
        const val C1_PM = 0x9E
        const val C1_APC = 0x9F
    }
}
