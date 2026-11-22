package ai.tatawur.rafiq.bridge.tmux

import ai.tatawur.rafiq.bridge.BridgeFixture
import ai.tatawur.rafiq.bridge.FakeAdapter
import ai.tatawur.rafiq.bridge.TEST_TOKEN
import ai.tatawur.rafiq.bridge.bridgeModule
import ai.tatawur.rafiq.bridge.server.InMemoryConnectionRegistry
import ai.tatawur.rafiq.bridge.testSecurity
import ai.tatawur.rafiq.protocol.api.TerminalMessage
import ai.tatawur.rafiq.protocol.api.TerminalResize
import ai.tatawur.rafiq.protocol.api.terminalJson
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** What a test socket received, in order. */
sealed interface Received {
    data class Text(
        val text: String,
    ) : Received

    class Binary(
        val bytes: ByteArray,
    ) : Received

    data class Closed(
        val code: Int,
        val reason: String,
    ) : Received
}

/**
 * The bridge on a real port, with a real tmux behind it, and a plain JDK client to talk to it. The ktor test
 * host runs both ends on one thread, which makes frames and timeouts depend on scheduling.
 */
class RealBridge(
    dir: Path,
    val gate: TerminalGate?,
    agents: AgentDetector = AgentDetector(claudeCommand = "rafiq-no-such-claude"),
    adapter: FakeAdapter = FakeAdapter(),
    deviceOf: (
        io.ktor.server.application.ApplicationCall,
    ) -> String = { it.request.header("X-Test-Device") ?: "phone" },
) : AutoCloseable {
    val fixture: BridgeFixture
    private val server: EmbeddedServer<*, *>
    private val http = HttpClient.newHttpClient()
    private val sockets = mutableListOf<TestSocket>()
    val port: Int
    val tmuxSource: TmuxSessionSource?
    val connections = InMemoryConnectionRegistry()

    init {
        lateinit var created: TmuxSessionSource
        fixture =
            BridgeFixture(dir, adapter) { id -> gate == null || !id.startsWith("tmux-") || created.isVisible(id) }
        tmuxSource = gate?.let { TmuxSessionSource(it, fixture.log, agents).also { source -> created = source } }
        val service = gate?.let { TerminalService(it, tmuxSource!!) }
        server =
            embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                bridgeModule(
                    "test",
                    fixture.log,
                    fixture.sessions,
                    testSecurity(),
                    terminal = service,
                    connections = connections,
                    deviceOf = deviceOf,
                )
            }.also { it.start(wait = false) }
        port =
            runBlocking {
                server.engine
                    .resolvedConnectors()
                    .first()
                    .port
            }
    }

    /** Opens `/terminal?session=tmux-<name>` with the token, as the app does. */
    fun terminal(
        name: String,
        device: String = "phone",
        token: String? = TEST_TOKEN,
        viaSubprotocol: Boolean = false,
    ): TestSocket = socket("/terminal?session=tmux-$name", device, token, viaSubprotocol)

    /** [viaSubprotocol] sends the token as a `Sec-WebSocket-Protocol` value, as the app does, not as a header. */
    fun socket(
        path: String,
        device: String = "phone",
        token: String? = TEST_TOKEN,
        viaSubprotocol: Boolean = false,
    ): TestSocket = TestSocket(http, "ws://127.0.0.1:$port$path", token, device, viaSubprotocol).also(sockets::add)

    fun get(path: String): HttpResponse<String> =
        http.send(
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:$port$path"))
                .header("Authorization", "Bearer $TEST_TOKEN")
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    fun post(
        path: String,
        json: String,
    ): HttpResponse<String> =
        http.send(
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:$port$path"))
                .header("Authorization", "Bearer $TEST_TOKEN")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    override fun close() {
        sockets.forEach(TestSocket::close)
        server.stop(0, 0)
        fixture.close()
    }
}

/** A WebSocket client that queues what it receives, so a test can read frames in order and wait with a limit. */
class TestSocket(
    http: HttpClient,
    url: String,
    token: String?,
    device: String,
    viaSubprotocol: Boolean = false,
) : AutoCloseable {
    private val queue = LinkedBlockingQueue<Received>()
    private val socket: WebSocket

    init {
        val text = StringBuilder()
        val binary = java.io.ByteArrayOutputStream()
        val listener =
            object : WebSocket.Listener {
                override fun onText(
                    webSocket: WebSocket,
                    data: CharSequence,
                    last: Boolean,
                ): CompletionStage<*>? {
                    text.append(data)
                    if (last) queue.add(Received.Text(text.toString()).also { text.setLength(0) })
                    webSocket.request(1)
                    return null
                }

                override fun onBinary(
                    webSocket: WebSocket,
                    data: ByteBuffer,
                    last: Boolean,
                ): CompletionStage<*>? {
                    val bytes = ByteArray(data.remaining()).also(data::get)
                    binary.write(bytes)
                    if (last) queue.add(Received.Binary(binary.toByteArray()).also { binary.reset() })
                    webSocket.request(1)
                    return null
                }

                override fun onClose(
                    webSocket: WebSocket,
                    statusCode: Int,
                    reason: String,
                ): CompletionStage<*>? {
                    queue.add(Received.Closed(statusCode, reason))
                    return null
                }

                override fun onError(
                    webSocket: WebSocket,
                    error: Throwable,
                ) {
                    queue.add(Received.Closed(-1, error.toString()))
                }
            }
        val builder = http.newWebSocketBuilder()
        if (token != null && viaSubprotocol) {
            builder.subprotocols("rafiq.term.v1", token)
        } else if (token != null) {
            builder.header("Authorization", "Bearer $token")
        }
        builder.header("X-Test-Device", device)
        socket = runCatching { builder.buildAsync(URI(url), listener).get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            .getOrElse { failure ->
                // A handshake the server refuses, for example with 401, never becomes a socket.
                val refusal = failure.cause as? java.net.http.WebSocketHandshakeException
                queue.add(
                    Received.Closed(-1, "handshake refused with ${refusal?.response?.statusCode()}: ${failure.cause}"),
                )
                null
            } ?: NoSocket
    }

    /** The subprotocol the server selected in the handshake, or empty. */
    val subprotocol: String get() = socket.subprotocol

    fun sendResize(
        cols: Int,
        rows: Int,
    ) = sendText(terminalJson.encodeToString(TerminalMessage.serializer(), TerminalResize(cols, rows)))

    fun sendText(text: String) {
        socket.sendText(text, true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    fun sendKeys(bytes: ByteArray) {
        socket.sendBinary(ByteBuffer.wrap(bytes), true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    /** The next thing received. Fails the test when nothing arrives in [seconds]. */
    fun next(seconds: Long = TIMEOUT_SECONDS): Received =
        queue.poll(seconds, TimeUnit.SECONDS) ?: throw AssertionError("Nothing arrived within $seconds s.")

    /** Reads until [match] holds for a received item, and returns it. Items before it are dropped. */
    fun until(
        seconds: Long = TIMEOUT_SECONDS,
        match: (Received) -> Boolean,
    ): Received {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (true) {
            val left = TimeUnit.NANOSECONDS.toSeconds(deadline - System.nanoTime()).coerceAtLeast(1)
            val item = next(left)
            if (match(item)) return item
            if (item is Received.Closed) throw AssertionError("The socket closed first: $item")
        }
    }

    override fun close() {
        runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "test done").get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        runCatching { socket.abort() }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 20L
    }
}

/** Stands in for a socket the server refused, so closing it is harmless. */
private object NoSocket : WebSocket {
    override fun sendText(
        data: CharSequence?,
        last: Boolean,
    ) = error("The socket never opened.")

    override fun sendBinary(
        data: ByteBuffer?,
        last: Boolean,
    ) = error("The socket never opened.")

    override fun sendPing(message: ByteBuffer?) = error("The socket never opened.")

    override fun sendPong(message: ByteBuffer?) = error("The socket never opened.")

    override fun sendClose(
        statusCode: Int,
        reason: String?,
    ) = error("The socket never opened.")

    override fun request(n: Long) = Unit

    override fun getSubprotocol(): String = ""

    override fun isOutputClosed() = true

    override fun isInputClosed() = true

    override fun abort() = Unit
}
