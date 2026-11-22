package ai.tatawur.rafiq.protocol

import ai.tatawur.rafiq.protocol.api.TerminalEnded
import ai.tatawur.rafiq.protocol.api.TerminalMessage
import ai.tatawur.rafiq.protocol.api.TerminalModes
import ai.tatawur.rafiq.protocol.api.TerminalResize
import ai.tatawur.rafiq.protocol.api.TerminalSize
import ai.tatawur.rafiq.protocol.api.TerminalSnapshot
import ai.tatawur.rafiq.protocol.api.TerminalUnknown
import ai.tatawur.rafiq.protocol.api.TerminalWindow
import ai.tatawur.rafiq.protocol.api.terminalJson
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalMessageTest {
    private fun encode(message: TerminalMessage) = terminalJson.encodeToString(TerminalMessage.serializer(), message)

    private fun decode(json: String) = terminalJson.decodeFromString(TerminalMessage.serializer(), json)

    @Test
    fun terminal_messages_have_the_documented_wire_shape() {
        assertEquals(
            """{"type":"snapshot","cols":80,"rows":24,"cursor_x":3,"cursor_y":5,""" +
                """"modes":{"bracketed_paste":true,"app_cursor_keys":false,"mouse":false}}""",
            encode(TerminalSnapshot(80, 24, 3, 5, TerminalModes(bracketedPaste = true))),
        )
        assertEquals("""{"type":"size","cols":100,"rows":30}""", encode(TerminalSize(100, 30)))
        assertEquals("""{"type":"ended","reason":"gone"}""", encode(TerminalEnded("gone")))
        assertEquals("""{"type":"resize","cols":60,"rows":20}""", encode(TerminalResize(60, 20)))
    }

    @Test
    fun a_snapshot_carries_the_window_and_one_without_it_still_decodes() {
        val head = """{"type":"snapshot","cols":80,"rows":24,"cursor_x":0,"cursor_y":0"""
        val window = TerminalWindow(2, "api server")

        val json = """$head,"window":{"index":2,"name":"api server"}}"""
        assertEquals(TerminalSnapshot(80, 24, 0, 0, window = window), decode(json))
        val written = encode(TerminalSnapshot(80, 24, 0, 0, window = window))
        assertEquals(true, """"window":{"index":2,"name":"api server"}""" in written)
        assertEquals(null, (decode("$head}") as TerminalSnapshot).window)
        assertEquals(false, "window" in encode(TerminalSnapshot(80, 24, 0, 0)))
    }

    @Test
    fun a_snapshot_without_modes_still_decodes() {
        // An older bridge sends no modes: every mode is off.
        assertEquals(
            TerminalSnapshot(80, 24, 0, 0),
            decode("""{"type":"snapshot","cols":80,"rows":24,"cursor_x":0,"cursor_y":0}"""),
        )
    }

    @Test
    fun an_unknown_field_and_an_unknown_type_decode_without_error() {
        assertEquals(
            TerminalResize(60, 20),
            decode("""{"type":"resize","cols":60,"rows":20,"from_the_future":{"a":1}}"""),
        )
        assertEquals(TerminalUnknown, decode("""{"type":"title","text":"hello"}"""))
    }

    @Test
    fun a_session_carries_terminal_and_defaults_to_false() {
        val session =
            protocolJson.decodeFromString(
                Session.serializer(),
                """{"id":"s","agent":"a","project_path":"/p","title":"t","created_at_ms":1,"updated_at_ms":2}""",
            )
        assertEquals(false, session.terminal)
        assertEquals(
            true,
            protocolJson
                .encodeToString(
                    Session.serializer(),
                    session.copy(terminal = true),
                ).contains("\"terminal\":true"),
        )
    }
}
