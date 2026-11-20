package ai.tatawur.rafiq.bridge.agent.acp

import ai.tatawur.rafiq.bridge.agent.AgentSession
import ai.tatawur.rafiq.protocol.ApprovalOptionKind
import ai.tatawur.rafiq.protocol.ContentBlock
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.MessageRole
import ai.tatawur.rafiq.protocol.ThreadKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.readLines
import kotlin.io.path.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
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
                ProcessHandle.current().children().noneMatch { it.isAlive },
                "No agent process outlives close().",
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

    /** The adapter, its session, and everything the session reported so far. */
    private class Run(
        val session: AgentSession,
        private val log: Path,
        scope: CoroutineScope,
    ) {
        private val received = Channel<EventPayload>(Channel.UNLIMITED)
        private val seen = mutableListOf<EventPayload>()

        init {
            scope.launch {
                session.updates.collect { received.send(it) }
                received.close()
            }
        }

        suspend fun awaitEvent(predicate: (EventPayload) -> Boolean): EventPayload {
            while (true) {
                val event = received.receive()
                seen += event
                if (predicate(event)) return event
            }
        }

        /** Everything up to the end of the session, which comes when the agent process exits. */
        suspend fun drain(): List<EventPayload> {
            for (event in received) seen += event
            return seen
        }

        /** Every line the bridge wrote to the agent, read once the agent has exited. */
        fun sentByBridge(): List<JsonObject> = log.readLines().map { Json.parseToJsonElement(it).jsonObject }
    }

    private fun runScript(
        fixture: String,
        exitAtEnd: Boolean = true,
        block: suspend CoroutineScope.(Run) -> Unit,
    ) = runBlocking {
        val log = projectPath.resolve("sent-by-bridge.jsonl")
        val resource = requireNotNull(javaClass.getResource("/acp/$fixture")) { "Missing fixture $fixture" }
        val script = resource.toURI().toPath()
        val ids = AtomicInteger()
        val adapter =
            AcpAgentAdapter(
                agentId = "fake",
                command = fakeAgentCommand(script, log, exitAtEnd),
                clientVersion = "test",
                newId = { "id-${ids.incrementAndGet()}" },
            )
        val session = adapter.start("session-1", projectPath)
        try {
            block(Run(session, log, this))
        } finally {
            session.close()
        }
    }

    private fun fakeAgentCommand(
        script: Path,
        log: Path,
        exitAtEnd: Boolean,
    ): List<String> =
        listOfNotNull(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            // A small, quick-starting JVM: the fake only shuffles a few lines of JSON.
            "-Xmx64m",
            "-XX:TieredStopAtLevel=1",
            "-cp",
            System.getProperty("java.class.path"),
            "ai.tatawur.rafiq.bridge.agent.acp.FakeAcpAgentKt",
            script.toString(),
            log.toString(),
            "exit-at-end".takeIf { exitAtEnd },
        )
}

private fun EventPayload.Message.text(): String =
    content.joinToString("") {
        when (it) {
            is ContentBlock.Text -> it.text
            is ContentBlock.Markdown -> it.markdown
            else -> ""
        }
    }
