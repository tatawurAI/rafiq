package ai.tatawur.rafiq.bridge.server

import ai.tatawur.rafiq.bridge.BridgeFixture
import ai.tatawur.rafiq.bridge.TEST_TOKEN
import ai.tatawur.rafiq.bridge.bridgeModule
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val MISDIRECTED = HttpStatusCode(421, "Misdirected Request")

@Timeout(60)
class SecurityTest {
    @TempDir
    lateinit var dir: Path

    private val opened = mutableListOf<BridgeFixture>()

    @AfterEach
    fun closeFixtures() = opened.forEach(BridgeFixture::close)

    private val security =
        BridgeSecurity(TEST_TOKEN, bindAddress = "100.64.0.1", allowedHosts = listOf("box.example.ts.net"))

    private fun inspect(
        path: String = "/sessions",
        host: String? = "100.64.0.1:8740",
        origin: String? = null,
        authorization: String? = "Bearer $TEST_TOKEN",
        protocols: String? = null,
    ) = security.inspect(path, host, origin, authorization, protocols)

    private fun rejected(verdict: BridgeSecurity.Verdict) = (verdict as BridgeSecurity.Verdict.Rejected).status

    @Test
    fun a_request_needs_the_right_token_unless_it_is_health_or_the_dev_page() {
        assertEquals(BridgeSecurity.Verdict.Accepted(deviceId = "legacy"), inspect())
        assertEquals(HttpStatusCode.Unauthorized, rejected(inspect(authorization = null)))
        assertEquals(HttpStatusCode.Unauthorized, rejected(inspect(authorization = "Bearer ${TEST_TOKEN}0")))
        assertEquals(HttpStatusCode.Unauthorized, rejected(inspect(authorization = TEST_TOKEN)))
        assertEquals(BridgeSecurity.Verdict.Accepted(), inspect(path = "/health", authorization = null))
        assertEquals(BridgeSecurity.Verdict.Accepted(), inspect(path = "/dev", authorization = null))
        // With no token configured nothing protected can be reached, not even with an empty one.
        val noToken = BridgeSecurity(null, "100.64.0.1")
        assertEquals(
            HttpStatusCode.Unauthorized,
            rejected(noToken.inspect("/sessions", "100.64.0.1", null, "Bearer ", null)),
        )
    }

    @Test
    fun only_the_bind_address_localhost_and_listed_names_are_accepted_as_host() {
        for (host in listOf("100.64.0.1", "100.64.0.1:8740", "localhost:8740", "LOCALHOST", "box.example.ts.net:443")) {
            assertEquals("legacy", (inspect(host = host) as BridgeSecurity.Verdict.Accepted).deviceId, host)
        }
        for (host in listOf("evil.example", "evil.example:8740", "100.64.0.2", null)) {
            assertEquals(MISDIRECTED, rejected(inspect(host = host)), host.toString())
        }
    }

    @Test
    fun a_request_with_another_origin_is_refused_even_with_the_token() {
        assertEquals(BridgeSecurity.Verdict.Accepted(deviceId = "legacy"), inspect(origin = "http://100.64.0.1:8740"))
        for (origin in listOf("https://evil.example", "http://localhost:3000", "null")) {
            assertEquals(HttpStatusCode.Forbidden, rejected(inspect(origin = origin)), origin)
        }
    }

    @Test
    fun the_two_sockets_alone_take_the_token_as_a_subprotocol_and_never_from_the_query_string() {
        assertEquals(
            BridgeSecurity.Verdict.Accepted(echoProtocol = "rafiq.v1", deviceId = "legacy"),
            inspect(path = "/events", authorization = null, protocols = "rafiq.v1, $TEST_TOKEN"),
        )
        assertEquals(
            BridgeSecurity.Verdict.Accepted(echoProtocol = "rafiq.term.v1", deviceId = "legacy"),
            inspect(path = "/terminal", authorization = null, protocols = "rafiq.term.v1, $TEST_TOKEN"),
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            rejected(inspect(path = "/sessions", authorization = null, protocols = "rafiq.v1, $TEST_TOKEN")),
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            rejected(inspect(path = "/events", authorization = null, protocols = "rafiq.v1, wrong")),
        )
    }

    @Test
    fun the_checks_run_before_routing_and_a_browser_style_socket_handshake_selects_the_subprotocol() =
        testApplication {
            val fixture = BridgeFixture(dir.resolve("data")).also(opened::add)
            application {
                bridgeModule("test", fixture.log, fixture.sessions, BridgeSecurity(TEST_TOKEN, "127.0.0.1"))
            }
            val http = createClient { install(WebSockets) }

            val unauthorized = http.get("/sessions")
            assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)
            assertTrue(""""code":"unauthorized"""" in unauthorized.bodyAsText())
            assertEquals(
                MISDIRECTED,
                http.get("/health") { header(HttpHeaders.Host, "evil.example") }.status,
            )
            assertEquals(HttpStatusCode.OK, http.get("/health").status)

            // Closed with a reason, not refused: the handshake got through on the subprotocol alone.
            http.webSocket(
                "/events?session=ses_nope",
                request = { header(HttpHeaders.SecWebSocketProtocol, "rafiq.v1, $TEST_TOKEN") },
            ) {
                assertEquals("rafiq.v1", call.response.headers[HttpHeaders.SecWebSocketProtocol])
                assertEquals("no such session", closeReason.await()?.message)
            }
        }
}
