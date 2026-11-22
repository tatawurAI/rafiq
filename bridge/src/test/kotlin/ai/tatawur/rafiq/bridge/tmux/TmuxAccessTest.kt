package ai.tatawur.rafiq.bridge.tmux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The two switches and the second list of #60, decided without tmux. */
class TmuxAccessTest {
    private fun settings(vararg vars: Pair<String, String>) =
        TerminalSettings.fromEnvironment(mapOf(*vars)::get, "127.0.0.1")

    private val listedOnly = TmuxAccess(listed = true, terminal = false)
    private val opens = TmuxAccess(listed = true, terminal = true)
    private val hidden = TmuxAccess(listed = false, terminal = false)

    @Test
    fun both_switches_are_off_by_default() {
        val off = settings("RAFIQ_TMUX_SESSIONS" to "weather-app", "RAFIQ_TERMINAL_SHELLS" to "weather-app")
        assertEquals(hidden, off.access("weather-app", agentDetected = true))
        assertEquals(hidden, off.access("weather-app", agentDetected = false))
    }

    @Test
    fun with_listing_on_and_terminal_off_a_session_is_listed_but_never_opens() {
        val listing =
            settings(
                "RAFIQ_TMUX" to "1",
                "RAFIQ_TMUX_SESSIONS" to "weather-app",
                "RAFIQ_TERMINAL_SHELLS" to "weather-app",
            )
        assertEquals(listedOnly, listing.access("weather-app", agentDetected = true))
        assertEquals(listedOnly, listing.access("weather-app", agentDetected = false))
    }

    @Test
    fun a_session_with_an_agent_opens_without_being_on_the_second_list() {
        val on = settings("RAFIQ_TERMINAL" to "1", "RAFIQ_TMUX_SESSIONS" to "weather-app")
        assertEquals(opens, on.access("weather-app", agentDetected = true))
    }

    @Test
    fun a_session_with_no_agent_opens_only_once_it_is_on_the_second_list() {
        val sessions = "RAFIQ_TMUX_SESSIONS" to "weather-app,docs-site"
        val before = settings("RAFIQ_TERMINAL" to "1", sessions)
        assertEquals(listedOnly, before.access("docs-site", agentDetected = false))

        val after = settings("RAFIQ_TERMINAL" to "1", sessions, "RAFIQ_TERMINAL_SHELLS" to "docs-site")
        assertEquals(opens, after.access("docs-site", agentDetected = false))
        // The entry is exact: it does not open a session whose name only starts with it.
        assertEquals(hidden, after.access("docs-site-2", agentDetected = false))
        assertEquals(listedOnly, after.access("weather-app", agentDetected = false))
    }

    @Test
    fun the_terminal_switch_alone_still_lists_and_opens_as_the_setup_guide_says() {
        val on = settings("RAFIQ_TERMINAL" to "1", "RAFIQ_TMUX_SESSIONS" to "weather-app")
        assertTrue(on.enabled)
        assertTrue(on.allows("weather-app"))
    }

    @Test
    fun a_bad_name_in_the_second_list_fails_the_start_and_names_the_variable() {
        for (bad in listOf("docs*", "docs site", "docs;rm", "../docs", "weather-app")) {
            val failure =
                assertFailsWith<IllegalArgumentException>(bad) {
                    settings(
                        "RAFIQ_TERMINAL" to "1",
                        "RAFIQ_TMUX_SESSIONS" to "docs-site",
                        "RAFIQ_TERMINAL_SHELLS" to bad,
                    )
                }
            assertTrue("RAFIQ_TERMINAL_SHELLS" in failure.message.orEmpty(), "$bad: ${failure.message}")
        }
    }

    @Test
    fun listing_alone_needs_named_sessions_and_a_loopback_bind() {
        val noNames = assertFailsWith<IllegalArgumentException> { settings("RAFIQ_TMUX" to "1") }
        assertTrue("RAFIQ_TMUX_SESSIONS" in noNames.message.orEmpty(), noNames.message)
        val remote =
            assertFailsWith<IllegalArgumentException> {
                TerminalSettings.fromEnvironment(
                    mapOf("RAFIQ_TMUX" to "1", "RAFIQ_TMUX_SESSIONS" to "weather-app")::get,
                    "100.64.0.1",
                )
            }
        assertTrue("RAFIQ_BIND_ADDRESS" in remote.message.orEmpty(), remote.message)
    }
}
