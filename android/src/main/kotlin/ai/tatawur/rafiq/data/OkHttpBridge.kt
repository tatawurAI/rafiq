package ai.tatawur.rafiq.data

import ai.tatawur.rafiq.client.pairing.DeviceRevoked
import ai.tatawur.rafiq.client.sync.EventSource
import ai.tatawur.rafiq.client.sync.HandshakeRejectedException
import ai.tatawur.rafiq.client.sync.HttpMethod
import ai.tatawur.rafiq.client.sync.HttpRequest
import ai.tatawur.rafiq.client.sync.HttpResponse
import ai.tatawur.rafiq.client.sync.HttpTransport
import ai.tatawur.rafiq.client.sync.SocketFrame
import ai.tatawur.rafiq.client.sync.apiErrorCode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The OkHttp clients the app shares. Both use the platform's default trust store (ADR 0012). */
internal object BridgeClients {
    private const val CONNECT_TIMEOUT_S = 10L
    private const val CALL_TIMEOUT_S = 20L

    // OkHttp fails a socket when a ping's pong has not come back by the next ping, so a link that died
    // silently is noticed within one to two intervals. The bridge (Ktor) answers pings on its own.
    private const val PING_INTERVAL_S = 5L

    /**
     * For REST calls. [onDeviceRevoked] hears the token of any request the bridge answered with
     * `device_revoked`, and sockets derived from this client report the same way.
     */
    fun rest(onDeviceRevoked: (token: String) -> Unit = {}): OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
            .addInterceptor(DeviceRevokedInterceptor(onDeviceRevoked))
            .build()

    /**
     * For `WS /events`, derived from [rest] so both share one connection pool. A live socket can be
     * quiet for minutes, so there is no read timeout. Pings notice a dead link instead.
     */
    fun sockets(rest: OkHttpClient): OkHttpClient =
        rest
            .newBuilder()
            .callTimeout(0, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(PING_INTERVAL_S, TimeUnit.SECONDS)
            .build()
}

/**
 * Tells [onRevoked] when the bridge answers a request with 401 `device_revoked`, passing the token that
 * request carried so the receiver can ignore an answer to a token it has already replaced. It reads at most
 * a kilobyte of the body and never changes the response. The token is never logged.
 */
internal class DeviceRevokedInterceptor(
    private val onRevoked: (token: String) -> Unit,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (response.code == UNAUTHORIZED &&
            DeviceRevoked.matches(response.code, response.peekBody(PEEK_BYTES).string())
        ) {
            tokenOf(request)?.let(onRevoked)
        }
        return response
    }

    private fun tokenOf(request: Request): String? {
        val bearer = request.header("Authorization")?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
        // A socket carries the token as the subprotocol value that is not the protocol name.
        return bearer
            ?: request
                .header("Sec-WebSocket-Protocol")
                ?.split(',')
                ?.map(String::trim)
                ?.lastOrNull { it.isNotEmpty() && !it.startsWith("rafiq.") }
    }

    private companion object {
        const val UNAUTHORIZED = 401
        const val PEEK_BYTES = 1024L
    }
}

/** [HttpTransport] over OkHttp. Cancelling the caller cancels the call. */
class OkHttpTransport(
    private val client: OkHttpClient,
) : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse {
        val call = client.newCall(request.toOkHttp())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        val answer =
                            try {
                                response.use {
                                    // Retry-After as seconds; the date form is rare and ignored.
                                    val retryAfter = it.header("Retry-After")?.trim()?.toLongOrNull()
                                    HttpResponse(it.code, it.body.string(), retryAfter)
                                }
                            } catch (failure: IOException) {
                                continuation.resumeWithException(failure)
                                return
                            }
                        continuation.resume(answer)
                    }
                },
            )
        }
    }

    private fun HttpRequest.toOkHttp(): Request {
        val builder =
            try {
                Request.Builder().url(url)
            } catch (invalid: IllegalArgumentException) {
                // The client treats any IOException as "nothing answered", which a malformed URL is.
                throw IOException("Not a valid URL.", invalid)
            }
        token?.let { builder.header("Authorization", "Bearer $it") }
        return when (method) {
            HttpMethod.GET -> builder.get()
            HttpMethod.POST -> builder.post(jsonBody.orEmpty().toRequestBody(JSON))
            HttpMethod.PUT -> builder.put(jsonBody.orEmpty().toRequestBody(JSON))
            HttpMethod.DELETE -> builder.delete(jsonBody.orEmpty().toRequestBody(JSON))
        }.build()
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

/**
 * [EventSource] over OkHttp's WebSocket. The token rides as the second `Sec-WebSocket-Protocol` value
 * next to `rafiq.v1`, never in the URL, so it stays out of logs.
 */
class OkHttpEventSource(
    private val client: OkHttpClient,
) : EventSource {
    override fun open(
        url: String,
        token: String,
    ): Flow<SocketFrame> =
        callbackFlow {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("Sec-WebSocket-Protocol", "$PROTOCOL, $token")
                    .build()
            val socket =
                client.newWebSocket(
                    request,
                    object : WebSocketListener() {
                        override fun onOpen(
                            webSocket: WebSocket,
                            response: Response,
                        ) {
                            trySend(SocketFrame.Opened)
                        }

                        override fun onMessage(
                            webSocket: WebSocket,
                            text: String,
                        ) {
                            trySend(SocketFrame.Text(text))
                        }

                        override fun onClosing(
                            webSocket: WebSocket,
                            code: Int,
                            reason: String,
                        ) {
                            webSocket.close(NORMAL_CLOSURE, null)
                            channel.close()
                        }

                        override fun onFailure(
                            webSocket: WebSocket,
                            t: Throwable,
                            response: Response?,
                        ) {
                            val status = response?.code
                            channel.close(
                                when {
                                    status != null && status != SWITCHING_PROTOCOLS -> {
                                        HandshakeRejectedException(status, response.errorCode())
                                    }

                                    t is IOException -> {
                                        t
                                    }

                                    else -> {
                                        IOException("The socket failed.", t)
                                    }
                                },
                            )
                        }
                    },
                )
            awaitClose { socket.close(NORMAL_CLOSURE, null) }
        }
            // Frames must never be dropped: a lost event would show up as a gap and a reconnect.
            .buffer(Channel.UNLIMITED)

    private companion object {
        const val PROTOCOL = "rafiq.v1"
        const val NORMAL_CLOSURE = 1000
        const val SWITCHING_PROTOCOLS = 101
    }
}

/**
 * The error code in a refused handshake's body, for example `identity_mismatch`, or null. Reads at most a
 * kilobyte and never the token.
 */
internal fun Response.errorCode(): String? =
    try {
        apiErrorCode(peekBody(ERROR_PEEK_BYTES).string())
    } catch (_: IOException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

private const val ERROR_PEEK_BYTES = 1024L
