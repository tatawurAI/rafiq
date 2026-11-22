package ai.tatawur.rafiq.client.pairing

import ai.tatawur.rafiq.client.PairedServer
import ai.tatawur.rafiq.client.sync.HttpRequest
import ai.tatawur.rafiq.client.sync.HttpResponse
import ai.tatawur.rafiq.client.sync.HttpTransport
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DevicePairingApiTest {
    private val health = HttpResponse(200, """{"status":"ok","version":"0.9.0"}""")
    private val sent = mutableListOf<HttpRequest>()

    private fun api(pair: HttpResponse) =
        DevicePairingApi { request ->
            sent += request
            if (request.url.endsWith("/health")) health else pair
        }

    private fun error(code: String) = """{"error":{"code":"$code","message":"m"}}"""

    @Test
    fun a_good_code_returns_the_token_and_sends_no_token_of_its_own() =
        runTest {
            val result =
                api(HttpResponse(201, """{"device_id":"dev_1","name":"Pixel","token":"rfqd_test"}"""))
                    .pair("bridge.example.ts.net", " ABCD-EFGH ")

            assertEquals(
                CodePairResult.Paired(PairedServer("https://bridge.example.ts.net", "0.9.0"), "dev_1", "rfqd_test"),
                result,
            )
            val post = sent.last()
            assertEquals("https://bridge.example.ts.net/pair", post.url)
            assertEquals("""{"code":"ABCD-EFGH"}""", post.jsonBody)
            assertEquals(null, post.token)
            assertFalse("rfqd_test" in result.toString())
        }

    @Test
    fun each_failure_maps_to_the_hint_that_fixes_it() =
        runTest {
            assertEquals(
                CodePairResult.CodeRejected,
                api(HttpResponse(401, error("invalid_pairing_code"))).pair("https://b.example", "x"),
            )
            assertEquals(
                CodePairResult.TooManyAttempts,
                api(HttpResponse(429, error("too_many_attempts"))).pair("https://b.example", "x"),
            )
            assertEquals(CodePairResult.HostRejected, api(HttpResponse(421, "")).pair("https://b.example", "x"))
            // A 401 that is not ours, or a page that is not a bridge, is not "wrong code".
            assertEquals(
                CodePairResult.NotABridge,
                api(HttpResponse(401, error("unauthorized"))).pair("https://b.example", "x"),
            )
            assertEquals(CodePairResult.NotABridge, api(HttpResponse(201, "<html>")).pair("https://b.example", "x"))
            assertEquals(CodePairResult.InvalidUrl, api(health).pair("http://b.example", "x"))
            val down = DevicePairingApi(HttpTransport { throw IOException("no route") })
            assertEquals(CodePairResult.Unreachable("no route"), down.pair("https://b.example", "x"))
        }

    @Test
    fun only_a_401_with_the_device_revoked_code_means_revoked() {
        assertTrue(DeviceRevoked.matches(401, error("device_revoked")))
        assertFalse(DeviceRevoked.matches(401, error("unauthorized")))
        assertFalse(DeviceRevoked.matches(403, error("device_revoked")))
        assertFalse(DeviceRevoked.matches(401, "not json"))
    }
}
