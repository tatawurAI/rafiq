package ai.tatawur.rafiq.client.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Pane bytes are untrusted: no clipboard write, no title, no image, no link without an https target (G14). */
class PaneHardeningTest {
    private fun down(vararg frames: String): String {
        val filter = PaneFilter()
        return frames.joinToString("") { String(filter.filter(it.toByteArray())) }
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
