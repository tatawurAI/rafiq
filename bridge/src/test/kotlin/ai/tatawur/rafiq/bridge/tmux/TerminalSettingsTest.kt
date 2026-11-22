package ai.tatawur.rafiq.bridge.tmux

import ai.tatawur.rafiq.bridge.BridgeConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalSettingsTest {
    private fun settings(
        vararg vars: Pair<String, String>,
        bind: String = "127.0.0.1",
    ) = TerminalSettings.fromEnvironment(mapOf(*vars)::get, bind)

    private fun message(block: () -> Unit) = assertFailsWith<IllegalArgumentException> { block() }.message.orEmpty()

    @Test
    fun terminal_is_off_by_default_and_lists_nothing() {
        val off = settings()

        assertFalse(off.enabled)
        assertFalse(off.allows("weather-app"))
        // Naming sessions is not enough: the switch is separate.
        assertFalse(settings("RAFIQ_TMUX_SESSIONS" to "weather-app").allows("weather-app"))
    }

    @Test
    fun a_name_with_a_semicolon_a_star_or_a_space_is_rejected_and_the_message_names_the_variable() {
        for (bad in listOf("weather;app", "*", "weather app", "a,b;c", "../x")) {
            val text = message { settings("RAFIQ_TERMINAL" to "1", "RAFIQ_TMUX_SESSIONS" to bad) }
            assertTrue("RAFIQ_TMUX_SESSIONS" in text, "$bad: $text")
        }
    }

    @Test
    fun only_exactly_the_listed_names_are_allowed() {
        val on = settings("RAFIQ_TERMINAL" to "1", "RAFIQ_TMUX_SESSIONS" to "weather-app, docs-site")

        assertTrue(on.allows("weather-app"))
        assertTrue(on.allows("docs-site"))
        assertFalse(on.allows("weather-app-2"))
        assertFalse(on.allows("weather"))
    }

    @Test
    fun terminal_on_with_no_session_named_refuses_to_start() {
        assertTrue("RAFIQ_TMUX_SESSIONS" in message { settings("RAFIQ_TERMINAL" to "1") })
    }

    @Test
    fun a_bad_socket_name_fails_the_start_and_names_the_variable() {
        assertTrue("RAFIQ_TMUX_SOCKET" in message { settings("RAFIQ_TMUX_SOCKET" to "/tmp/x; rm") })
        assertEquals("work", settings("RAFIQ_TMUX_SOCKET" to "work").socket)
    }

    @Test
    fun a_non_loopback_bind_is_refused_unless_the_override_is_set() {
        val on = arrayOf("RAFIQ_TERMINAL" to "1", "RAFIQ_TMUX_SESSIONS" to "weather-app")

        for (bind in listOf("0.0.0.0", "100.64.0.1", "192.168.1.5", "box.example.ts.net", "::")) {
            assertTrue("RAFIQ_BIND_ADDRESS" in message { settings(*on, bind = bind) }, bind)
        }
        for (bind in listOf("127.0.0.1", "localhost", "127.1.2.3", "::1", "[::1]")) {
            assertTrue(settings(*on, bind = bind).enabled, bind)
        }
        assertTrue(settings(*on, "RAFIQ_TERMINAL_ALLOW_NON_LOOPBACK" to "1", bind = "100.64.0.1").enabled)
    }

    @Test
    fun the_bridge_config_carries_the_gate_and_refuses_the_bad_bind_at_load() {
        val env =
            mapOf(
                "RAFIQ_TERMINAL" to "1",
                "RAFIQ_TMUX_SESSIONS" to "weather-app",
                "RAFIQ_BIND_ADDRESS" to "0.0.0.0",
            )

        assertFailsWith<IllegalArgumentException> { BridgeConfig.fromEnvironment(env) }
        assertTrue(BridgeConfig.fromEnvironment(env - "RAFIQ_BIND_ADDRESS").terminal.allows("weather-app"))
    }
}
