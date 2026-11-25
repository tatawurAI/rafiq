package ai.tatawur.rafiq.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The start-up warning for an agent whose sessions would start in a mode the agent picks itself (#33). */
class DefaultModeWarningTest {
    @Test
    fun only_an_agent_without_a_default_mode_is_warned_about_with_a_pointer_to_the_setup_guide() {
        val config =
            BridgeConfig(
                bindAddress = "127.0.0.1",
                port = 8740,
                dataDir = null,
                deviceToken = null,
                agents = listOf(AgentSpec("claude-code", listOf("claude-agent-acp")), AgentSpec("other", listOf("x"))),
                defaultModes = mapOf("claude-code" to "default"),
            )

        val warnings = defaultModeWarnings(config)

        assertEquals(1, warnings.size)
        assertTrue("other" in warnings.single() && "docs/setup.md" in warnings.single())
    }
}
