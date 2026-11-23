package ai.tatawur.rafiq.client.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TerminalWireTest {
    @Test
    fun a_snapshot_restores_the_pane_modes_then_places_each_line_by_row_and_the_cursor_last() {
        val decoder = TerminalStreamDecoder()
        val json =
            """{"type":"snapshot","cols":10,"rows":3,"cursor_x":2,"cursor_y":1,""" +
                """"modes":{"bracketed_paste":true,"app_cursor_keys":true,"mouse":true}}"""
        assertEquals(null, decoder.onText(json))

        val event = decoder.onBinary("\u001b[31mred\r\nsecond\nthird\nignored-extra-row".toByteArray())

        assertIs<TerminalEvent.Snapshot>(event)
        assertEquals(CellSize(10, 3), event.size)
        assertEquals(
            "\u0018\u001bc\u001b[?2004h\u001b[?1h" +
                "\u001b[1;1H\u001b[31mred\u001b[2;1Hsecond\u001b[3;1Hthird\u001b[0m\u001b[2;3H",
            String(event.vt),
        )
        // The next binary frame is live output again.
        assertIs<TerminalEvent.Output>(decoder.onBinary("x".toByteArray()))
    }

    @Test
    fun a_message_type_this_build_does_not_know_is_ignored() {
        val decoder = TerminalStreamDecoder()
        assertEquals(null, decoder.onText("""{"type":"bell_v2","volume":3}"""))
        assertEquals(null, decoder.onText("not json"))
        assertIs<TerminalEvent.Output>(decoder.onBinary("x".toByteArray()))
    }

    @Test
    fun the_snapshot_names_the_mirrored_window_as_plain_text_and_none_when_the_bridge_leaves_it_out() {
        fun windowOf(json: String): MirroredWindow? {
            val decoder = TerminalStreamDecoder()
            decoder.onText(json)
            return (decoder.onBinary(ByteArray(0)) as TerminalEvent.Snapshot).window
        }
        val base = """{"type":"snapshot","cols":80,"rows":24,"cursor_x":0,"cursor_y":0"""

        assertEquals(MirroredWindow(1, "build"), windowOf("""$base,"window":{"index":1,"name":"build"}}"""))
        assertEquals(null, windowOf("$base}"))
        // The name is tmux's and can hold anything: controls show as escapes and it is cut short.
        val hostile = "\\u001b[2Jx" + "y".repeat(100)
        val window = windowOf("""$base,"window":{"index":3,"name":"$hostile"}}""")
        val cut = ("\u001b[2Jx" + "y".repeat(100)).take(MirroredWindow.MAX_NAME).replace("\u001b", "<U+001B>")
        assertEquals(MirroredWindow(3, cut), window)
    }

    @Test
    fun the_window_list_and_notices_reach_the_view_as_plain_text() {
        val decoder = TerminalStreamDecoder()

        val windows =
            decoder.onText(
                """{"type":"windows","windows":[{"index":1,"name":"build"},""" +
                    """{"index":2,"name":"lo\u001b[2Jgs"}],"shown":2}""",
            )
        val notice = decoder.onText("""{"type":"notice","code":"window_closed","message":"Window 2 closed.\u0007"}""")

        assertEquals(
            TerminalEvent.Windows(listOf(MirroredWindow(1, "build"), MirroredWindow(2, "lo<U+001B>[2Jgs")), 2),
            windows,
        )
        assertEquals(TerminalEvent.Notice("window_closed", "Window 2 closed.<U+0007>"), notice)
    }
}
