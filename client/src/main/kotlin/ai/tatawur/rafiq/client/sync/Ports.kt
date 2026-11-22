package ai.tatawur.rafiq.client.sync

import ai.tatawur.rafiq.protocol.Event
import ai.tatawur.rafiq.protocol.api.ApiError
import ai.tatawur.rafiq.protocol.api.SessionSummary
import ai.tatawur.rafiq.protocol.protocolJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException

// The platform services the sync logic needs. :android implements them with Room, OkHttp and the
// Android lifecycle, and the tests implement them in memory (ADR 0011).

/**
 * Where the paired bridge is and the device token it accepts.
 *
 * @property baseUrl `https://` with no trailing slash, as [PairingProbe.normaliseUrl] returns it.
 */
data class BridgeCredentials(
    val baseUrl: String,
    val token: String,
) {
    // Holds a secret, so it must never print one.
    override fun toString(): String = "BridgeCredentials(baseUrl=$baseUrl)"
}

/** The app's offline copy of what the bridge sent. Every flow in the app starts from it. */
interface EventStore {
    /** Every cached event of [sessionId], in `seq` order. */
    suspend fun events(sessionId: String): List<Event>

    /** Caches [event]. Storing the same `(session, seq)` again replaces it, so a replay is harmless. */
    suspend fun append(event: Event)

    /** The session list as last fetched. */
    suspend fun summaries(): List<SessionSummary>

    /** Replaces the cached session list with [summaries]. */
    suspend fun replaceSummaries(summaries: List<SessionSummary>)

    /** Forgets everything, for unpairing. */
    suspend fun clear()
}

/** The HTTP verbs the bridge's routes use. */
enum class HttpMethod { GET, POST, DELETE }

/**
 * One call to the bridge.
 *
 * @property token sent as `Authorization: Bearer`, or null for the open `GET /health`.
 * @property jsonBody the request body as JSON, or null for none.
 */
data class HttpRequest(
    val method: HttpMethod,
    val url: String,
    val token: String?,
    val jsonBody: String? = null,
) {
    // Holds a secret, so it must never print one.
    override fun toString(): String = "HttpRequest($method $url)"
}

/**
 * Whatever the bridge answered, success or not.
 *
 * @property retryAfterSeconds the `Retry-After` header as a number of seconds, or null when absent or a date.
 */
data class HttpResponse(
    val status: Int,
    val body: String,
    val retryAfterSeconds: Long? = null,
)

/** Sends HTTP requests. */
fun interface HttpTransport {
    /**
     * Sends [request] and returns the answer, whatever its status.
     *
     * @throws IOException when nothing answered: no network, DNS, TLS or a timeout.
     */
    suspend fun execute(request: HttpRequest): HttpResponse
}

/** What a socket from [EventSource] delivers. */
sealed interface SocketFrame {
    /** The handshake succeeded and the bridge is streaming. */
    data object Opened : SocketFrame

    /** One text frame, which the bridge fills with one event as JSON. */
    data class Text(
        val text: String,
    ) : SocketFrame
}

/** Opens `WS /events` sockets. */
fun interface EventSource {
    /**
     * A cold flow that opens a socket to [url] with the token as a `Sec-WebSocket-Protocol` value when
     * collected, and closes it when collection stops. It completes when the bridge closes the socket.
     *
     * @throws HandshakeRejectedException when the bridge answers the handshake with an HTTP error.
     * @throws IOException when the connection fails or drops.
     */
    fun open(
        url: String,
        token: String,
    ): Flow<SocketFrame>
}

/**
 * The bridge refused a socket handshake with HTTP [status], for example 401 for a wrong token. [code] is the
 * error code from the answer's body, such as [ai.tatawur.rafiq.protocol.api.AuthErrorCodes.IDENTITY_MISMATCH], or null.
 */
class HandshakeRejectedException(
    val status: Int,
    val code: String? = null,
) : IOException("The bridge refused the socket with HTTP $status.")

/** The error code in a bridge error body, or null when the body is not one. For a refused socket handshake. */
fun apiErrorCode(body: String): String? =
    try {
        protocolJson.decodeFromString(ApiError.serializer(), body).error.code
    } catch (_: IllegalArgumentException) {
        null
    }

/** What the app's process is doing, which decides whether it may hold a socket open. */
interface AppConditions {
    /** True while any of the app's screens is visible. */
    val foreground: StateFlow<Boolean>

    /** True while the phone has a network. A change from false to true cuts any backoff short. */
    val networkAvailable: StateFlow<Boolean>
}
