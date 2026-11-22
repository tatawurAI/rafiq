package ai.tatawur.rafiq.bridge.tmux

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The allow-list of key encodings. The vectors are in `terminal-input-vectors.txt`, which the app mirrors.
 */
class TerminalInputFilterTest {
    private class Vector(
        val verdict: String,
        val frames: List<ByteArray>,
        val note: String,
    )

    private fun unescape(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '\\') {
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
                continue
            }
            val next = text[i + 1]
            when (next) {
                'e' -> out.write(0x1b)
                'r' -> out.write('\r'.code)
                'n' -> out.write('\n'.code)
                't' -> out.write('\t'.code)
                '\\' -> out.write('\\'.code)
                'x' -> out.write(text.substring(i + 2, i + 4).toInt(16))
                else -> error("Unknown escape \\$next")
            }
            i += if (next == 'x') 4 else 2
        }
        return out.toByteArray()
    }

    private val vectors: List<Vector> =
        checkNotNull(javaClass.getResourceAsStream("/terminal-input-vectors.txt"))
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val (verdict, bytes, note) = line.split('\t')
                Vector(verdict, bytes.split(" | ").map(::unescape), note)
            }

    /** What comes out for the frames, then for the wait after the last one. */
    private fun run(frames: List<ByteArray>): ByteArray {
        val filter = TerminalInputFilter()
        val out = java.io.ByteArrayOutputStream()
        frames.forEach { out.write(filter.filter(it)) }
        out.write(filter.flush())
        return out.toByteArray()
    }

    @Test
    fun every_vector_gets_its_verdict() {
        assertTrue(vectors.size > 50, "the vector file was not read")
        for (v in vectors) {
            val whole = v.frames.reduce { a, b -> a + b }
            val label = "${v.verdict} ${v.note}"
            when (v.verdict) {
                "accept", "split" -> assertContentEquals(whole, run(v.frames), label)
                "reject" -> assertContentEquals(ByteArray(0), run(v.frames), label)
                else -> error("Unknown verdict ${v.verdict}")
            }
        }
    }

    @Test
    fun a_reply_between_keys_is_cut_out_and_the_keys_around_it_stay() {
        assertEquals("a\u001b[Bb", String(run(listOf("a\u001b[?62c\u001b[B\u001b[5;5Rb".toByteArray()))))
    }

    @Test
    fun a_lone_escape_waits_for_the_next_frame_and_is_released_when_none_comes() {
        val filter = TerminalInputFilter()

        assertEquals("", String(filter.filter("\u001b".toByteArray())))
        assertTrue(filter.hasHeld)
        assertEquals("\u001b", String(filter.flush()))
        assertFalse(filter.hasHeld)
        // Not released twice, and not turned into text.
        assertEquals("", String(filter.flush()))
    }

    @Test
    fun a_paste_keeps_its_text_loses_escapes_inside_it_and_ends_on_a_marker_split_over_frames() {
        val paste = TerminalInputFilter()

        assertEquals(
            "\u001b[200~line one\r\nline\ttwo",
            String(paste.filter("\u001b[200~line one\r\nline\ttwo".toByteArray())),
        )
        assertTrue(paste.isPasting)
        // A reply and a colour change inside the paste are not text: they go, the rest is harmless text.
        assertEquals(" more", String(paste.filter("\u001b[12;40R\u001b[31m more".toByteArray())))
        // The end marker arrives in two frames. Losing it would leave the pane waiting for the end of the paste.
        assertEquals("", String(paste.filter("\u001b[20".toByteArray())))
        assertEquals("\u001b[201~", String(paste.filter("1~".toByteArray())))
        assertFalse(paste.isPasting)
        assertEquals("x", String(paste.filter("x".toByteArray())))
    }

    @Test
    fun a_string_opened_by_an_opener_with_content_ends_where_the_terminal_ends_it_even_over_frames() {
        val filter = TerminalInputFilter()

        // An OSC cut in two: nothing of it is typed, and typing after it passes.
        assertEquals("", String(filter.filter("\u001b]11;rgb:00".toByteArray())))
        assertEquals("ok", String(filter.filter("00/00\u0007ok".toByteArray())))
    }

    @Test
    fun invalid_utf8_between_valid_bytes_drops_only_the_bad_bytes() {
        assertContentEquals(
            byteArrayOf(0x28),
            TerminalInputFilter().filter(byteArrayOf(0xC3.toByte(), 0x28, 0xFF.toByte())),
        )
    }
}
