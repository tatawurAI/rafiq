package ai.tatawur.rafiq.bridge.server

import ai.tatawur.rafiq.bridge.BridgeFixture
import ai.tatawur.rafiq.bridge.bridgeModule
import ai.tatawur.rafiq.bridge.eventAfter
import ai.tatawur.rafiq.bridge.status
import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.ApprovalOption
import ai.tatawur.rafiq.protocol.ApprovalOptionKind
import ai.tatawur.rafiq.protocol.Event
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.protocolJson
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoutesTest {
    @TempDir
    lateinit var dir: Path

    private val project: String by lazy { Files.createDirectory(dir.resolve("weather-app")).toString() }

    private fun ApplicationTestBuilder.start(
        fixture: BridgeFixture,
        devPage: Boolean = false,
    ) = application { bridgeModule("test", fixture.log, fixture.sessions, devPage) }

    private suspend fun ApplicationTestBuilder.postJson(
        path: String,
        body: String,
    ) = client.post(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun parse(text: String) = Json.parseToJsonElement(text)

    @Test
    fun reconnecting_with_the_last_seq_replays_the_gap_once_while_the_agent_keeps_emitting() =
        testApplication {
            val fixture = BridgeFixture(dir.resolve("data"))
            start(fixture)
            val sockets = createClient { install(WebSockets) }
            val session = fixture.sessions.create("fake", project, null)
            val agent = fixture.adapter.started.single()
            val total = 600

            val emitter =
                CoroutineScope(Dispatchers.Default).launch {
                    repeat(total) {
                        agent.emit(EventPayload.MessageDelta("m", "main", "d$it"))
                        if (it % 5 == 0) yield()
                    }
                    agent.emit(status(AgentState.DONE, detail = "end"))
                }

            val received = mutableListOf<Event>()
            sockets.webSocket("/events?session=${session.id}&after=0") {
                while (received.size < 40) received += decode(incoming.receive())
            }
            // The socket is now closed mid-stream. Reconnect from the last seq seen.
            sockets.webSocket("/events?session=${session.id}&after=${received.last().seq}") {
                do {
                    received += decode(incoming.receive())
                } while ((received.last().payload as? EventPayload.StatusChanged)?.detail != "end")
            }
            emitter.join()

            // Two initial events, the deltas, and the end marker: no gap, no repeat, same bytes as the log.
            assertEquals((1L..(total + 3).toLong()).toList(), received.map { it.seq })
            assertEquals(fixture.log.after(session.id, 0, 10_000), received)
            fixture.close()
        }

    @Test
    fun a_session_is_created_prompted_cancelled_and_approved_over_http() =
        testApplication {
            val fixture = BridgeFixture(dir.resolve("data"))
            start(fixture)

            val created = postJson("/sessions", """{"agent":"fake","project_path":"$project"}""")
            assertEquals(HttpStatusCode.Created, created.status)
            val sessionId =
                parse(created.bodyAsText())
                    .jsonObject
                    .getValue("id")
                    .jsonPrimitive.content
            val agent = fixture.adapter.started.single()

            val listed = parse(client.get("/sessions").bodyAsText()).jsonArray
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
            val threads = parse(client.get("/sessions/$sessionId/threads").bodyAsText()).jsonArray
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
                postJson("/sessions/$sessionId/prompt", """{"text":"hello"}""").status,
            )
            assertEquals(listOf("main" to "hello"), agent.prompts)

            assertEquals(HttpStatusCode.Accepted, postJson("/sessions/$sessionId/threads/main/cancel", "{}").status)
            assertEquals(listOf("main"), agent.cancels)

            val options = listOf(ApprovalOption("allow", ApprovalOptionKind.ALLOW_ONCE, "Allow"))
            fixture.log.eventAfter(sessionId, trigger = {
                agent.emit(EventPayload.ApprovalRequest("apr_1", "tool_1", "Run tests", options))
            }) { it.payload is EventPayload.ApprovalRequest }
            assertEquals(HttpStatusCode.Accepted, postJson("/approvals/apr_1", """{"option_id":"allow"}""").status)
            assertEquals(listOf("apr_1" to "allow"), agent.approvals)
            fixture.close()
        }

    @Test
    fun every_failure_has_the_same_error_body() =
        testApplication {
            val fixture = BridgeFixture(dir.resolve("data"))
            start(fixture)

            val missingFolder = postJson("/sessions", """{"agent":"fake","project_path":"${dir.resolve("nope")}"}""")
            assertEquals(HttpStatusCode.BadRequest, missingFolder.status)
            assertEquals(
                """{"error":{"code":"invalid_project_path",""" +
                    """"message":"project_path must be an absolute path to an existing folder."}}""",
                missingFolder.bodyAsText(),
            )
            val unknownSession = client.get("/sessions/ses_nope/threads")
            assertEquals(HttpStatusCode.NotFound, unknownSession.status)
            assertEquals("session_not_found", parse(unknownSession.bodyAsText()).code())
            assertEquals("invalid_body", parse(postJson("/sessions", "{not json").bodyAsText()).code())
            assertEquals("invalid_body", parse(postJson("/sessions", """{"agent":"fake"}""").bodyAsText()).code())
            assertEquals("not_found", parse(client.get("/nothing-here").bodyAsText()).code())
            fixture.close()
        }

    @Test
    fun the_dev_page_does_not_exist_unless_the_flag_is_set() =
        testApplication {
            val fixture = BridgeFixture(dir.resolve("data"))
            start(fixture)

            assertEquals(HttpStatusCode.NotFound, client.get("/dev").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/dev/agents").status)
            fixture.close()
        }

    @Test
    fun the_dev_page_is_served_when_the_flag_is_set() =
        testApplication {
            val fixture = BridgeFixture(dir.resolve("data"))
            start(fixture, devPage = true)

            val page = client.get("/dev")

            assertEquals(HttpStatusCode.OK, page.status)
            assertTrue("Rafiq dev page" in page.bodyAsText())
            assertEquals(
                listOf("fake"),
                parse(client.get("/dev/agents").bodyAsText()).jsonArray.map { it.jsonPrimitive.content },
            )
            fixture.close()
        }

    private fun decode(frame: Frame): Event =
        protocolJson.decodeFromString(Event.serializer(), (frame as Frame.Text).readText())

    private fun kotlinx.serialization.json.JsonElement.code() =
        jsonObject
            .getValue("error")
            .jsonObject
            .getValue("code")
            .jsonPrimitive.content
}
