package ai.tatawur.rafiq.client.terminal

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Only key encodings go up, and Enter always travels alone (G13). The allow-list is pinned by the
 * bridge's own vector file, so the app and the bridge cannot drift apart. The cases after it are
 * about the app's framing, which the bridge does not see.
 */
class KeyUplinkTest {
    private fun upBytes(vararg chunks: ByteArray): List<ByteArray> {
        val uplink = KeyUplink()
        return chunks.flatMap { uplink.frames(it) }
    }

    private fun joined(frames: List<ByteArray>): ByteArray = frames.fold(ByteArray(0)) { all, frame -> all + frame }

    private fun up(vararg chunks: String): List<String> =
        upBytes(*chunks.map { it.toByteArray() }.toTypedArray()).map { String(it) }

    @Test
    fun the_app_agrees_with_every_line_of_the_bridge_vector_file() {
        val vectors = sharedVectors()
        assertTrue(vectors.size > 50, "expected the shared vector file at ${VECTOR_FILE.absolutePath}")
        val wrong =
            vectors.mapNotNull { (verdict, encoded, note) ->
                val frames = encoded.split(" | ").map(::decodeVector)
                val up = upBytes(*frames.toTypedArray())
                val agrees =
                    when (verdict) {
                        // One frame, byte for byte what went in.
                        "accept" -> {
                            up.size == 1 && up.single().contentEquals(frames.single())
                        }

                        "reject" -> {
                            up.isEmpty()
                        }

                        // Pieces of one key in separate frames come out as that key, joined.
                        "split" -> {
                            joined(up).contentEquals(joined(frames))
                        }

                        else -> {
                            false
                        }
                    }
                if (agrees) null else "$verdict $encoded ($note)"
            }
        if (wrong.isNotEmpty()) fail("The app disagrees with the bridge on:\n" + wrong.joinToString("\n"))
    }

    @Test
    fun the_vector_escapes_decode_as_the_file_documents() {
        val expected = byteArrayOf(0x1b, 0x5b, 0x0d, 0x0a, 0x09, 0x5c, 0xff.toByte())
        assertContentEquals(expected, decodeVector("\\e[\\r\\n\\t\\\\\\xff"))
        assertContentEquals("é👍".toByteArray(), decodeVector("é👍"))
    }

    @Test
    fun a_reply_mixed_into_typed_keys_is_cut_out_and_the_keys_stay() {
        assertEquals(listOf("a\u001b[Bb"), up("a\u001b[?62c\u001b[B\u001b[5;5Rb"))
    }

    @Test
    fun enter_goes_up_in_a_frame_of_its_own() {
        assertEquals(listOf("fix the test", "\r", "next"), up("fix the test\rnext"))
        assertEquals(listOf("x", "\u001b\r"), up("x\u001b\r"))
    }

    @Test
    fun a_utf8_character_split_across_chunks_goes_up_whole_in_one_frame() {
        val frames = upBytes(byteArrayOf(0xC3.toByte()), byteArrayOf(0xA9.toByte()))

        assertEquals(1, frames.size)
        assertEquals("é", String(frames.single()))
    }

    @Test
    fun a_sequence_split_across_chunks_goes_up_whole_and_a_split_reply_is_still_dropped() {
        assertEquals(listOf("\u001b[1;5D"), up("\u001b[1;5", "D"))
        assertEquals(listOf("x"), up("\u001b]11;rgb", ":0000/0000/0000\u0007x"))
        // An old style mouse report whose three bytes come in the next chunk: none of it is text.
        assertEquals(listOf("y"), up("\u001b[M", " !!y"))
    }

    @Test
    fun a_bracketed_paste_stays_whole_across_chunks() {
        val paste = "\u001b[200~line one\rline two\u001b[201~"
        assertEquals(listOf("a", paste, "\r"), up("a$paste\r"))
        assertEquals(
            listOf("\u001b[200~one\rtwo", "\u001b[201~", "\r"),
            up("\u001b[200~one\rtwo\u001b[2", "01~\r"),
        )
    }

    @Test
    fun a_long_paste_goes_up_in_frames_of_at_most_8_kib_that_rejoin_exactly() {
        // 40 KB with two- and four-byte characters, so a frame edge could fall inside one.
        val body = buildString { while (length < 40_000) append("line é 😀 ").append(length).append('\n') }
        val paste = "\u001b[200~$body\u001b[201~".toByteArray()
        // The emulator hands a long paste over in 16 KiB pieces.
        val frames =
            upBytes(
                *paste
                    .toList()
                    .chunked(16 * 1024)
                    .map { it.toByteArray() }
                    .toTypedArray(),
            )

        assertTrue(frames.size >= 5)
        assertTrue(frames.all { it.size <= KeyUplink.MAX_FRAME_BYTES })
        frames.forEach { frame -> assertEquals(frame.size, String(frame).toByteArray().size, "whole characters only") }
        assertContentEquals(paste, frames.reduce { a, b -> a + b })
    }

    @Test
    fun pasted_text_cannot_end_the_paste_early_or_type_keys_and_a_huge_paste_is_refused() {
        assertEquals("[201~rm -rf\n\tok", sanitizePaste("\u001b[201~rm -rf\n\tok\u0003\u009b"))
        assertEquals("ok", preparePaste("ok"))
        assertNull(preparePaste("x".repeat(MAX_PASTE_BYTES + 1)))
    }

    private companion object {
        // Tests run with the module as the working directory.
        val VECTOR_FILE = File("../bridge/src/test/resources/terminal-input-vectors.txt")

        fun sharedVectors(): List<Triple<String, String, String>> =
            VECTOR_FILE
                .readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { line ->
                    val (verdict, bytes, note) = line.split('\t')
                    Triple(verdict, bytes, note)
                }

        /** The file's escapes: `\e`, `\r`, `\n`, `\t`, `\xHH` and `\\`. Anything else is itself, as UTF-8. */
        fun decodeVector(text: String): ByteArray {
            val out = ByteArrayOutputStream()
            var i = 0
            while (i < text.length) {
                if (text[i] != '\\' || i + 1 >= text.length) {
                    val codePoint = text.codePointAt(i)
                    out.write(String(Character.toChars(codePoint)).toByteArray())
                    i += Character.charCount(codePoint)
                    continue
                }
                when (val next = text[i + 1]) {
                    'e' -> {
                        out.write(0x1b)
                    }

                    'r' -> {
                        out.write(0x0d)
                    }

                    'n' -> {
                        out.write(0x0a)
                    }

                    't' -> {
                        out.write(0x09)
                    }

                    '\\' -> {
                        out.write(0x5c)
                    }

                    'x' -> {
                        out.write(text.substring(i + 2, i + 4).toInt(16))
                        i += 2
                    }

                    else -> {
                        out.write("\\$next".toByteArray())
                    }
                }
                i += 2
            }
            return out.toByteArray()
        }
    }
}
