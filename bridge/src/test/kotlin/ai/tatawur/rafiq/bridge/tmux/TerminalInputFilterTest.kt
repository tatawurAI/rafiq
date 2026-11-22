package ai.tatawur.rafiq.bridge.tmux

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalInputFilterTest {
    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8)

    private fun filter(
        text: String,
        filter: TerminalInputFilter = TerminalInputFilter(),
    ) = String(filter.filter(bytes(text)), Charsets.UTF_8)

    @Test
    fun what_a_keyboard_sends_goes_through_unchanged() {
        val keys =
            listOf(
                "ls -la\r",
                "héllo مرحبا 👍",
                "\t",
                "\u007f",
                "\u0003",
                "\u001b", // text, Tab, Backspace, Ctrl+C, Esc
                "\u001b[A",
                "\u001b[B",
                "\u001bOA",
                "\u001bOH", // arrows, normal and application mode, Home
                "\u001b[3~",
                "\u001b[5~",
                "\u001b[15~",
                "\u001b[Z", // Delete, page up, F5, Shift+Tab
                "\u001b[1;5C",
                "\u001b[1;2P",
                "\u001bx", // Ctrl+Right, Shift+F1, Alt+x
            )
        for (key in keys) assertEquals(key, filter(key), "key ${key.replace("\u001b", "ESC")}")
    }

    @Test
    fun terminal_replies_and_sequences_that_are_not_keys_are_dropped() {
        val notKeys =
            listOf(
                "\u001b[?62;22c",
                "\u001b[>1;10;0c", // device attributes
                "\u001b[12;40R",
                "\u001b[1;5R", // cursor position, also when it looks like a modified F3
                "\u001b[0n",
                "\u001b[?2004;1\$y", // status and mode reports
                "\u001b[I",
                "\u001b[O", // focus
                "\u001b]11;rgb:0000/0000/0000\u0007",
                "\u001b]52;c;aGk=\u001b\\", // colour reply, clipboard
                "\u001bP>|libvterm(0.3)\u001b\\",
                "\u001b[97;5u", // version reply, a key protocol the pane never asked for
                "\u0090\u009b\u009d",
                "\u001b[12;4", // C1 controls, a sequence cut short
            )
        for (reply in notKeys) assertEquals("", filter(reply), reply.replace("\u001b", "ESC"))
        // Invalid UTF-8 never reaches the pane either. The valid byte between the bad ones does.
        assertContentEquals(
            byteArrayOf(0x28),
            TerminalInputFilter().filter(byteArrayOf(0xC3.toByte(), 0x28, 0xFF.toByte())),
        )
    }

    @Test
    fun a_reply_between_keys_is_cut_out_and_the_keys_around_it_stay() {
        assertEquals("a\u001b[Bb", filter("a\u001b[?62c\u001b[B\u001b[5;5Rb"))
    }

    @Test
    fun a_paste_keeps_its_text_and_loses_any_escape_inside_it_even_across_frames() {
        val paste = TerminalInputFilter()

        assertEquals("\u001b[200~line one\r\nline\ttwo", filter("\u001b[200~line one\r\nline\ttwo", paste))
        assertTrue(paste.isPasting)
        // A reply and an Escape inside the paste are text-only: the escape goes, the rest is harmless text.
        assertEquals(" more", filter("\u001b[12;40R\u001b[31m more", paste))
        assertEquals("\u001b[201~", filter("\u001b[201~", paste))
        assertFalse(paste.isPasting)
        assertEquals("x", filter("x", paste))
    }
}
