package ai.tatawur.rafiq.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BridgeConfigTest {
    @Test
    fun unset_environment_gives_the_documented_defaults() {
        val config = BridgeConfig.fromEnvironment(emptyMap())

        assertEquals("127.0.0.1", config.bindAddress)
        assertEquals(8740, config.port)
        assertNull(config.dataDir)
        assertNull(config.deviceToken)
    }

    @Test
    fun a_port_that_is_not_a_number_fails_at_start_up() {
        assertFailsWith<IllegalArgumentException> {
            BridgeConfig.fromEnvironment(mapOf("RAFIQ_PORT" to "87a0"))
        }
    }

    @Test
    fun the_device_token_never_appears_in_the_printed_config() {
        val config = BridgeConfig.fromEnvironment(mapOf("RAFIQ_DEVICE_TOKEN" to "test-token-0000"))

        assertFalse("test-token-0000" in config.toString())
    }

    @Test
    fun agents_and_the_dev_page_flag_are_read_and_a_bad_value_names_its_variable() {
        val config =
            BridgeConfig.fromEnvironment(
                mapOf(
                    "RAFIQ_AGENTS" to "claude-code=claude-agent-acp, codex=codex-acp --flag",
                    "RAFIQ_DEV_PAGE" to "1",
                ),
            )

        assertEquals(listOf("claude-code", "codex"), config.agents.map { it.id })
        assertEquals(listOf("codex-acp", "--flag"), config.agents[1].command)
        assertTrue(config.devPage)
        assertFalse(BridgeConfig.fromEnvironment(emptyMap()).devPage)
        assertTrue(
            "RAFIQ_AGENTS" in
                assertFailsWith<IllegalArgumentException> {
                    BridgeConfig.fromEnvironment(mapOf("RAFIQ_AGENTS" to "no-command"))
                }.message.orEmpty(),
        )
        assertTrue(
            "RAFIQ_DEV_PAGE" in
                assertFailsWith<IllegalArgumentException> {
                    BridgeConfig.fromEnvironment(mapOf("RAFIQ_DEV_PAGE" to "yes"))
                }.message.orEmpty(),
        )
    }
}
