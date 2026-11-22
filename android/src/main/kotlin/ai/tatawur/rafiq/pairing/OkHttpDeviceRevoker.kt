package ai.tatawur.rafiq.pairing

import ai.tatawur.rafiq.client.pairing.DeviceRevoker
import ai.tatawur.rafiq.client.pairing.RevokeSelfResult
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume

/**
 * [DeviceRevoker] over OkHttp. It uses [client] without its interceptors, so a `device_revoked` answer to
 * this call does not also raise the "your server removed this phone" notice.
 */
class OkHttpDeviceRevoker(
    client: OkHttpClient,
) : DeviceRevoker {
    private val client = client.newBuilder().apply { interceptors().clear() }.build()

    override suspend fun revokeSelf(
        baseUrl: String,
        token: String,
    ): RevokeSelfResult {
        val request =
            try {
                Request
                    .Builder()
                    .url("$baseUrl/devices/me")
                    .delete()
                    .header("Authorization", "Bearer $token")
                    .build()
            } catch (_: IllegalArgumentException) {
                return RevokeSelfResult.UNCONFIRMED
            }
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) = continuation.resume(RevokeSelfResult.UNCONFIRMED)

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        // 204 is done. 401 means the bridge already refuses this token: nothing left to revoke.
                        val gone = response.use { it.code == NO_CONTENT || it.code == UNAUTHORIZED }
                        continuation.resume(if (gone) RevokeSelfResult.GONE else RevokeSelfResult.UNCONFIRMED)
                    }
                },
            )
        }
    }

    private companion object {
        const val NO_CONTENT = 204
        const val UNAUTHORIZED = 401
    }
}
