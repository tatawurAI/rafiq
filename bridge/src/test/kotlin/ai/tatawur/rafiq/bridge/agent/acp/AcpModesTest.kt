package ai.tatawur.rafiq.bridge.agent.acp

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** How the ACP adapter reads, follows and changes a session's permission modes (#33). */
@Timeout(60)
class AcpModesTest {
    @TempDir
    lateinit var projectPath: Path

    @Test
    fun the_advertised_modes_are_read_followed_and_changed_with_set_mode() =
        runFakeAgent(projectPath, "modes.jsonl") { run ->
            val session = run.session
            assertEquals(
                listOf("default", "acceptEdits", "plan", "auto"),
                session.modes.value.available
                    .map { it.id },
            )
            assertEquals(
                "Always ask before making changes",
                session.modes.value.available
                    .first()
                    .description,
            )

            // The agent switched to plan on its own right after opening.
            session.modes.first { it.currentModeId == "plan" }
            session.setMode("default")

            assertEquals("default", session.modes.value.currentModeId)
            session.close()
            val setMode = run.sentByBridge().single { it.method() == "session/set_mode" }
            val params = setMode.getValue("params").jsonObject
            assertEquals(JsonPrimitive("sess_modes"), params["sessionId"])
            assertEquals(JsonPrimitive("default"), params["modeId"])
        }

    @Test
    fun a_mode_the_agent_does_not_offer_is_refused_before_anything_is_sent() =
        runFakeAgent(projectPath, "modes.jsonl") { run ->
            assertFailsWith<IllegalArgumentException> { run.session.setMode("bypassPermissions") }
        }

    @Test
    fun an_agent_offering_modes_only_as_a_grouped_config_option_is_changed_with_set_config_option() {
        val modes = AcpModes()
        modes.advertised(
            json(
                """{"sessionId":"s","configOptions":[
                  {"id":"model","name":"Model","category":"model","type":"select","currentValue":"a","options":[]},
                  {"id":"approvals","name":"Approvals","category":"mode","type":"select","currentValue":"ask",
                   "options":[{"group":"safe","name":"Safe","options":[{"value":"ask","name":"Ask"}]},
                              {"group":"fast","name":"Fast","options":[{"value":"edit","name":"Edit freely"}]}]}]}""",
            ),
        )

        assertEquals(
            listOf("ask", "edit"),
            modes.modes.value.available
                .map { it.id },
        )
        val (method, params) = modes.request("s", "edit")
        assertEquals("session/set_config_option", method.name)
        assertEquals(JsonPrimitive("approvals"), params["configId"])
        assertEquals(JsonPrimitive("edit"), params["value"])

        // The answer to set_config_option is the full new state, and it wins.
        modes.accepted(
            "edit",
            json(
                """{"configOptions":[{"id":"approvals","name":"Approvals","category":"mode","type":"select",
                  "currentValue":"ask","options":[{"value":"ask","name":"Ask"}]}]}""",
            ),
        )
        assertEquals("ask", modes.modes.value.currentModeId)
        assertEquals(
            listOf("ask"),
            modes.modes.value.available
                .map { it.id },
        )
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun JsonObject.method(): String? = (this["method"] as? JsonPrimitive)?.content
}
