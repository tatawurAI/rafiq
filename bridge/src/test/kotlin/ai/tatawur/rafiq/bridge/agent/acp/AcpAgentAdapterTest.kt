package ai.tatawur.rafiq.bridge.agent.acp

import ai.tatawur.rafiq.bridge.agent.TurnInProgressException
import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.ApprovalOptionKind
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.MessageRole
import ai.tatawur.rafiq.protocol.ThreadKind
import kotlinx.coroutines.CoroutineScope
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
import kotlin.test.assertTrue

// Each test starts FakeAcpAgent as a real process. The timeout only stops a hung test; nothing
// here waits on the clock.
@Timeout(60)
class AcpAgentAdapterTest {
    @TempDir
    lateinit var projectPath: Path

    @Test
    fun a_helper_announced_by_subagent_update_gets_its_own_thread_and_its_tool_calls() =
        runScript("with-subagents.jsonl") { run ->
            run.session.prompt(MAIN_THREAD_ID, "Launch two helpers.")
            val events = run.drain()

            val helper = "a74e84dbef0b26528"
            val announced = events.indexOfFirst { it is EventPayload.ThreadUpdated && it.thread.id == helper }
            val thread = (events[announced] as EventPayload.ThreadUpdated).thread
            assertEquals(ThreadKind.SUBAGENT, thread.kind)
            assertEquals(MAIN_THREAD_ID, thread.parentThreadId)
            assertEquals("Summarise README.md", thread.title)

            val read = events.filterIsInstance<EventPayload.ToolCall>().first { it.inputSummary == "Read README.md" }
            assertEquals(helper, read.threadId)
            assertTrue(announced < events.indexOf(read), "The thread is announced before its first tool call.")
            assertTrue(
                events.filterIsInstance<EventPayload.Message>().any {
                    it.threadId == helper && it.role == MessageRole.AGENT && "three-day forecast" in it.text()
                },
                "The helper's report is on the helper's thread.",
            )
            assertTrue(
                events.filterIsInstance<EventPayload.Message>().any {
                    it.threadId == helper && it.role == MessageRole.USER && "Read the file" in it.text()
                },
                "The parent's instructions are the helper's user message.",
            )
            assertTrue(
                events.filterIsInstance<EventPayload.Message>().none { "Async agent launched" in it.text() },
                "The root session's copy of the hand-off notes is not shown.",
            )
        }

    @Test
    fun without_the_capability_tagged_updates_are_grouped_into_a_helper_thread() =
        runScript("without-subagents.jsonl") { run ->
            run.session.prompt(MAIN_THREAD_ID, "Launch two helpers.")
            val events = run.drain()

            val helper = "toolu_01HFtNN1HEGNX74NsqZ6CpH1"
            val announced = events.indexOfFirst { it is EventPayload.ThreadUpdated && it.thread.id == helper }
            val thread = (events[announced] as EventPayload.ThreadUpdated).thread
            assertEquals(ThreadKind.SUBAGENT, thread.kind)
            assertEquals(MAIN_THREAD_ID, thread.parentThreadId)
            assertEquals("Summarise README", thread.title, "Titled from the tool call that launched it.")

            val calls = events.filterIsInstance<EventPayload.ToolCall>()
            val readme = calls.first { it.inputSummary == "Read README.md" }
            assertEquals(helper, readme.threadId)
            assertTrue(announced < events.indexOf(readme))
            assertEquals(
                "toolu_01VNBHszj3DFzZins6NbkDM3",
                calls.first { it.inputSummary == "Read forecast.txt" }.threadId,
            )
            assertTrue(
                events.filterIsInstance<EventPayload.Message>().filter { it.role == MessageRole.AGENT }.all {
                    it.threadId == MAIN_THREAD_ID
                },
                "The parent's own replies stay on the main thread.",
            )
        }

