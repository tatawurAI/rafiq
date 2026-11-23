package ai.tatawur.rafiq.bridge.server

import ai.tatawur.rafiq.bridge.BridgeFixture
import ai.tatawur.rafiq.bridge.FakeAdapter
import ai.tatawur.rafiq.bridge.TEST_TOKEN
import ai.tatawur.rafiq.bridge.bridgeModule
import ai.tatawur.rafiq.bridge.claudeLikeModes
import ai.tatawur.rafiq.bridge.projects.FolderCatalog
import ai.tatawur.rafiq.bridge.testSecurity
import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.AgentThread
import ai.tatawur.rafiq.protocol.Session
import ai.tatawur.rafiq.protocol.ThreadKind
import ai.tatawur.rafiq.protocol.api.ApiError
import ai.tatawur.rafiq.protocol.api.ProjectFolderListing
import ai.tatawur.rafiq.protocol.protocolJson
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** `POST /sessions` with `mode_id`, `POST /sessions/{id}/mode` and `GET /projects/folders` over HTTP. */
@Timeout(60)
class ModeAndProjectRoutesTest {
    @TempDir
    lateinit var dir: Path

    private val opened = mutableListOf<BridgeFixture>()
    private val root: Path by lazy { Files.createDirectories(dir.resolve("code")) }
    private val project: Path by lazy { Files.createDirectories(root.resolve("weather-app")) }

    @AfterEach
    fun closeFixtures() = opened.forEach(BridgeFixture::close)

    private fun ApplicationTestBuilder.start(): Pair<BridgeFixture, HttpClient> {
        val fixture =
            BridgeFixture(
                dir.resolve("data"),
                FakeAdapter(modes = claudeLikeModes()),
                visibility = { true },
                defaultModes = mapOf("fake" to "default"),
            ).also(opened::add)
        val projects = FolderCatalog(listOf(root), recentPaths = { emptyList() })
        application { bridgeModule("test", fixture.log, fixture.sessions, testSecurity(), projects = projects) }
        val http = createClient { defaultRequest { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") } }
        return fixture to http
    }

    private suspend fun HttpClient.postJson(
        path: String,
        body: String,
    ) = post(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpResponse.errorCode(): String =
        protocolJson.decodeFromString(ApiError.serializer(), bodyAsText()).error.code

    @Test
    fun a_session_starts_in_the_mode_asked_for_and_switches_on_request() =
        testApplication {
            val (fixture, http) = start()

            val created = http.postJson("/sessions", """{"agent":"fake","project_path":"$project","mode_id":"plan"}""")
            assertEquals(HttpStatusCode.Created, created.status)
            val session = protocolJson.decodeFromString(Session.serializer(), created.bodyAsText())
            assertEquals("plan", session.permissionMode)

            val switched = http.postJson("/sessions/${session.id}/mode", """{"mode_id":"default"}""")
            assertEquals(HttpStatusCode.Accepted, switched.status)
            assertEquals(
                "default",
                fixture.sessions
                    .sessions()
                    .single()
                    .permissionMode,
            )

            val unknown = http.postJson("/sessions/${session.id}/mode", """{"mode_id":"yolo"}""")
            assertEquals(HttpStatusCode.BadRequest, unknown.status)
            assertEquals("unknown_mode", unknown.errorCode())
        }

    @Test
    fun a_tmux_session_answers_409_mode_not_supported() =
        testApplication {
            val (fixture, http) = start()
            val id = "tmux-weather-app"
            fixture.log.createSession(
                Session(id, "claude-code", project.toString(), "weather-app", AgentState.DONE, 1, 1),
                "tmux",
                AgentThread("main", id, null, ThreadKind.MAIN, "weather-app", AgentState.DONE),
            )

            val refused = http.postJson("/sessions/$id/mode", """{"mode_id":"default"}""")

            assertEquals(HttpStatusCode.Conflict, refused.status)
            assertEquals("mode_not_supported", refused.errorCode())
        }

    @Test
    fun folders_are_listed_under_a_root_and_refused_outside_one() =
        testApplication {
            val (_, http) = start()
            project

            val listed = http.get("/projects/folders") { parameter("path", root.toString()) }
            assertEquals(HttpStatusCode.OK, listed.status)
            val listing = protocolJson.decodeFromString(ProjectFolderListing.serializer(), listed.bodyAsText())
            assertEquals(listOf("weather-app"), listing.folders)

            val outside = http.get("/projects/folders") { parameter("path", "$root/../data") }
            assertEquals(HttpStatusCode.Forbidden, outside.status)
            assertEquals("outside_project_roots", outside.errorCode())

            val missing = http.get("/projects/folders") { parameter("path", "$root/docs-site") }
            assertEquals(HttpStatusCode.NotFound, missing.status)
            assertEquals("not_a_folder", missing.errorCode())
        }

    @Test
    fun the_project_routes_need_the_device_token() =
        testApplication {
            start()
            val anonymous = createClient {}

            assertEquals(HttpStatusCode.Unauthorized, anonymous.get("/projects").status)
        }
}
