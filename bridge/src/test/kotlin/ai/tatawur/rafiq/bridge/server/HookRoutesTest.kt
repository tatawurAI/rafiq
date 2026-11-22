package ai.tatawur.rafiq.bridge.server

import ai.tatawur.rafiq.bridge.BridgeFixture
import ai.tatawur.rafiq.bridge.TEST_TOKEN
import ai.tatawur.rafiq.bridge.bridgeModule
import ai.tatawur.rafiq.bridge.hook.HookSettings
import ai.tatawur.rafiq.bridge.hook.HookSetup
import ai.tatawur.rafiq.bridge.store.DeviceStore
import ai.tatawur.rafiq.bridge.testSecurity
import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.AgentThread
import ai.tatawur.rafiq.protocol.Session
import ai.tatawur.rafiq.protocol.ThreadKind
import ai.tatawur.rafiq.protocol.api.HookSetupPreview
import ai.tatawur.rafiq.protocol.protocolJson
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
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
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

@Timeout(60)
class HookRoutesTest {
    @TempDir
    lateinit var dir: Path

    private val opened = mutableListOf<AutoCloseable>()
    private val lines = CopyOnWriteArrayList<String>()

    @AfterEach
    fun close() = opened.forEach(AutoCloseable::close)

    private suspend fun HttpClient.preview(path: String) =
        protocolJson.decodeFromString(HookSetupPreview.serializer(), get(path).bodyAsText())

    private fun HttpRequestBuilder.hash(value: String) {
        contentType(ContentType.Application.Json)
        setBody("""{"preview_hash":"$value"}""")
    }

    @Test
    fun install_and_remove_each_leave_an_audit_line_with_the_device_project_and_hash() =
        testApplication {
            val fixture = BridgeFixture(dir.resolve("data"), visibility = { true }).also(opened::add)
            val store = DeviceStore("jdbc:sqlite::memory:", Dispatchers.IO.limitedParallelism(1)).also(opened::add)
            val project = Files.createDirectory(dir.resolve("weather-app")).toRealPath()
            val session =
                Session("tmux-weather-app", "claude-code", project.toString(), "weather-app", AgentState.DONE, 1, 1)
            fixture.log.createSession(
                session,
                "ref",
                AgentThread("main", session.id, null, ThreadKind.MAIN, "weather-app", AgentState.DONE),
            )
            val hooks =
                HookSetup(HookSettings("/opt/rafiq-bridge/hooks/rafiq-hook.py", "/var/lib/rafiq"), store, dir)
            application {
                bridgeModule(
                    "test",
                    fixture.log,
                    fixture.sessions,
                    testSecurity(),
                    hooks = hooks,
                    audit = LoggingAuditLog(lines::add),
                    deviceOf = { "dev_phone1" },
                )
            }
            val http = createClient { defaultRequest { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") } }
            val path = "/sessions/tmux-weather-app/hooks"

            val install = http.preview(path)
            assertEquals(HttpStatusCode.OK, http.post(path) { hash(install.previewHash) }.status)
            val written = Files.readString(project.resolve(".claude/settings.local.json"))
            assertEquals(HttpStatusCode.OK, http.delete(path) { hash(http.preview(path).previewHash) }.status)

            assertEquals(
                listOf(
                    "Hooks installed: device=dev_phone1 project=$project sha256=${LoggingAuditLog.sha256Hex(written)}",
                    "Hooks removed: device=dev_phone1 project=$project sha256=deleted",
                ),
                lines,
            )
        }
}
