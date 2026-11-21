package ai.tatawur.rafiq.bridge.server

import ai.tatawur.rafiq.protocol.api.ApiError
import ai.tatawur.rafiq.protocol.api.ApiErrorDetail
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import java.security.MessageDigest

/**
 * Decides whether a request may reach the bridge's routes. Three checks, in this order:
 *
 * 1. `Host` must be the bind address, `localhost` or an entry of `RAFIQ_ALLOWED_HOSTS`, which stops a
 *    web page from reaching the bridge through DNS rebinding (421).
 * 2. A request that carries `Origin` must come from the bridge's own origin, which keeps the dev page
 *    working and refuses every other web page (403).
 * 3. Every route except `GET /health` and the static `/dev` page needs the device token as
 *    `Authorization: Bearer <token>` (401). A browser cannot set headers on a WebSocket, so `/events`
 *    also takes the token as one of the `Sec-WebSocket-Protocol` values. Never a query string.
 *
 * Pairing, per-device tokens and revocation arrive with M3. This is one shared token.
 *
 * @param deviceToken the secret, or null when none is set, in which case every protected route answers 401.
 */
class BridgeSecurity(
    deviceToken: String?,
    bindAddress: String,
    allowedHosts: List<String> = emptyList(),
) {
    private val tokenDigest = deviceToken?.let(::sha256)
    private val allowed =
        (allowedHosts + bindAddress + "localhost")
            .map {
                it.lowercase().removeSurrounding("[", "]")
            }.toSet()

    // Holds a secret, so it must never print one.
    override fun toString(): String = "BridgeSecurity(tokenSet=${tokenDigest != null}, allowedHosts=$allowed)"

    /** What the check decided. [echoProtocol] is the subprotocol the WebSocket handshake must select, if any. */
    sealed interface Verdict {
        data class Accepted(
            val echoProtocol: String? = null,
        ) : Verdict

        data class Rejected(
            val status: HttpStatusCode,
            val code: String,
            val message: String,
        ) : Verdict
    }

    /** Checks one request from its path and the headers the checks read. */
    fun inspect(
        path: String,
        host: String?,
        origin: String?,
        authorization: String?,
        webSocketProtocols: String?,
    ): Verdict {
        val misdirected = Verdict.Rejected(MISDIRECTED_REQUEST, "misdirected_request", "Unknown host.")
        val forbiddenOrigin =
            Verdict.Rejected(
                HttpStatusCode.Forbidden,
                "forbidden_origin",
                "Cross-origin requests are refused.",
            )
        val unauthorized =
            Verdict.Rejected(
                HttpStatusCode.Unauthorized,
                "unauthorized",
                "Missing or wrong device token.",
            )
        return when {
            host == null || hostName(host) !in allowed -> misdirected
            origin != null && !origin.substringAfter("://", "").equals(host, ignoreCase = true) -> forbiddenOrigin
            path == "/health" || path == "/dev" -> Verdict.Accepted()
            else -> authenticate(path, authorization, webSocketProtocols) ?: unauthorized
        }
    }

    private fun authenticate(
        path: String,
        authorization: String?,
        webSocketProtocols: String?,
    ): Verdict.Accepted? {
        val bearer =
            authorization
                ?.takeIf {
                    it.startsWith(
                        BEARER_PREFIX,
                        ignoreCase = true,
                    )
                }?.substring(BEARER_PREFIX.length)
        val offered =
            webSocketProtocols
                ?.takeIf { path == "/events" }
                ?.split(',')
                ?.map(String::trim)
                .orEmpty()
        return when {
            bearer != null && matches(bearer.trim()) -> Verdict.Accepted()
            offered.any(::matches) -> Verdict.Accepted(echoProtocol = WS_PROTOCOL.takeIf { it in offered })
            else -> null
        }
    }

    private fun matches(candidate: String): Boolean =
        tokenDigest != null && MessageDigest.isEqual(tokenDigest, sha256(candidate))

    private fun hostName(hostHeader: String): String =
        if (hostHeader.startsWith("[")) {
            hostHeader.substringAfter('[').substringBefore(']')
        } else {
            hostHeader.substringBefore(':')
        }.lowercase()

    companion object {
        /** The WebSocket subprotocol a browser lists next to the token: `new WebSocket(url, ["rafiq.v1", token])`. */
        const val WS_PROTOCOL = "rafiq.v1"
        private const val BEARER_PREFIX = "Bearer "
        private const val MISDIRECTED_STATUS = 421
        private val MISDIRECTED_REQUEST = HttpStatusCode(MISDIRECTED_STATUS, "Misdirected Request")

        // Comparing digests keeps the comparison constant-time whatever the lengths are.
        private fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    }
}

/** Runs [security] on every request before routing, and answers with the standard error body on a rejection. */
internal fun Application.installSecurity(security: BridgeSecurity) {
    intercept(ApplicationCallPipeline.Plugins) {
        val request = call.request
        when (
            val verdict =
                security.inspect(
                    path = request.path(),
                    host = request.header(HttpHeaders.Host) ?: request.local.serverHost,
                    origin = request.header(HttpHeaders.Origin),
                    authorization = request.header(HttpHeaders.Authorization),
                    webSocketProtocols = request.header(HttpHeaders.SecWebSocketProtocol),
                )
        ) {
            is BridgeSecurity.Verdict.Rejected -> {
                call.respond(verdict.status, ApiError(ApiErrorDetail(verdict.code, verdict.message)))
                finish()
            }

            is BridgeSecurity.Verdict.Accepted -> {
                verdict.echoProtocol?.let { call.response.header(HttpHeaders.SecWebSocketProtocol, it) }
            }
        }
    }
}
