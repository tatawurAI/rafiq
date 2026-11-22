package ai.tatawur.rafiq.bridge.server

import ai.tatawur.rafiq.bridge.BridgeConfig
import ai.tatawur.rafiq.bridge.BridgeFixture
import ai.tatawur.rafiq.bridge.TEST_TOKEN
import ai.tatawur.rafiq.bridge.bridgeModule
import ai.tatawur.rafiq.bridge.seed
import ai.tatawur.rafiq.bridge.testSecurity
import ai.tatawur.rafiq.bridge.tmux.TerminalGate
import ai.tatawur.rafiq.bridge.tmux.TerminalService
import ai.tatawur.rafiq.bridge.tmux.TerminalSettings
import ai.tatawur.rafiq.bridge.tmux.TmuxSessionSource
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val OWNER = "alex@example.com"
private const val LOGIN = TailscaleIdentityCheck.LOGIN_HEADER

/**
 * The bridge on a real port, as `tailscale serve` reaches it: from loopback, with the login header set. The JDK
 * client is used for the sockets because it can send any header on a handshake and report the refused status.
 */
@Timeout(60)
class TailscaleIdentityCheckTest {
    @TempDir
    lateinit var dir: Path

    private val http = HttpClient.newHttpClient()
    private val servers = mutableListOf<EmbeddedServer<*, *>>()
    private val fixtures = mutableListOf<BridgeFixture>()

    @AfterEach
    fun stop() {
        servers.forEach { it.stop(0, 0) }
        fixtures.forEach(BridgeFixture::close)
    }

