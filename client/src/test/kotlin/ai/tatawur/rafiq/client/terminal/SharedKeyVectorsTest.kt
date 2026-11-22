package ai.tatawur.rafiq.client.terminal

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The bridge's key vectors, read from the bridge's own test resources, so the app and the bridge
 * always pin the same allow-list. "accept" and "split" mean what goes up joins back to exactly
 * the input; "reject" means nothing goes up.
 */
class SharedKeyVectorsTest {
    private val vectors = File("../bridge/src/test/resources/terminal-input-vectors.txt")

    @Test
    fun the_app_accepts_exactly_what_the_bridge_accepts() {
        val lines = vectors.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue(lines.size > 50, "expected the shared vector file at ${vectors.absolutePath}")
        val wrong =
            lines.mapNotNull { line ->
                val (verdict, encoded, note) = line.split('\t')
                val frames = encoded.split(" | ").map(::decode)
                val uplink = KeyUplink()
                val up = frames.flatMap { uplink.frames(it) }.fold(ByteArray(0)) { all, frame -> all + frame }
                val expected = if (verdict == "reject") ByteArray(0) else frames.reduce { a, b -> a + b }
                if (up.contentEquals(expected)) null else "$verdict $encoded ($note)"
            }
        if (wrong.isNotEmpty()) fail("Disagrees with the bridge:\n" + wrong.joinToString("\n"))
    }

    @Test
    fun the_vector_escapes_decode_as_documented() {
        assertContentEquals(
            byteArrayOf(0x1b, 0x5b, 0x0d, 0x0a, 0x09, 0x5c, 0xff.toByte()),
            decode("\\e[\\r\\n\\t\\\\\\xff"),
        )
    }

    /** `\e`, `\r`, `\n`, `\t`, `\xHH` and `\\`. Anything else is itself, as UTF-8. */
    private fun decode(text: String): ByteArray {
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
