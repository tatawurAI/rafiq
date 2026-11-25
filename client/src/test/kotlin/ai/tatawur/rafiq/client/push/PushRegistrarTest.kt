package ai.tatawur.rafiq.client.push

import ai.tatawur.rafiq.client.sync.BridgeApi
import ai.tatawur.rafiq.client.sync.BridgeCredentials
import ai.tatawur.rafiq.client.sync.HttpMethod
import ai.tatawur.rafiq.client.sync.HttpRequest
import ai.tatawur.rafiq.client.sync.HttpResponse
import ai.tatawur.rafiq.protocol.api.PushRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

/** Your server must always hold this phone's current endpoint while paired, and none after. */
class PushRegistrarTest {
    private val requests = mutableListOf<HttpRequest>()
    private val distributor = FakeDistributor()
    private val paired = MutableStateFlow<BridgeCredentials?>(null)
    private val network = MutableStateFlow(true)
    private var offline = false

    @Test
    fun pairing_subscribes_and_sends_the_endpoint_to_your_server_once() =
        runTest {
            val registrar = started()

            paired.value = SERVER_A
            testScheduler.runCurrent()
            registrar.endpoint(ENDPOINT_1)
            registrar.endpoint(ENDPOINT_1)

            assertEquals(1, distributor.subscribes)
            assertEquals(listOf("PUT ${SERVER_A.baseUrl}/devices/me/push"), sent())
            assertEquals(PushStatus.Ready, registrar.status.value)
        }

    @Test
    fun a_new_endpoint_or_a_new_pairing_is_sent_again() =
        runTest {
            val registrar = started()
            paired.value = SERVER_A
            testScheduler.runCurrent()
            registrar.endpoint(ENDPOINT_1)

            registrar.endpoint(ENDPOINT_2)
            paired.value = SERVER_A.copy(token = "new-token")
            testScheduler.runCurrent()

            assertEquals(3, requests.count { it.method == HttpMethod.PUT })
            assertEquals("new-token", requests.last().token)
            assertEquals(ENDPOINT_2.endpoint, requests.last().jsonBody?.let(::endpointOf))
        }

    @Test
    fun unpairing_unsubscribes_and_an_endpoint_arriving_after_is_not_sent() =
        runTest {
            val registrar = started()
            paired.value = SERVER_A
            testScheduler.runCurrent()
            registrar.endpoint(ENDPOINT_1)

            paired.value = null
            testScheduler.runCurrent()
            registrar.endpoint(ENDPOINT_2)

            assertEquals(1, distributor.unsubscribes)
            assertEquals(1, requests.size)
            assertEquals(PushStatus.NotPaired, registrar.status.value)
        }

    @Test
    fun a_dropped_endpoint_is_deleted_on_your_server() =
        runTest {
            val registrar = started()
            paired.value = SERVER_A
            testScheduler.runCurrent()
            registrar.endpoint(ENDPOINT_1)

            registrar.endpointLost()

            assertEquals("DELETE ${SERVER_A.baseUrl}/devices/me/push", sent().last())
        }

    @Test
    fun a_registration_that_could_not_reach_your_server_is_sent_when_the_network_returns() =
        runTest {
            val registrar = started()
            paired.value = SERVER_A
            testScheduler.runCurrent()
            offline = true
            network.value = false
            testScheduler.runCurrent()
            registrar.endpoint(ENDPOINT_1)
            assertEquals(PushStatus.Offline, registrar.status.value)

            offline = false
            network.value = true
            testScheduler.runCurrent()

            assertEquals(PushStatus.Ready, registrar.status.value)
            assertEquals(2, requests.size)
        }

    private fun TestScope.started(): PushRegistrar {
        val api =
            BridgeApi({ request ->
                requests += request
                if (offline) throw IOException("Network is unreachable")
                HttpResponse(204, "")
            }) { paired.value }
        return PushRegistrar(api, distributor, paired, network, backgroundScope).also {
            it.start()
            testScheduler.runCurrent()
        }
    }

    private fun sent() = requests.map { "${it.method} ${it.url}" }

    private fun endpointOf(json: String) =
        ai.tatawur.rafiq.protocol.protocolJson
            .decodeFromString(PushRegistration.serializer(), json)
            .endpoint

    private class FakeDistributor : PushDistributor {
        var subscribes = 0
        var unsubscribes = 0

        override fun subscribe() {
            subscribes++
        }

        override fun unsubscribe() {
            unsubscribes++
        }
    }

    private companion object {
        val SERVER_A = BridgeCredentials("https://bridge.example.ts.net", "test-token")
        val ENDPOINT_1 = PushRegistration("https://ntfy.example.org/up1", "BPublicKeyOne", "authOne")
        val ENDPOINT_2 = PushRegistration("https://ntfy.example.org/up2", "BPublicKeyTwo", "authTwo")
    }
}