    /** Starts the bridge with the identity check for [owner], or without it when null. Returns its port. */
    private fun bridge(owner: String?): Int {
        val fixture = BridgeFixture(dir.resolve("data-${fixtures.size}")).also(fixtures::add)
        runBlocking { fixture.log.seed() }
        // Terminal is off, so `/terminal` accepts the handshake and then closes with "no such session". No tmux
        // command runs. That is enough to tell a handshake the identity check let through from one it refused.
        val gate = TerminalGate(TerminalSettings.OFF)
        val terminal = TerminalService(gate, TmuxSessionSource(gate, fixture.log))
        val server =
            embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                bridgeModule("test", fixture.log, fixture.sessions, testSecurity(), terminal = terminal)
                installIdentityCheck(owner)
            }.also { it.start(wait = false) }
        servers += server
        return runBlocking {
            server.engine
                .resolvedConnectors()
                .first()
                .port
        }
    }

    private fun get(
        port: Int,
        path: String = "/sessions",
        token: String? = TEST_TOKEN,
        logins: List<String> = emptyList(),
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
        token?.let { request.header("Authorization", "Bearer $it") }
        logins.forEach { request.header(LOGIN, it) }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    /** Opens a socket and returns the close reason the server sent, or the status that refused the handshake. */
    private fun handshake(
        port: Int,
        path: String,
        protocol: String,
        logins: List<String>,
    ): String {
        val closed = CompletableFuture<String>()
        val listener =
            object : WebSocket.Listener {
                override fun onClose(
                    webSocket: WebSocket,
                    statusCode: Int,
                    reason: String,
                ): CompletionStage<*>? {
                    closed.complete("closed: $reason")
                    return null
                }
            }
        val builder = http.newWebSocketBuilder().subprotocols(protocol, TEST_TOKEN)
        logins.forEach { builder.header(LOGIN, it) }
        val socket =
            try {
                builder.buildAsync(URI("ws://127.0.0.1:$port$path"), listener).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (failure: ExecutionException) {
                val refusal = failure.cause as? WebSocketHandshakeException ?: throw failure
                return "refused: ${refusal.response.statusCode()}"
            }
        // An open socket that the route has nothing to stream on stays open. Opening is all this test needs.
        return closed.completeOnTimeout("open", 2, TimeUnit.SECONDS).get().also { socket.abort() }
    }

    @Test
    fun without_the_setting_a_request_needs_no_identity_header() {
        assertEquals(null, BridgeConfig.fromEnvironment(emptyMap()).tailscaleLogin)
        val port = bridge(owner = null)
        assertEquals(200, get(port).statusCode())
        assertEquals("open", handshake(port, "/events?session=ses_1", "rafiq.v1", emptyList()))
    }

    @Test
    fun with_the_setting_the_right_token_and_login_pass() {
        val port = bridge(OWNER)
        assertEquals(200, get(port, logins = listOf(OWNER)).statusCode())
        assertEquals(200, get(port, logins = listOf("Alex@Example.com")).statusCode())
    }

    @Test
    fun with_the_setting_a_wrong_missing_or_doubled_login_gets_its_own_403() {
        val port = bridge(OWNER)
        for (logins in listOf(listOf("mallory@example.com"), emptyList(), listOf(OWNER, "mallory@example.com"))) {
            val response = get(port, logins = logins)
            assertEquals(403, response.statusCode(), logins.toString())
            assertTrue(response.body().contains(TailscaleIdentityCheck.ERROR_CODE), response.body())
        }
    }

    @Test
    fun the_token_is_still_checked_first_and_health_stays_open() {
        val port = bridge(OWNER)
        assertEquals(401, get(port, token = null, logins = listOf(OWNER)).statusCode())
        assertEquals(200, get(port, path = "/health", token = null).statusCode())
    }

    @Test
    fun both_socket_handshakes_need_the_login() {
        val port = bridge(OWNER)
        val events = "/events?session=ses_1"
        assertEquals("open", handshake(port, events, "rafiq.v1", listOf(OWNER)))
        assertEquals("refused: 403", handshake(port, events, "rafiq.v1", emptyList()))
        assertEquals("refused: 403", handshake(port, events, "rafiq.v1", listOf("mallory@example.com")))

        val terminal = "/terminal?session=tmux-weather-app"
        assertEquals("closed: no such session", handshake(port, terminal, "rafiq.term.v1", listOf(OWNER)))
        assertEquals("refused: 403", handshake(port, terminal, "rafiq.term.v1", emptyList()))
        assertEquals("refused: 403", handshake(port, terminal, "rafiq.term.v1", listOf("mallory@example.com")))
    }

    @Test
    fun a_login_header_from_a_peer_that_is_not_loopback_is_refused() {
        val check = TailscaleIdentityCheck(OWNER)
        assertIs<TailscaleIdentityCheck.Verdict.Accepted>(check.inspect("/sessions", "127.0.0.1", listOf(OWNER)))
        assertIs<TailscaleIdentityCheck.Verdict.Accepted>(check.inspect("/sessions", "0:0:0:0:0:0:0:1", listOf(OWNER)))
        for (peer in listOf("100.64.0.7", "192.168.1.20", "localhost.example.ts.net")) {
            assertIs<TailscaleIdentityCheck.Verdict.Rejected>(check.inspect("/sessions", peer, listOf(OWNER)), peer)
        }
    }

    @Test
    fun the_refusal_log_line_names_the_refused_login_and_nothing_else() {
        val check = TailscaleIdentityCheck(OWNER)
        val forged = "mallory@example.com\r\nAuthorization: Bearer $TEST_TOKEN"
        val rejected =
            assertIs<TailscaleIdentityCheck.Verdict.Rejected>(check.inspect("/sessions", "127.0.0.1", listOf(forged)))
        val line = refusalLogLine("GET", "/sessions", rejected)
        assertTrue(line.contains("login=mallory@example.com"), line)
        assertFalse(line.contains(TEST_TOKEN), line)
        assertFalse(line.contains('\n') || line.contains('\r'), line)
    }

    @Test
    fun the_bridge_will_not_start_with_the_setting_and_a_non_loopback_bind() {
        val env = mapOf("RAFIQ_TAILSCALE_LOGIN" to OWNER)
        assertEquals(OWNER, BridgeConfig.fromEnvironment(env).tailscaleLogin)
        assertEquals(OWNER, BridgeConfig.fromEnvironment(env + ("RAFIQ_BIND_ADDRESS" to "::1")).tailscaleLogin)
        for (bind in listOf("100.64.0.1", "0.0.0.0", "box.example.ts.net")) {
            val failure =
                assertFailsWith<IllegalArgumentException>(bind) {
                    BridgeConfig.fromEnvironment(env + ("RAFIQ_BIND_ADDRESS" to bind))
                }
            assertTrue(failure.message.orEmpty().contains("RAFIQ_TAILSCALE_LOGIN"), failure.message)
        }
        val list =
            assertFailsWith<IllegalArgumentException> {
                BridgeConfig.fromEnvironment(mapOf("RAFIQ_TAILSCALE_LOGIN" to "$OWNER,bob@example.com"))
            }
        assertTrue(list.message.orEmpty().contains("RAFIQ_TAILSCALE_LOGIN"), list.message)
    }

    private companion object {
        const val TIMEOUT_SECONDS = 20L
    }
}
