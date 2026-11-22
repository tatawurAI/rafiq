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
}
