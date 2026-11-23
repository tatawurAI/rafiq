package ai.tatawur.rafiq.bridge.server

import ai.tatawur.rafiq.bridge.BridgeFixture
import ai.tatawur.rafiq.bridge.TEST_TOKEN
import ai.tatawur.rafiq.bridge.bridgeModule
import ai.tatawur.rafiq.bridge.eventAfter
import ai.tatawur.rafiq.bridge.testSecurity
import ai.tatawur.rafiq.protocol.ApprovalOption
import ai.tatawur.rafiq.protocol.ApprovalOptionKind
import ai.tatawur.rafiq.protocol.EventPayload
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@Timeout(60)
class AuditTest {
    @TempDir
    lateinit var dir: Path

    private val opened = mutableListOf<BridgeFixture>()
    private val lines = CopyOnWriteArrayList<String>()

    @AfterEach
    fun closeFixtures() = opened.forEach(BridgeFixture::close)

    private suspend fun HttpClient.postJson(
        path: String,
        body: String,
    ) = post(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    @Test
    fun a_prompt_and_an_approval_answer_each_leave_one_line_with_the_device_and_no_text() =
        testApplication {
            val fixture = BridgeFixture(dir.resolve("data")).also(opened::add)
            application {
                bridgeModule(
                    "test",
                    fixture.log,
                    fixture.sessions,
                    testSecurity(),
                    audit = LoggingAuditLog(lines::add),
                    deviceOf = { "dev_phone1" },
                )
            }
            val http = createClient { defaultRequest { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") } }
            val project = Files.createDirectory(dir.resolve("weather-app"))
            val created = http.postJson("/sessions", """{"agent":"fake","project_path":"$project"}""")
            val sessionId =
                Json
                    .parseToJsonElement(created.bodyAsText())
                    .jsonObject
                    .getValue("id")
                    .jsonPrimitive.content
            val secret = "deploy with the password hunter2"

            val prompted = http.postJson("/sessions/$sessionId/prompt", """{"text":"$secret","client_id":"c-1"}""")
            val agent = fixture.adapter.started.single()
            val options = listOf(ApprovalOption("allow", ApprovalOptionKind.ALLOW_ONCE, "Allow"))
            fixture.log.eventAfter(sessionId, trigger = {
                agent.emit(EventPayload.ApprovalRequest("apr_1", "tool_1", "Run tests", options))
            }) { it.payload is EventPayload.ApprovalRequest }
            val answered = http.postJson("/approvals/apr_1", """{"option_id":"allow"}""")

            assertEquals(HttpStatusCode.Accepted, prompted.status)
            assertEquals(HttpStatusCode.Accepted, answered.status)
            assertEquals(
                listOf(
                    "Chat prompt: device=dev_phone1 session=$sessionId sha256=${LoggingAuditLog.sha256Hex(secret)}",
                    "Approval answered: device=dev_phone1 session=$sessionId approval=apr_1 option=allow",
                ),
                lines,
            )
            assertFalse(lines.any { "hunter2" in it || "password" in it })
        }

    @Test
    fun a_refused_prompt_leaves_a_line_with_its_code_and_an_oversized_one_is_turned_away() =
        testApplication {
            val fixture = BridgeFixture(dir.resolve("data")).also(opened::add)
            application {
                bridgeModule(
                    "test",
                    fixture.log,
                    fixture.sessions,
                    testSecurity(),
                    audit = LoggingAuditLog(lines::add),
                    deviceOf = { "dev_phone1" },
                )
            }
            val http = createClient { defaultRequest { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") } }
            val project = Files.createDirectory(dir.resolve("weather-app"))
            val created = http.postJson("/sessions", """{"agent":"fake","project_path":"$project"}""")
            val sessionId =
                Json
                    .parseToJsonElement(created.bodyAsText())
                    .jsonObject
                    .getValue("id")
                    .jsonPrimitive.content

            val refused = http.postJson("/sessions/$sessionId/prompt", """{"text":"hi","thread_id":"no-such-thread"}""")
            val huge =
                http.postJson(
                    "/sessions/$sessionId/prompt",
                    """{"text":"${"x".repeat(MAX_PROMPT_BYTES + 1)}"}""",
                )
            val longId =
                http.postJson(
                    "/sessions/$sessionId/prompt",
                    """{"text":"hi","client_id":"${"c".repeat(65)}"}""",
                )

            assertEquals(HttpStatusCode.NotFound, refused.status)
            assertEquals(HttpStatusCode.PayloadTooLarge, huge.status)
            assertEquals(HttpStatusCode.BadRequest, longId.status)
            assertEquals(
                listOf(
                    "Chat prompt refused: device=dev_phone1 session=$sessionId code=thread_not_found " +
                        "sha256=${LoggingAuditLog.sha256Hex("hi")}",
                ),
                lines,
            )
        }

    @Test
    fun a_field_cannot_add_a_line_or_fake_another_field() {
        val audit = LoggingAuditLog(lines::add)

        audit.approvalAnswer("dev_1", "ses_1", "apr_1\ndevice=evil", "allow x=1")

        assertEquals(
            listOf("Approval answered: device=dev_1 session=ses_1 approval=apr_1?device?evil option=allow?x?1"),
            lines,
        )
    }
}
