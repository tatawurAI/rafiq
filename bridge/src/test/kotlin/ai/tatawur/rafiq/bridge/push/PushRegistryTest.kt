package ai.tatawur.rafiq.bridge.push

import ai.tatawur.rafiq.bridge.server.BridgeSecurity
import ai.tatawur.rafiq.bridge.server.DeviceFixture
import ai.tatawur.rafiq.bridge.server.DevicePrincipalKey
import ai.tatawur.rafiq.bridge.server.deviceRoutes
import ai.tatawur.rafiq.bridge.server.installErrorShape
import ai.tatawur.rafiq.bridge.server.installSecurity
import ai.tatawur.rafiq.bridge.server.pushRoutes
import ai.tatawur.rafiq.bridge.sessions.SessionException
import ai.tatawur.rafiq.protocol.api.PushRegistration
import ai.tatawur.rafiq.protocol.protocolJson
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Timeout(60)
class PushRegistryTest {
    private val devices = DeviceFixture()
    private val registry = StorePushRegistry(devices.store, devices.clock)
    private val phone = TestPhoneKeys()

    @AfterEach
    fun close() = devices.close()

    @Test
    fun only_an_https_endpoint_with_a_host_and_no_user_info_is_accepted() {
        val refused =
            listOf(
                "http://push.example.ts.net/up/abc",
                "https://user:secret@push.example.ts.net/up/abc",
                "https:///up/abc",
                "file:///etc/passwd",
                "not a url",
                "https://push.example.ts.net/" + "a".repeat(StorePushRegistry.MAX_ENDPOINT_LENGTH),
            )
        for (endpoint in refused) {
            val failure =
                assertFailsWith<SessionException.Invalid>(
                    endpoint,
                ) { StorePushRegistry.validate(phone.registration(endpoint)) }
            assertEquals(StorePushRegistry.INVALID_CODE, failure.code)
            assertFalse(failure.message.orEmpty().contains(endpoint), "The error must not echo the endpoint.")
        }
        assertNotNull(StorePushRegistry.validate(phone.registration("https://push.example.ts.net/up/abc")))
    }

    @Test
    fun keys_must_be_a_p256_point_and_a_16_byte_secret() {
        val endpoint = "https://push.example.ts.net/up/abc"
        val good = phone.registration(endpoint)
        val offCurve = phone.publicBytes.copyOf().also { it[64] = (it[64].toInt() xor 1).toByte() }
        val refused =
            listOf(
                good.copy(p256dh = b64(phone.publicBytes.copyOf(64))),
                good.copy(p256dh = b64(offCurve)),
                good.copy(p256dh = "not base64!"),
                good.copy(auth = b64(ByteArray(15))),
                good.copy(auth = b64(ByteArray(32))),
            )
        for (registration in refused) {
            assertFailsWith<SessionException.Invalid> { StorePushRegistry.validate(registration) }
        }
        val stored = StorePushRegistry.validate(good)
        assertContentEquals(phone.publicBytes, stored.p256dh)
        assertContentEquals(phone.auth, stored.auth)
    }

    @Test
    fun revoking_a_device_deletes_its_registration_and_a_late_one_is_not_stored() =
        runBlocking {
            val (id, _) = devices.pair()
            val (otherId, _) = devices.pair("tablet")
            registry.register(id, phone.registration("https://push.example.ts.net/up/phone"))
            registry.register(otherId, phone.registration("https://push.example.ts.net/up/tablet"))

            devices.service.revoke(id)

            assertNull(devices.store.push(id))
            assertEquals(listOf(otherId), devices.store.pushDevices())
            // A register that raced the revoke finds the device revoked and stores nothing.
            registry.register(id, phone.registration("https://push.example.ts.net/up/phone"))
            assertNull(devices.store.push(id))
        }

    @Test
    fun a_gone_answer_for_an_old_endpoint_keeps_the_registration_that_replaced_it() =
        runBlocking {
            val (id, _) = devices.pair()
            registry.register(id, phone.registration("https://push.example.ts.net/up/old"))
            registry.register(id, phone.registration("https://push.example.ts.net/up/new"))

            devices.store.deletePush(id, "https://push.example.ts.net/up/old")
            assertEquals("https://push.example.ts.net/up/new", devices.store.push(id)?.endpoint)

            devices.store.deletePush(id, "https://push.example.ts.net/up/new")
            assertNull(devices.store.push(id))
        }

    private fun ApplicationTestBuilder.module() {
        application {
            install(ContentNegotiation) { json(protocolJson) }
            installErrorShape()
            installSecurity(BridgeSecurity("127.0.0.1", emptyList(), devices.service))
            routing {
                deviceRoutes(devices.service)
                pushRoutes(registry) { it.attributes[DevicePrincipalKey].deviceId }
            }
        }
    }

    @Test
    fun the_routes_store_and_delete_a_registration_and_refuse_a_bad_one_with_400() =
        testApplication {
            module()
            val (id, token) = devices.pair()

            suspend fun put(registration: PushRegistration) =
                client.put("/devices/me/push") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(protocolJson.encodeToString(PushRegistration.serializer(), registration))
                }

            val bad = put(phone.registration("http://push.example.ts.net/up/abc"))
            assertEquals(HttpStatusCode.BadRequest, bad.status)
            assertTrue(bad.bodyAsText().contains(StorePushRegistry.INVALID_CODE))
            assertNull(devices.store.push(id))

            assertEquals(HttpStatusCode.NoContent, put(phone.registration("https://push.example.ts.net/up/abc")).status)
            assertNotNull(devices.store.push(id))

            val removed = client.delete("/devices/me/push") { header(HttpHeaders.Authorization, "Bearer $token") }
            assertEquals(HttpStatusCode.NoContent, removed.status)
            assertNull(devices.store.push(id))

            // Unpairing from the app revokes the device, which takes its registration with it.
            put(phone.registration("https://push.example.ts.net/up/abc"))
            client.delete("/devices/me") { header(HttpHeaders.Authorization, "Bearer $token") }
            assertNull(devices.store.push(id))
            assertEquals(emptyList(), devices.store.pushDevices())
        }
}
