package ai.tatawur.rafiq.client.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

/** Only key encodings go up, and Enter always travels alone (G13). */
class KeyUplinkTest {
    private fun up(vararg chunks: String): List<String> {
        val uplink = KeyUplink()
        return chunks.flatMap { chunk -> uplink.frames(chunk.toByteArray()).map { String(it) } }
    }

    @Test
    fun automatic_replies_to_terminal_queries_never_go_up() {
        val replies =
            listOf(
                "\u001b[?62;22c", // device attributes
                "\u001b[>1;10;0c", // secondary device attributes
                "\u001b[12;40R", // cursor position
                "\u001b[1;5R", // cursor position that looks like Ctrl-F3
                "\u001b[0n", // status
                "\u001b[?2004;1\$y", // mode report
                "\u001b[I", // focus in
                "\u001b]11;rgb:0000/0000/0000\u001b\\", // colour report
                "\u001bP>|libvterm(0.3)\u001b\\", // version report
                "\u001b[<0;10;5M", // SGR mouse report
                "\u001b[M !!", // X10 mouse report
                "\u001b[?1u", // keyboard protocol report
            )
        for (reply in replies) assertEquals(emptyList(), up(reply), reply)
    }

    @Test
    fun keys_on_the_allow_list_go_up_unchanged() {
        val keys =
            listOf(
                "ls -la", // text
                "é😀", // UTF-8
                "\u0003", // Ctrl-C
                "\t",
                "\u007f", // Backspace
                "\u001b", // Esc
                "\u001bx", // Alt-x
                "\u001b[", // Alt-[ alone
                "\u001b]", // Alt-] alone
                "\u001b[A", // Up
                "\u001b[1;5D", // Ctrl-Left
                "\u001bOA", // Up in application mode
                "\u001bOP", // F1
                "\u001b[3~", // Delete
                "\u001b[5;2~", // Shift-Page Up
                "\u001b[24~", // F12
                "\u001b[Z", // Shift-Tab
                "\u001b[99;5u", // Ctrl-c in the keyboard protocol
            )
        for (key in keys) assertEquals(listOf(key), up(key), key)
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
    fun a_bracketed_paste_stays_in_one_frame_even_across_chunks() {
        val paste = "\u001b[200~line one\rline two\u001b[201~"
        assertEquals(listOf("a", paste, "\r"), up("a$paste\r"))

        // The emulator hands a long paste over in pieces, and the end marker can be split too.
        assertEquals(
            listOf("\u001b[200~one\rtwo", "\u001b[201~", "\r"),
            up("\u001b[200~one\rtwo\u001b[2", "01~\r"),
        )
    }

    @Test
    fun pasted_text_cannot_end_the_paste_early_or_type_keys() {
        assertEquals("[201~rm -rf\n\tok", sanitizePaste("\u001b[201~rm -rf\n\tok\u0003\u009b"))
    }
}
