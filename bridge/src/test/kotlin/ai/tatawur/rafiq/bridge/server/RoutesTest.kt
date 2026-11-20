package ai.tatawur.rafiq.bridge.server

import ai.tatawur.rafiq.bridge.BridgeFixture
import ai.tatawur.rafiq.bridge.TEST_TOKEN
import ai.tatawur.rafiq.bridge.bridgeModule
import ai.tatawur.rafiq.bridge.eventAfter
import ai.tatawur.rafiq.bridge.status
import ai.tatawur.rafiq.bridge.testSecurity
import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.AgentThread
import ai.tatawur.rafiq.protocol.ApprovalOption
import ai.tatawur.rafiq.protocol.ApprovalOptionKind
import ai.tatawur.rafiq.protocol.Event
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.MessageRole
import ai.tatawur.rafiq.protocol.ThreadKind
import ai.tatawur.rafiq.protocol.protocolJson
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Timeout(60)
class RoutesTest {
    @TempDir
    lateinit var dir: Path

    private val opened = mutableListOf<BridgeFixture>()
    private val project: String by lazy { Files.createDirectory(dir.resolve("weather-app")).toString() }

    @AfterEach
    fun closeFixtures() = opened.forEach(BridgeFixture::close)

    private fun ApplicationTestBuilder.start(devPage: Boolean = false): Pair<BridgeFixture, HttpClient> {
        val fixture = BridgeFixture(dir.resolve("data")).also(opened::add)
        application { bridgeModule("test", fixture.log, fixture.sessions, testSecurity(), devPage) }
        val http =
            createClient {
                install(WebSockets)
                defaultRequest { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") }
            }
        return fixture to http
    }

    private suspend fun HttpClient.postJson(
        path: String,
        body: String,
    ) = post(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun parse(text: String) = Json.parseToJsonElement(text)

    private fun JsonElement.code() =
        jsonObject
            .getValue("error")
            .jsonObject
            .getValue("code")
            .jsonPrimitive.content

    private suspend fun HttpResponse.code() = parse(bodyAsText()).code()

    private fun decode(frame: Frame): Event =
        protocolJson.decodeFromString(Event.serializer(), (frame as Frame.Text).readText())

    @Test
    fun reconnecting_with_the_last_seq_replays_the_gap_once_while_the_agent_keeps_emitting() =
        testApplication {
            val (fixture, http) = start()
            val session = fixture.sessions.create("fake", project, null)
            val agent = fixture.adapter.started.single()
            fixture.sessions.prompt(session.id, threadId = null, text = "go")

            val emitter =
                CoroutineScope(Dispatchers.Default).launch {
                    // The prompt has already returned. The reply streams in afterwards and replaces by id.
                    agent.emit(EventPayload.Message("reply", "main", MessageRole.AGENT, isStreaming = true))
                    repeat(60) {
                        agent.emit(EventPayload.MessageDelta("reply", "main", "d$it"))
                        if (it % 5 == 0) yield()
                    }
                    agent.emit(EventPayload.Message("reply", "main", MessageRole.AGENT, isStreaming = false))
                    agent.emit(status(AgentState.DONE, "main", detail = "end"))
                }

            val received = mutableListOf<Event>()
            http.webSocket("/events?session=${session.id}&after=0") {
                while (received.size < 10) received += decode(incoming.receive())
            }
            // The socket is now closed mid-stream. Reconnect from the last seq seen.
            http.webSocket("/events?session=${session.id}&after=${received.last().seq}") {
                do {
                    received += decode(incoming.receive())
                } while ((received.last().payload as? EventPayload.StatusChanged)?.detail != "end")
            }
            emitter.join()

            // No gap, no repeat, and the same bytes as the log.
            assertEquals((1L..received.last().seq).toList(), received.map { it.seq })
            assertEquals(fixture.log.after(session.id, 0, 10_000), received)
        }

    @Test
    fun a_session_is_created_prompted_cancelled_and_approved_over_http() =
        testApplication {
            val (fixture, http) = start()

            val created = http.postJson("/sessions", """{"agent":"fake","project_path":"$project"}""")
            assertEquals(HttpStatusCode.Created, created.status)
            val sessionId =
                parse(created.bodyAsText())
                    .jsonObject
                    .getValue("id")
                    .jsonPrimitive.content
            val agent = fixture.adapter.started.single()

            val listed = parse(http.get("/sessions").bodyAsText()).jsonArray
            assertEquals(
                sessionId,
                listed
                    .single()
                    .jsonObject
                    .getValue("id")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "weather-app",
                listed
                    .single()
                    .jsonObject
                    .getValue("title")
                    .jsonPrimitive.content,
            )
            val threads = parse(http.get("/sessions/$sessionId/threads").bodyAsText()).jsonArray
            assertEquals(
                listOf("main"),
                threads.map {
                    it.jsonObject
                        .getValue("id")
                        .jsonPrimitive.content
                },
            )

            assertEquals(
                HttpStatusCode.Accepted,
                http.postJson("/sessions/$sessionId/prompt", """{"text":"hello"}""").status,
            )
            assertEquals(listOf("main" to "hello"), agent.prompts)
            assertPromptLoggedAsUser(fixture, sessionId)

            assertEquals(
                HttpStatusCode.Accepted,
                http.postJson("/sessions/$sessionId/threads/main/cancel", "{}").status,
            )
            assertEquals(listOf("main"), agent.cancels)

            val options = listOf(ApprovalOption("allow", ApprovalOptionKind.ALLOW_ONCE, "Allow"))
            fixture.log.eventAfter(sessionId, trigger = {
                agent.emit(EventPayload.ApprovalRequest("apr_1", "tool_1", "Run tests", options))
            }) { it.payload is EventPayload.ApprovalRequest }
            assertEquals(HttpStatusCode.Accepted, http.postJson("/approvals/apr_1", """{"option_id":"allow"}""").status)
            assertEquals(listOf("apr_1" to "allow"), agent.approvals)
        }

    @Test
    fun every_failure_has_the_same_error_body() =
        testApplication {
            val (_, http) = start()

            val missingFolder =
                http.postJson(
                    "/sessions",
                    """{"agent":"fake","project_path":"${dir.resolve("nope")}"}""",
                )
            assertEquals(HttpStatusCode.BadRequest, missingFolder.status)
            assertEquals(
                """{"error":{"code":"invalid_project_path",""" +
                    """"message":"project_path must be an absolute path to an existing folder."}}""",
                missingFolder.bodyAsText(),
            )
            val unknownSession = http.get("/sessions/ses_nope/threads")
            assertEquals(HttpStatusCode.NotFound, unknownSession.status)
            assertEquals("session_not_found", unknownSession.code())
            assertEquals("invalid_body", http.postJson("/sessions", "{not json").code())
            assertEquals("invalid_body", http.postJson("/sessions", """{"agent":"fake"}""").code())
            assertEquals("not_found", http.get("/nothing-here").code())
            val plain =
                http.post("/sessions") {
                    contentType(ContentType.Text.Plain)
                    setBody("agent=fake")
                }
            assertEquals(HttpStatusCode.UnsupportedMediaType, plain.status)
            assertEquals("unsupported_media_type", plain.code())
        }

    @Test
    fun a_project_path_must_be_an_absolute_existing_folder_and_a_symlink_to_one_is_resolved() =
        testApplication {
            val (_, http) = start()
            val file = Files.createFile(dir.resolve("notes.txt"))
            val link = Files.createSymbolicLink(dir.resolve("link-to-weather-app"), Path.of(project))

            for (bad in listOf("weather-app", file.toString())) {
                val response = http.postJson("/sessions", """{"agent":"fake","project_path":"$bad"}""")
                assertEquals(HttpStatusCode.BadRequest, response.status, bad)
                assertEquals("invalid_project_path", response.code())
            }
            val viaLink = http.postJson("/sessions", """{"agent":"fake","project_path":"$link"}""")

            assertEquals(HttpStatusCode.Created, viaLink.status)
            assertEquals(
                Path.of(project).toRealPath().toString(),
                parse(viaLink.bodyAsText())
                    .jsonObject
                    .getValue("project_path")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun adapter_refusals_keep_the_error_shape_and_leave_no_phantom_message() =
        testApplication {
            val (fixture, http) = start()
            val sessionId = fixture.sessions.create("fake", project, null).id
            val agent = fixture.adapter.started.single()
            val helper = AgentThread("sub-1", sessionId, "main", ThreadKind.SUBAGENT, "Explore", AgentState.WORKING)
            fixture.log.eventAfter(sessionId, trigger = { agent.emit(EventPayload.ThreadUpdated(helper)) }) {
                it.payload is EventPayload.ThreadUpdated
            }
            val prompt = "/sessions/$sessionId/prompt"

            // A helper thread is refused before anything is logged.
            val loggedBefore = fixture.log.after(sessionId, 0, 1000).size
            val helperPrompt = http.postJson(prompt, """{"thread_id":"sub-1","text":"hi"}""")
            assertEquals(HttpStatusCode.BadRequest, helperPrompt.status)
            assertEquals("invalid_thread", helperPrompt.code())
            assertEquals(loggedBefore, fixture.log.after(sessionId, 0, 1000).size)
            assertTrue(agent.prompts.isEmpty())

            agent.failure = UnsupportedOperationException("grouped helper")
            val cancel = http.postJson("/sessions/$sessionId/threads/main/cancel", "{}")
            assertEquals(HttpStatusCode.Conflict, cancel.status)
            assertEquals("not_supported", cancel.code())

            // A helper the agent no longer knows, as after a resume.
            agent.failure = IllegalArgumentException("Unknown thread sub-1.")
            val forgotten = http.postJson("/sessions/$sessionId/threads/sub-1/cancel", "{}")
            assertEquals(HttpStatusCode.NotFound, forgotten.status)
            assertEquals("thread_not_found", forgotten.code())

            agent.failure = RuntimeException("adapter bug with secret detail")
            val unexpected = http.postJson(prompt, """{"text":"hi"}""")
            assertEquals(HttpStatusCode.InternalServerError, unexpected.status)
            assertEquals("internal_error", unexpected.code())
            assertTrue("secret detail" !in unexpected.bodyAsText())
        }

    @Test
    fun a_second_prompt_during_a_turn_is_refused_and_nothing_is_logged() =
        testApplication {
            val (fixture, http) = start()
            val sessionId = fixture.sessions.create("fake", project, null).id
            val agent = fixture.adapter.started.single()
            val prompt = "/sessions/$sessionId/prompt"

            // The fake reports the thread working, as an adapter does when a turn starts.
            fixture.log.eventAfter(sessionId, trigger = { http.postJson(prompt, """{"text":"first"}""") }) {
                (it.payload as? EventPayload.StatusChanged)?.state == AgentState.WORKING
            }
            val loggedBefore = fixture.log.after(sessionId, 0, 1000).size
            val second = http.postJson(prompt, """{"text":"second"}""")

            assertEquals(HttpStatusCode.Conflict, second.status)
            assertEquals("turn_in_progress", second.code())
            assertEquals(loggedBefore, fixture.log.after(sessionId, 0, 1000).size)
            assertEquals(listOf("main" to "first"), agent.prompts)
        }

    @Test
    fun two_prompts_sent_at_once_give_one_accepted_one_refused_and_one_logged_message() =
        testApplication {
            val (fixture, http) = start()
            val sessionId = fixture.sessions.create("fake", project, null).id
            val prompt = "/sessions/$sessionId/prompt"

            val statuses =
                coroutineScope {
                    val sent = listOf("a", "b").map { text -> async(Dispatchers.Default) { send(http, prompt, text) } }
                    sent.awaitAll()
                }

            assertEquals(listOf(HttpStatusCode.Accepted, HttpStatusCode.Conflict), statuses.sortedBy { it.value })
            val userMessages =
                fixture.log
                    .after(sessionId, 0, 1000)
                    .map { it.payload }
                    .filterIsInstance<EventPayload.Message>()
            assertEquals(1, userMessages.size)
            assertEquals(
                1,
                fixture.adapter.started
                    .single()
                    .prompts.size,
            )
        }

    @Test
    fun approvals_reject_unknown_ids_bad_options_and_answers_the_agent_no_longer_waits_for() =
        testApplication {
            val (fixture, http) = start()
            val sessionId = fixture.sessions.create("fake", project, null).id
            val agent = fixture.adapter.started.single()
            val options = listOf(ApprovalOption("allow", ApprovalOptionKind.ALLOW_ONCE, "Allow"))
            fixture.log.eventAfter(sessionId, trigger = {
                agent.emit(EventPayload.ApprovalRequest("apr_1", "tool_1", "Run tests", options))
            }) { it.payload is EventPayload.ApprovalRequest }

            val unknown = http.postJson("/approvals/apr_nope", """{"option_id":"allow"}""")
            assertEquals(HttpStatusCode.NotFound, unknown.status)
            assertEquals("approval_not_found", unknown.code())
            val badOption = http.postJson("/approvals/apr_1", """{"option_id":"deny-everything"}""")
            assertEquals(HttpStatusCode.BadRequest, badOption.status)
            assertEquals("invalid_option", badOption.code())

            // The agent dropped the request, for example because its thread was cancelled.
            agent.failure = IllegalArgumentException("No pending approval apr_1.")
            val stale = http.postJson("/approvals/apr_1", """{"option_id":"allow"}""")
            assertEquals(HttpStatusCode.Conflict, stale.status)
            assertEquals("approval_gone", stale.code())
            agent.failure = null
            assertEquals(HttpStatusCode.NotFound, http.postJson("/approvals/apr_1", """{"option_id":"allow"}""").status)
        }

    @Test
    fun the_event_socket_closes_a_bad_request_with_a_reason_instead_of_streaming() =
        testApplication {
            val (fixture, http) = start()
            val sessionId = fixture.sessions.create("fake", project, null).id

            for (query in listOf(
                "session=ses_nope",
                "session=",
                "",
                "session=$sessionId&after=-1",
                "session=$sessionId&after=x",
            )) {
                http.webSocket("/events?$query") {
                    val reason = closeReason.await()
                    assertEquals(CloseReason.Codes.CANNOT_ACCEPT.code, reason?.code, query)
                    assertTrue(reason?.message.orEmpty().isNotEmpty(), query)
                }
            }
        }

    @Test
    fun the_dev_page_exists_only_when_the_flag_is_set() {
        testApplication {
            val (_, http) = start()

            assertEquals(HttpStatusCode.NotFound, http.get("/dev").status)
            assertEquals(HttpStatusCode.NotFound, http.get("/dev/agents").status)
        }
        testApplication {
            val (_, http) = start(devPage = true)

            val page = http.get("/dev")

            assertEquals(HttpStatusCode.OK, page.status)
            assertTrue("Rafiq dev page" in page.bodyAsText())
            assertEquals(
                listOf("fake"),
                parse(http.get("/dev/agents").bodyAsText()).jsonArray.map { it.jsonPrimitive.content },
            )
        }
    }

    private suspend fun send(
        http: HttpClient,
        path: String,
        text: String,
    ) = http.postJson(path, """{"text":"$text"}""").status

    private suspend fun assertPromptLoggedAsUser(
        fixture: BridgeFixture,
        sessionId: String,
    ) {
        val logged =
            fixture.log
                .after(sessionId, 0, 100)
                .map { it.payload }
                .filterIsInstance<EventPayload.Message>()
        assertEquals(listOf("main"), logged.map { it.threadId })
        assertEquals(MessageRole.USER, logged.single().role)
        assertTrue(logged.single().id.startsWith("user:"))
    }
}
