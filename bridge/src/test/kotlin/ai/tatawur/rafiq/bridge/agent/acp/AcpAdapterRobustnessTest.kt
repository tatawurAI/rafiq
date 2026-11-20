package ai.tatawur.rafiq.bridge.agent.acp

import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.MessageRole
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How the adapter holds up when the agent misbehaves or the timing is unkind. Each test starts
 * FakeAcpAgent as a real process. The timeout only stops a hung test; nothing waits on the clock.
 */
@Timeout(120)
class AcpAdapterRobustnessTest {
    @TempDir
    lateinit var projectPath: Path

    @Test
    fun a_burst_of_chunks_before_the_answer_all_lands_before_the_turn_ends() {
        // The race this guards against shows only now and then, so the same run is repeated.
        val runs = 20
        val outOfOrder =
            (1..runs).count {
                var wrong = false
                runFakeAgent(projectPath, "burst-ordering.jsonl") { run ->
                    run.session.prompt(MAIN_THREAD_ID, "Write a long answer.")
                    val events = run.drain()
                    val turnEnded = events.indexOfFirst(::isTurnEnd)
                    // A missing turn end counts as out of order too: it was dropped.
                    val before = events.take(turnEnded.coerceAtLeast(0))
                    val after = events.drop(turnEnded.coerceAtLeast(0))
                    wrong = turnEnded < 0 ||
                        before.count { it is EventPayload.MessageDelta } != 299 ||
                        after.any { it is EventPayload.MessageDelta } ||
                        after.none { it is EventPayload.Message && "after the turn" in it.text() }
                }
                wrong
            }
        assertEquals(0, outOfOrder, "$outOfOrder of $runs runs moved the turn's end away from its wire position.")
    }

    @Test
    fun an_agent_that_dies_mid_turn_ends_the_session_with_an_error() =
        runFakeAgent(projectPath, "dies-mid-turn.jsonl") { run ->
            run.session.prompt(MAIN_THREAD_ID, "Check the forecast.")
            val events = run.drain()

            val last = events.last() as EventPayload.StatusChanged
            assertEquals(null, last.threadId)
            assertEquals(AgentState.ERROR, last.state)
            assertTrue(last.detail.orEmpty().startsWith(EXITED), "Reported as an exit: ${last.detail}")
            assertTrue(
                events.filterIsInstance<EventPayload.Message>().any {
                    !it.isStreaming && it.text() == "Looking at the forecast"
                },
                "The message that was streaming is closed with what arrived.",
            )
        }

    @Test
    fun resume_reattaches_with_session_resume_and_maps_what_follows() =
        runFakeAgent(projectPath, "resume.jsonl", open = { it.resume("session-1", projectPath, "sess_old") }) { run ->
            assertEquals("sess_old", run.session.agentSessionRef)
            val events = run.drain()

            assertTrue(
                events.filterIsInstance<EventPayload.Message>().any {
                    it.threadId == MAIN_THREAD_ID && "Picking up" in it.text()
                },
            )
            val sent = run.sentByBridge()
            val resume = sent.single { it["method"] == JsonPrimitive("session/resume") }
            assertEquals(JsonPrimitive("sess_old"), resume.getValue("params").jsonObject["sessionId"])
            assertTrue(sent.none { it["method"] == JsonPrimitive("session/new") })
        }

    @Test
    fun a_line_that_is_not_json_and_one_too_long_do_not_stop_the_session() =
        runFakeAgent(projectPath, "garbage-and-long-lines.jsonl", maxLineChars = 1000) { run ->
            run.session.prompt(MAIN_THREAD_ID, "Go on.")
            val events = run.drain()
            val messages = events.filterIsInstance<EventPayload.Message>()

            assertTrue(messages.any { it.role == MessageRole.SYSTEM && "too long" in it.text() })
            assertTrue(messages.any { it.role == MessageRole.AGENT && it.text() == "still here" })
            assertTrue(events.any(::isTurnEnd), "The turn still ends normally.")
        }

    @Test
    fun the_agent_never_sees_the_bridges_own_settings() =
        runFakeAgent(
            projectPath,
            "unknown-update.jsonl",
            exitAtEnd = false,
            environment = mapOf("RAFIQ_DEVICE_TOKEN" to "test-token-0000", "RAFIQ_PORT" to "8740", "KEEP_ME" to "1"),
        ) { run ->
            val names = run.agentEnvironment()

            assertTrue("KEEP_ME" in names, "Other variables pass through: $names")
            assertTrue(names.none { it.startsWith("RAFIQ_") }, "No RAFIQ_ variable reaches the agent: $names")
        }
}

private fun isTurnEnd(event: EventPayload): Boolean =
    event is EventPayload.StatusChanged && event.threadId == MAIN_THREAD_ID && event.state == AgentState.WAITING_ON_YOU
