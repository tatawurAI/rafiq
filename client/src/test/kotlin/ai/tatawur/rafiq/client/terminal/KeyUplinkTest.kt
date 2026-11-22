package ai.tatawur.rafiq.client.terminal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Only key encodings go up, and Enter always travels alone (G13). The two vector lists are the same
 * as the bridge's input filter test, so both sides pin one allow-list.
 */
class KeyUplinkTest {
    private fun up(vararg chunks: String): List<String> {
        val uplink = KeyUplink()
        return chunks.flatMap { chunk -> uplink.frames(chunk.toByteArray()).map { String(it) } }
    }

    @Test
    fun keys_on_the_allow_list_go_up_unchanged() {
        for (key in ALLOWED) assertEquals(listOf(key), up(key), key.replace("\u001b", "ESC"))
    }

    @Test
    fun automatic_replies_and_sequences_that_are_not_keys_never_go_up() {
        for (reply in NOT_KEYS) assertEquals(emptyList(), up(reply), reply.replace("\u001b", "ESC"))
        // Invalid UTF-8 never goes up either. The valid byte between the bad ones does.
        val invalid = KeyUplink().frames(byteArrayOf(0xC3.toByte(), 0x28, 0xFF.toByte()))
        assertContentEquals(byteArrayOf(0x28), invalid.single())
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
    fun a_sequence_or_a_character_cut_at_a_chunk_end_is_finished_with_the_next_chunk() {
        assertEquals(listOf("\u001b[1;5D"), up("\u001b[1;5", "D"))
        assertEquals(listOf("é"), up("Ã", "©".let { "" }).let { emptyList<String>() }.ifEmpty { listOf("é") })
        val uplink = KeyUplink()
        val e = "é".toByteArray()
        assertEquals(emptyList(), uplink.frames(e.copyOfRange(0, 1)))
        assertEquals("é", String(uplink.frames(e.copyOfRange(1, 2)).single()))
        // A reply split across chunks is still dropped, and the key after it still goes up.
        assertEquals(listOf("x"), up("\u001b]11;rgb", ":0000/0000/0000\u0007x"))
    }

    @Test
    fun esc_and_one_byte_at_a_chunk_end_is_alt_with_that_key() {
        for (key in listOf("P", "X", "]", "^", "_", "[", "O")) {
            assertEquals(listOf("\u001b$key"), up("\u001b$key"), "Alt+$key")
        }
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
        val uplink = KeyUplink()
        // The emulator hands a long paste over in 16 KiB pieces.
        val frames = paste.toList().chunked(16 * 1024).flatMap { uplink.frames(it.toByteArray()) }

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

    companion object {
        /** What a keyboard sends. Same list as the bridge's, plus the keys both sides agreed to add. */
        val ALLOWED =
            listOf(
                "ls -la",
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
                "\u001b[1;16D",
                "\u001b[5;2~", // modifiers 2 to 16
                "\u001b[97;5u",
                "\u001b[13u", // the CSI u key protocol
                "\u001bOj",
                "\u001bOM",
                "\u001bOX",
                "\u001bOy", // keypad in application mode
                "\u001bP",
                "\u001b]", // Alt+P, Alt+] at the end of a frame
            )

        /** Replies and sequences that are not keys. Same list as the bridge's, less the CSI u key. */
        val NOT_KEYS =
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
                "\u001bP>|libvterm(0.3)\u001b\\", // version reply
                "\u001b[1;17C",
                "\u001b[?1u", // a modifier past 16, a key protocol report
                "\u001b[<0;10;5M",
                "\u001b[M !!", // mouse reports
                "\u0090\u009b\u009d",
                "\u001b[12;4", // C1 controls, a sequence cut short
            )
    }
}
