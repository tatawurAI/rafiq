package ai.tatawur.rafiq.client.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Pane bytes are untrusted: no clipboard write, no title, no image, no link without an https target (G14). */
class PaneHardeningTest {
    private fun down(vararg frames: String): String = down(*frames.map { it.toByteArray() }.toTypedArray())

    private fun down(vararg frames: ByteArray): String {
        val filter = PaneFilter()
        return String(frames.map { filter.filter(it) }.reduce { a, b -> a + b })
    }

    @Test
    fun clipboard_title_and_image_sequences_never_reach_the_emulator() {
        val hostile =
            listOf(
                "\u001b]52;c;cm0gLXJmIH4=\u0007", // clipboard write
                "\u001b]52;c;cm0gLXJmIH4=\u001b\\",
                "\u001b]0;sudo password\u0007", // window title
                "\u001b]2;title\u001b\\",
                "\u001b]1337;File=inline=1:AAAA\u0007", // inline image
                "\u001b_Ga=T,f=100;AAAA\u001b\\", // kitty image
                "\u001bPq#0;2;0;0;0#0!10~\u001b\\", // sixel image
                "\u001b]10;?\u0007", // colour query
            )
        for (sequence in hostile) assertEquals("before after", down("before $sequence", "after"), sequence)
    }

    @Test
    fun c1_controls_written_as_utf8_are_filtered_like_their_esc_forms() {
        // U+009D is OSC and U+0090 is DCS: the same clipboard write and image, in their C1 forms.
        assertEquals("before after", down("before \u009d52;c;cm0gLXJmIH4=\u0007", "after"))
        assertEquals("before after", down("before \u0090q#0;2;0;0;0#0!10~\u009c", "after"))
        assertEquals("ab", down("a\u009d0;title\u009c", "b"))
        // Split between the two bytes of the C1 character.
        val osc = "\u009d52;c;eA==\u0007b".toByteArray()
        assertEquals("ab", down(byteArrayOf('a'.code.toByte(), osc[0]), osc.copyOfRange(1, osc.size)))
        // Raw C1 bytes, not inside a UTF-8 character, are read the same way.
        val raw = byteArrayOf(0x9d.toByte()) + "52;c;cm0gLXJmIH4=\u0007after".toByteArray()
        assertEquals("before after", down("before ".toByteArray(), raw))
        // Continuation bytes in 0x80 to 0x9F inside a real character are not C1: € is E2 82 AC.
        assertEquals("€ and £", down("€ and £"))
        // A C1 CSI is read as ESC [, and ordinary text with a C2 lead byte is untouched.
        assertEquals("\u001b[31mred £", down("\u009b31mred £"))
    }

    @Test
    fun a_sequence_split_across_frames_is_still_removed() {
        assertEquals("ab", down("a\u001b]5", "2;c;cm0g", "LXJm\u001b", "\\b"))
    }

    @Test
    fun colours_cursor_moves_and_links_pass_through() {
        val ordinary = "\u001b[31mred\u001b[0m\u001b[2;3H\u001b(B\u0007"
        assertEquals(ordinary, down(ordinary))
        val link = "\u001b]8;;https://example.com/docs\u001b\\docs\u001b]8;;\u001b\\"
        assertEquals(link, down(link))
    }

    @Test
    fun only_an_https_link_with_a_host_may_open() {
        assertEquals("https://example.com/a?b=c", httpsLinkTarget("https://example.com/a?b=c"))
        val refused =
            listOf(
                "http://example.com",
                "javascript:alert(1)",
                "file:///etc/passwd",
                "intent://scan#Intent;scheme=zxing;end",
                "https:///no-host",
                "https://user:secret@example.com",
                "https://example.com/\u001b[31m",
                "",
            )
        for (link in refused) assertNull(httpsLinkTarget(link), link)
    }
}
