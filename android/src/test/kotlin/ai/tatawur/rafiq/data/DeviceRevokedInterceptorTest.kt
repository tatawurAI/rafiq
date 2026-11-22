package ai.tatawur.rafiq.data

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DeviceRevokedInterceptorTest {
    private val heard = mutableListOf<String>()

    /** A client whose "network" answers every request with [status] and [body]. */
    private fun client(
        status: Int,
        body: String,
    ) = OkHttpClient
        .Builder()
        .addInterceptor(DeviceRevokedInterceptor { heard += it })
        .addInterceptor { chain ->
            Response
                .Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("answer")
                .body(body.toResponseBody())
                .build()
        }.build()

    private val revoked = """{"error":{"code":"device_revoked","message":"m"}}"""

    @Test
    fun a_device_revoked_answer_reports_the_token_the_request_carried_and_leaves_the_body_readable() {
        val bearer =
            Request
                .Builder()
                .url(
                    "https://b.example/sessions",
                ).header("Authorization", "Bearer rfqd_a")
                .build()
        val socket =
            Request
                .Builder()
                .url("https://b.example/events")
                .header("Sec-WebSocket-Protocol", "rafiq.v1, rfqd_b")
                .build()

        val response = client(401, revoked).newCall(bearer).execute()
        client(401, revoked).newCall(socket).execute().close()

        assertEquals(listOf("rfqd_a", "rfqd_b"), heard)
        assertEquals(revoked, response.body.string())
    }

    @Test
    fun a_wrong_token_or_another_status_is_not_a_revocation() {
        val request =
            Request
                .Builder()
                .url(
                    "https://b.example/sessions",
                ).header("Authorization", "Bearer rfqd_a")
                .build()

        client(401, """{"error":{"code":"unauthorized","message":"m"}}""").newCall(request).execute().close()
        client(403, revoked).newCall(request).execute().close()

        assertEquals(emptyList(), heard)
    }
}