    @Test
    fun a_helper_permission_request_is_answered_with_the_chosen_option_and_cancel_targets_the_helper() =
        runScript("child-approval.jsonl") { run ->
            run.session.prompt(MAIN_THREAD_ID, "Run the tests.")
            val approval = run.awaitEvent { it is EventPayload.ApprovalRequest } as EventPayload.ApprovalRequest
            assertEquals("sess_child/call_001", approval.toolCallId)
            assertEquals(
                listOf(ApprovalOptionKind.ALLOW_ONCE, ApprovalOptionKind.DENY),
                approval.options.map { it.kind },
            )

            // The turn is still open, waiting on you, so a second prompt is refused rather than sent.
            assertFailsWith<TurnInProgressException> { run.session.prompt(MAIN_THREAD_ID, "And the docs.") }

            run.session.respondToApproval(approval.id, "allow-once")
            run.session.cancel("sess_child")
            run.drain()

            val sent = run.sentByBridge()
            val answer = sent.single { it["id"] == JsonPrimitive("perm-1") && "result" in it }
            assertEquals(
                Json.parseToJsonElement("""{"outcome":"selected","optionId":"allow-once"}"""),
                answer.getValue("result").jsonObject["outcome"],
            )
            val cancel = sent.single { it["method"] == JsonPrimitive("session/cancel") }
            assertEquals(JsonPrimitive("sess_child"), cancel.getValue("params").jsonObject["sessionId"])
        }

    @Test
    fun initialize_opts_in_to_subagents_and_close_stops_the_agent() =
        runScript("unknown-update.jsonl", exitAtEnd = false) { run ->
            run.session.close()
            run.drain()

            val initialize = run.sentByBridge().first { it["method"] == JsonPrimitive("initialize") }
            val capabilities =
                initialize
                    .getValue("params")
                    .jsonObject
                    .getValue("clientCapabilities")
                    .jsonObject
            assertEquals(JsonObject(emptyMap()), capabilities["subagents"])
            assertTrue(
                ProcessHandle
                    .of(run.agentPid())
                    .map { it.isAlive }
                    .orElse(false)
                    .not(),
                "The agent outlived close().",
            )
        }

    @Test
    fun an_update_type_acp_does_not_define_becomes_a_plain_message_and_the_prompt_is_not_echoed() =
        runScript("unknown-update.jsonl") { run ->
            run.session.prompt(MAIN_THREAD_ID, "What is the weather?")
            val events = run.drain()

            // The bridge logs your prompt itself, so an echo here would show it twice.
            assertTrue(events.filterIsInstance<EventPayload.Message>().none { it.role == MessageRole.USER })

            val message =
                events.filterIsInstance<EventPayload.Message>().single { it.role == MessageRole.SYSTEM }
            assertEquals(MAIN_THREAD_ID, message.threadId)
            assertTrue("weather_report" in message.text())
        }

    @Test
    @Timeout(300)
    fun the_turn_end_keeps_its_place_in_the_agents_wire_order() {
        // The answer to session/prompt and the notifications around it take different paths through
        // the SDK. A race between them shows only now and then, so the same run is repeated.
        val runs = 50
        val outOfOrder =
            (1..runs).count {
                var wrong = false
                runScript("turn-end-order.jsonl") { run ->
                    run.session.prompt(MAIN_THREAD_ID, "Start two helpers.")
                    val order = run.drain().mapNotNull(::orderMark)
                    wrong = order != listOf("early done", "turn ended", "late done")
                }
                wrong
            }
        assertEquals(0, outOfOrder, "$outOfOrder of $runs runs reported events out of wire order.")
    }

    private fun runScript(
        fixture: String,
        exitAtEnd: Boolean = true,
        block: suspend CoroutineScope.(AcpRun) -> Unit,
    ) = runFakeAgent(projectPath, fixture, exitAtEnd = exitAtEnd, block = block)
}

/** Names the three events whose order the agent fixed on the wire, and ignores the rest. */
private fun orderMark(event: EventPayload): String? =
    when {
        event is EventPayload.StatusChanged && event.threadId == MAIN_THREAD_ID &&
            event.state == AgentState.WAITING_ON_YOU -> {
            "turn ended"
        }

        event is EventPayload.ThreadUpdated && event.thread.status == AgentState.DONE -> {
            if (event.thread.id == "sess_early") "early done" else "late done"
        }

        else -> {
            null
        }
    }
