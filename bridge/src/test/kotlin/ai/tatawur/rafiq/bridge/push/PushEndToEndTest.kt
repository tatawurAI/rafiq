package ai.tatawur.rafiq.bridge.push

import ai.tatawur.rafiq.bridge.TEST_SESSION
import ai.tatawur.rafiq.bridge.seed
import ai.tatawur.rafiq.bridge.server.DeviceFixture
import ai.tatawur.rafiq.bridge.store.EventLog
import ai.tatawur.rafiq.protocol.ApprovalOption
import ai.tatawur.rafiq.protocol.ApprovalOptionKind
import ai.tatawur.rafiq.protocol.EventPayload
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.nio.file.Path
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * The whole push path over real HTTPS: a paired phone registers, an approval is logged, and a local push service
 * receives the request. It decrypts the body with the phone's private key, as the phone would.
 */
@Timeout(60)
class PushEndToEndTest {
    @TempDir
    lateinit var dir: Path

    private class Request(
        val path: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private val requests = Channel<Request>(Channel.UNLIMITED)

    /** The status the fake push service answers with, per path. Anything not listed gets 201. */
    private val answers = HashMap<String, Int>()
    private val hits = AtomicInteger()
    private lateinit var server: HttpsServer
    private lateinit var client: HttpClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val devices = DeviceFixture()
    private val log = EventLog.open(null, Dispatchers.IO.limitedParallelism(1))

    @AfterEach
    fun stop() {
        scope.cancel()
        if (::server.isInitialized) server.stop(0)
        log.close()
        devices.close()
    }

    /** A local push service with a throwaway self-signed certificate, and a client that trusts only that one. */
    private fun startPushService(): String {
        val password = ByteArray(18).also(SecureRandom()::nextBytes).let(::b64)
        val store = dir.resolve("push-service.p12")
        val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
        val process =
            ProcessBuilder(
                keytool,
                "-genkeypair",
                "-alias",
                "push",
                "-keyalg",
                "EC",
                "-groupname",
                "secp256r1",
                "-dname",
                "CN=push-service-test",
                "-ext",
                "san=ip:127.0.0.1",
                "-validity",
                "1",
                "-storetype",
                "PKCS12",
                "-keystore",
                store.toString(),
                "-storepass",
                password,
                "-keypass",
                password,
            ).redirectErrorStream(true).start()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) {
            process.inputStream.readAllBytes().decodeToString()
        }
        val keys =
            KeyStore.getInstance("PKCS12").apply {
                store.toFile().inputStream().use { load(it, password.toCharArray()) }
            }
        val keyManagers = KeyManagerFactory.getInstance("PKIX").apply { init(keys, password.toCharArray()) }.keyManagers
        val trust = TrustManagerFactory.getInstance("PKIX").apply { init(keys) }.trustManagers
        val serverTls = SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }
        val clientTls = SSLContext.getInstance("TLS").apply { init(null, trust, null) }

        server = HttpsServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.httpsConfigurator = HttpsConfigurator(serverTls)
        server.createContext("/") { exchange -> answer(exchange) }
        server.start()
        client = HttpPushSender.defaultClient(clientTls)
        return "https://127.0.0.1:${server.address.port}"
    }

    private fun answer(exchange: HttpExchange) {
        hits.incrementAndGet()
        val path = exchange.requestURI.path
        val headers = exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") }
        requests.trySend(Request(path, headers, exchange.requestBody.readAllBytes()))
        val status = answers[path] ?: 201
        if (status in 300..399) exchange.responseHeaders.add("Location", "/up/elsewhere")
        exchange.sendResponseHeaders(status, -1)
        exchange.close()
    }

    @Test
    fun a_logged_approval_reaches_the_push_service_encrypted_with_ids_and_a_kind_only() =
        runBlocking {
            val base = startPushService()
            val (deviceId, _) = devices.pair()
            val phone = TestPhoneKeys()
            StorePushRegistry(devices.store).register(deviceId, phone.registration("$base/up/phone"))
            log.seed()
            PushNotifier(
                log::subscribeAll,
                log::threads,
                devices.store,
                { true },
                HttpPushSender(client),
                scope,
            ).start()

            val command = "rm -rf /work/weather-app/build"
            log.append(
                TEST_SESSION.id,
                EventPayload.ApprovalRequest(
                    id = "apr_1",
                    toolCallId = "tool_1",
                    description = command,
                    options = listOf(ApprovalOption("allow", ApprovalOptionKind.ALLOW_ONCE, "Allow")),
                ),
            )
            val request = withTimeout(20.seconds) { requests.receive() }

            assertEquals("/up/phone", request.path)
            assertEquals("aes128gcm", request.headers["content-encoding"])
            assertEquals("high", request.headers["urgency"])
            assertEquals("3600", request.headers["ttl"])
            assertFalse(String(request.body, Charsets.ISO_8859_1).contains("apr_1"), "the body must be encrypted")
            val plain = phone.decrypt(request.body).decodeToString()
            assertFalse(plain.contains(command))
            assertEquals(
                JsonObject(
                    mapOf(
                        "v" to JsonPrimitive(1),
                        "kind" to JsonPrimitive("approval"),
                        "session_id" to JsonPrimitive(TEST_SESSION.id),
                        "approval_id" to JsonPrimitive("apr_1"),
                    ),
                ),
                Json.parseToJsonElement(plain),
            )

            // The push service forgets the phone. The next push deletes the registration.
            answers["/up/phone"] = 410
            log.append(TEST_SESSION.id, EventPayload.ApprovalResolved("apr_1"))
            withTimeout(20.seconds) { requests.receive() }
            withTimeout(20.seconds) { while (devices.store.push(deviceId) != null) delay(20) }
            assertNull(devices.store.push(deviceId))
        }

    @Test
    fun the_sender_does_not_follow_a_redirect() =
        runBlocking {
            val base = startPushService()
            answers["/up/moved"] = 307

            val outcome = HttpPushSender(client).send("$base/up/moved", ByteArray(10), 60, PushUrgency.NORMAL)

            assertEquals(SendOutcome.Failed("status 307"), outcome)
            assertEquals(1, hits.get())
            assertEquals(
                listOf("/up/moved"),
                generateSequence { requests.tryReceive().getOrNull() }.map { it.path }.toList(),
            )
        }

    @Test
    fun the_sender_never_contacts_a_plain_http_endpoint() =
        runBlocking {
            startPushService()
            val port = server.address.port

            val outcome =
                HttpPushSender(
                    client,
                ).send("http://127.0.0.1:$port/up/phone", ByteArray(10), 60, PushUrgency.NORMAL)

            assertIs<SendOutcome.Failed>(outcome)
            assertEquals(0, hits.get())
        }

    @Test
    fun a_failed_send_reports_why_without_the_endpoint() =
        runBlocking {
            val port = startPushService().substringAfterLast(':')
            // The certificate names 127.0.0.1 only, so the handshake fails, and the JDK's message names the host.
            val endpoint = "https://localhost:$port/up/secret-capability-path"

            val outcome = HttpPushSender(client).send(endpoint, ByteArray(10), 60, PushUrgency.NORMAL)

            val reason = assertIs<SendOutcome.Retry>(outcome).reason
            assertFalse(reason.contains("localhost") || reason.contains("secret-capability-path"), reason)
        }
}
