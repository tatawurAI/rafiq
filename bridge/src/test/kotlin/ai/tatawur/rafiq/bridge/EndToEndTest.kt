package ai.tatawur.rafiq.bridge

import ai.tatawur.rafiq.bridge.agent.acp.AcpAgentAdapter
import ai.tatawur.rafiq.bridge.agent.acp.fakeAcpAgentCommand
import ai.tatawur.rafiq.bridge.sessions.SessionManager
import ai.tatawur.rafiq.bridge.store.EventLog
import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.AgentThread
import ai.tatawur.rafiq.protocol.ContentBlock
import ai.tatawur.rafiq.protocol.Event
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.MessageRole
import ai.tatawur.rafiq.protocol.Session
import ai.tatawur.rafiq.protocol.ThreadKind
import ai.tatawur.rafiq.protocol.protocolJson
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole bridge with nothing faked but the agent: the SQLite event log, the session manager, the
 * routes on Ktor's test host, and the ACP adapter driving FakeAcpAgent over a recorded run in which
 * the agent sends two helpers to work in the background. The timeout only stops a hung run; nothing
 * here waits on the clock.
 */
@Timeout(60)
class EndToEndTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun a_prompt_with_background_helpers_reaches_a_client_once_and_in_full() =
        testApplication {
            val project = Files.createDirectory(dir.resolve("weather-app"))
            val log = EventLog.open(dir.resolve("data"), Dispatchers.IO.limitedParallelism(1))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val adapter =
                AcpAgentAdapter(
                    agentId = "claude-code",
                    command = fakeAcpAgentCommand("with-subagents.jsonl", dir.resolve("sent-by-bridge.jsonl")),
                    clientVersion = "test",
                )
            val sessions = SessionManager(log, listOf(adapter), scope)
            application { bridgeModule("test", log, sessions, testSecurity()) }
            val http =
                createClient {
                    install(WebSockets)
                    defaultRequest { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") }
                }

            val created =
                http.post("/sessions") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"agent":"claude-code","project_path":"$project"}""")
                }
            assertEquals(HttpStatusCode.Created, created.status)
            val session = protocolJson.decodeFromString(Session.serializer(), created.bodyAsText())
            val prompt = "Launch two helpers in parallel."
            val accepted =
                http.post("/sessions/${session.id}/prompt") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"text":"$prompt"}""")
                }
            assertEquals(HttpStatusCode.Accepted, accepted.status)

            // The fixture ends with the agent exiting, which the bridge reports for the whole session.
            val events = mutableListOf<Event>()
            http.webSocket("/events?session=${session.id}&after=0") {
                do {
                    val frame = incoming.receive() as Frame.Text
                    val event = protocolJson.decodeFromString(Event.serializer(), frame.readText())
                    events += event
                    val payload = event.payload
                } while (!(payload is EventPayload.StatusChanged && payload.threadId == null))
            }
            val payloads = events.map { it.payload }

            assertPromptLoggedOnce(payloads, prompt)
            assertHelperThreads(
                protocolJson.decodeFromString(
                    ListSerializer(AgentThread.serializer()),
                    http.get("/sessions/${session.id}/threads").bodyAsText(),
                ),
            )
            assertLateWorkStreams(payloads)

            sessions.shutdown()
            scope.cancel()
            log.close()
        }

    /** (1) Your prompt is in the log once, logged by the bridge, not echoed by the adapter. */
    private fun assertPromptLoggedOnce(
        payloads: List<EventPayload>,
        prompt: String,
    ) {
        val yours =
            payloads.filterIsInstance<EventPayload.Message>().filter {
                it.role == MessageRole.USER && it.threadId == MAIN_THREAD
            }
        assertEquals(1, yours.size, "Exactly one user message for one prompt: $yours")
        assertTrue(yours.single().id.startsWith("user:"))
        assertEquals(listOf(ContentBlock.Text(prompt)), yours.single().content)
    }

    /** (2) The thread list has the main thread and both helpers, titled as the agent named them. */
    private fun assertHelperThreads(threads: List<AgentThread>) {
        assertEquals(listOf(MAIN_THREAD), threads.filter { it.kind == ThreadKind.MAIN }.map { it.id })
        assertEquals(
            setOf("Summarise README.md", "Find highest forecast temperature"),
            threads.filter { it.kind == ThreadKind.SUBAGENT }.map { it.title }.toSet(),
        )
    }

    /** (3) The turn ends while the helpers still work. What they do afterwards still streams. */
    private fun assertLateWorkStreams(payloads: List<EventPayload>) {
        val turnEnded =
            payloads.indexOfFirst {
                it is EventPayload.StatusChanged &&
                    it.threadId == MAIN_THREAD &&
                    it.state == AgentState.WAITING_ON_YOU
            }
        val helpersDone =
            payloads.withIndex().filter { (_, payload) ->
                payload is EventPayload.ThreadUpdated &&
                    payload.thread.kind == ThreadKind.SUBAGENT &&
                    payload.thread.status == AgentState.DONE
            }
        assertTrue(turnEnded >= 0, "The main turn ends.")
        assertEquals(2, helpersDone.size)
        assertTrue(helpersDone.all { it.index > turnEnded }, "Both helpers finish after the turn ended.")
        val followUp = payloads.indexOfFirst { it is EventPayload.MessageDelta && "finished" in it.text }
        assertTrue(followUp > turnEnded, "The agent's unprompted follow-up streams after the turn.")
    }
}

private const val MAIN_THREAD = "main"
