package ai.tatawur.rafiq.bridge.server

import ai.tatawur.rafiq.protocol.api.PairRequest
import ai.tatawur.rafiq.protocol.api.PairResponse
import ai.tatawur.rafiq.protocol.protocolJson
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
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
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import io.ktor.websocket.readReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import io.ktor.server.websocket.WebSockets as ServerWebSockets

@Timeout(60)
class DeviceRoutesTest {
    private val devices = DeviceFixture()

    @AfterEach
    fun close() = devices.close()

    /**
     * The real security plugin and `/pair` route, with stand-ins for what the bridge routes do: `/whoami`
     * reports the principal, and `/events` registers its socket in the registry as the real route does.
     */
    private fun ApplicationTestBuilder.module() {
        application {
            install(ContentNegotiation) { json(protocolJson) }
            install(ServerWebSockets)
            installSecurity(BridgeSecurity("127.0.0.1", emptyList(), devices.service))
            routing {
                deviceRoutes(devices.service)
                get("/whoami") { call.respondText(call.attributes[DevicePrincipalKey].deviceId) }
                webSocket("/events") {
                    val device = call.attributes[DevicePrincipalKey].deviceId
                    val gone = CompletableDeferred<String>()
                    devices.registry.register(device, "events") { reason -> gone.complete(reason) }.use {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, gone.await()))
                    }
                }
            }
        }
    }

    @Test
    fun pairing_gives_a_token_that_reaches_the_routes_and_a_revoked_one_gets_401_device_revoked() =
        testApplication {
            module()
            val http = createClient { install(WebSockets) }
            val code = devices.service.issueCode("phone").code

            val paired = http.post("/pair") { jsonBody(PairRequest(code)) }
            assertEquals(HttpStatusCode.Created, paired.status)
            assertEquals("no-store", paired.headers[HttpHeaders.CacheControl])
            val response = protocolJson.decodeFromString(PairResponse.serializer(), paired.bodyAsText())
            assertTrue(response.token.startsWith("rfqd_"))

            assertEquals(HttpStatusCode.Unauthorized, http.get("/whoami").status)
            val me = http.get("/whoami") { header(HttpHeaders.Authorization, "Bearer ${response.token}") }
            assertEquals(response.deviceId, me.bodyAsText())

            // An open socket, then revoke: the socket closes with the reason, at once.
            http.webSocket(
                "/events",
                request = { header(HttpHeaders.SecWebSocketProtocol, "rafiq.v1, ${response.token}") },
            ) {
                assertEquals("rafiq.v1", call.response.headers[HttpHeaders.SecWebSocketProtocol])
                devices.service.revoke(response.deviceId)
                assertEquals("device revoked", closeReason.await()?.message)
            }

            // The token has not expired. The next request and the next handshake are refused.
            val refused = http.get("/whoami") { header(HttpHeaders.Authorization, "Bearer ${response.token}") }
            assertEquals(HttpStatusCode.Unauthorized, refused.status)
            assertTrue(""""code":"device_revoked"""" in refused.bodyAsText())
            val handshake =
                http.get(
                    "/events",
                ) { header(HttpHeaders.SecWebSocketProtocol, "rafiq.v1, ${response.token}") }
            assertEquals(HttpStatusCode.Unauthorized, handshake.status)
            assertTrue(""""code":"device_revoked"""" in handshake.bodyAsText())
        }

    @Test
    fun deleting_me_revokes_only_the_caller_and_needs_a_valid_token() =
        testApplication {
            module()
            val http = createClient { }
            val (mine, myToken) = devices.pair("phone")
            val (other, otherToken) = devices.pair("tablet")
            val closed = mutableListOf<String>()
            devices.registry.register(mine, "events") { closed += "mine" }
            devices.registry.register(other, "events") { closed += "other" }

            assertEquals(HttpStatusCode.Unauthorized, http.delete("/devices/me").status)
            assertEquals(
                HttpStatusCode.Unauthorized,
                http.delete("/devices/me") { header(HttpHeaders.Authorization, "Bearer ${myToken}x") }.status,
            )
            assertEquals(DeviceCheck.Active(mine), devices.service.verify(myToken))

            val done = http.delete("/devices/me") { header(HttpHeaders.Authorization, "Bearer $myToken") }
            assertEquals(HttpStatusCode.NoContent, done.status)

            assertEquals(DeviceCheck.Revoked, devices.service.verify(myToken))
            assertEquals(DeviceCheck.Active(other), devices.service.verify(otherToken))
            assertEquals(listOf("mine"), closed)
            val again = http.delete("/devices/me") { header(HttpHeaders.Authorization, "Bearer $myToken") }
            assertEquals(HttpStatusCode.Unauthorized, again.status)
        }

    @Test
    fun a_wrong_code_is_401_and_repeated_wrong_codes_are_429_with_retry_after() =
        testApplication {
            module()
            val http = createClient { }
            val wrong = http.post("/pair") { jsonBody(PairRequest("AAAA-AAAA")) }
            assertEquals(HttpStatusCode.Unauthorized, wrong.status)
            assertTrue(""""code":"invalid_pairing_code"""" in wrong.bodyAsText())

            repeat(4) { http.post("/pair") { jsonBody(PairRequest("AAAA-AAAA")) } }
            val locked = http.post("/pair") { jsonBody(PairRequest("AAAA-AAAA")) }
            assertEquals(HttpStatusCode.TooManyRequests, locked.status)
            assertNotNull(locked.headers[HttpHeaders.RetryAfter]?.toLongOrNull())
        }

    @Test
    fun pairing_still_refuses_a_foreign_host_and_a_foreign_origin() =
        testApplication {
            module()
            val http = createClient { }
            val code = devices.service.issueCode("phone").code
            val foreignHost =
                http.post("/pair") {
                    header(HttpHeaders.Host, "evil.example")
                    jsonBody(PairRequest(code))
                }
            assertEquals(421, foreignHost.status.value)
            val foreignOrigin =
                http.post("/pair") {
                    header(HttpHeaders.Origin, "https://evil.example")
                    jsonBody(PairRequest(code))
                }
            assertEquals(HttpStatusCode.Forbidden, foreignOrigin.status)
            // Neither attempt spent the code.
            assertEquals(HttpStatusCode.Created, http.post("/pair") { jsonBody(PairRequest(code)) }.status)
        }

    private fun io.ktor.client.request.HttpRequestBuilder.jsonBody(body: PairRequest) {
        contentType(ContentType.Application.Json)
        setBody(protocolJson.encodeToString(PairRequest.serializer(), body))
    }
}
