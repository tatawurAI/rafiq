package ai.tatawur.rafiq.bridge.server

import ai.tatawur.rafiq.protocol.api.ApiError
import ai.tatawur.rafiq.protocol.api.ApiErrorDetail
import ai.tatawur.rafiq.protocol.api.AuthErrorCodes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond

/**
 * Decides whether a request may reach the bridge's routes. Three checks, in this order:
 *
 * 1. `Host` must be the bind address, `localhost` or an entry of `RAFIQ_ALLOWED_HOSTS`, which stops a
 *    web page from reaching the bridge through DNS rebinding (421).
 * 2. A request that carries `Origin` must come from the bridge's own origin, which keeps the dev page
 *    working and refuses every other web page (403).
 * 3. Every route except `GET /health`, the static `/dev` page and `POST /pair` needs a device token as
 *    `Authorization: Bearer <token>` (401). A browser cannot set headers on a WebSocket, so `/events` and
 *    `/terminal` also take the token as one of the `Sec-WebSocket-Protocol` values, next to `rafiq.v1` or
 *    `rafiq.term.v1`. Never a query string. A token of a revoked device gets 401 `device_revoked`, so the
 *    app can tell "pair again" from "wrong token".
 *
 * The token is checked against the device store on every request, never cached, so a revocation takes
 * effect on the next call. The checks set [DevicePrincipalKey] on the call.
 *
 * @param devices decides which device, if any, a token belongs to.
 */
class BridgeSecurity(
    bindAddress: String,
    allowedHosts: List<String>,
    private val devices: DeviceVerifier,
) {
    /** One shared token and no device store: what the bridge was before per-device tokens. Tests use it. */
    constructor(
        deviceToken: String?,
        bindAddress: String,
        allowedHosts: List<String> = emptyList(),
    ) : this(bindAddress, allowedHosts, SingleTokenVerifier(deviceToken))

    private val allowed =
        (allowedHosts + bindAddress + "localhost")
            .map {
                it.lowercase().removeSurrounding("[", "]")
            }.toSet()

    // Holds a secret, so it must never print one.
    override fun toString(): String = "BridgeSecurity(allowedHosts=$allowed)"

    /** What the check decided. [echoProtocol] is the subprotocol the WebSocket handshake must select, if any. */
    sealed interface Verdict {
        data class Accepted(
            val echoProtocol: String? = null,
            val deviceId: String? = null,
        ) : Verdict

        data class Rejected(
            val status: HttpStatusCode,
            val code: String,
            val message: String,
        ) : Verdict
    }

    /** Checks one request from its path and the headers the checks read. */
    suspend fun inspect(
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
            path in OPEN_PATHS -> Verdict.Accepted()
            else -> authenticate(path, authorization, webSocketProtocols) ?: unauthorized
        }
    }

    private suspend fun authenticate(
        path: String,
        authorization: String?,
        webSocketProtocols: String?,
    ): Verdict? {
        val bearer =
            authorization
                ?.takeIf {
                    it.startsWith(
                        BEARER_PREFIX,
                        ignoreCase = true,
                    )
                }?.substring(BEARER_PREFIX.length)
                ?.trim()
        val offered =
            webSocketProtocols
                ?.takeIf { path in SOCKET_PATHS }
                ?.split(',')
                ?.map(String::trim)
                .orEmpty()
        val echo = listOf(WS_PROTOCOL, TERMINAL_WS_PROTOCOL).firstOrNull { it in offered }
        // A socket carries the token as one subprotocol value next to the protocol name.
        val candidates =
            listOfNotNull(bearer?.takeIf(String::isNotEmpty)) +
                offered.filter { it.isNotEmpty() && it !in PROTOCOL_NAMES }
        var revoked = false
        for (candidate in candidates) {
            when (val check = devices.verify(candidate)) {
                is DeviceCheck.Active -> {
                    return Verdict.Accepted(echoProtocol = echo.takeIf { bearer == null }, deviceId = check.deviceId)
                }

                DeviceCheck.Revoked -> {
                    revoked = true
                }

                DeviceCheck.Unknown -> {
                    Unit
                }
            }
        }
        return if (revoked) {
            Verdict.Rejected(
                HttpStatusCode.Unauthorized,
                AuthErrorCodes.DEVICE_REVOKED,
                "This device was revoked. Pair it again.",
            )
        } else {
            null
        }
    }

    private fun hostName(hostHeader: String): String =
        if (hostHeader.startsWith("[")) {
            hostHeader.substringAfter('[').substringBefore(']')
        } else {
            hostHeader.substringBefore(':')
        }.lowercase()

    companion object {
        /** The WebSocket subprotocol a browser lists next to the token: `new WebSocket(url, ["rafiq.v1", token])`. */
        const val WS_PROTOCOL = "rafiq.v1"

        /** The subprotocol of `/terminal`, listed the same way next to the token. */
        const val TERMINAL_WS_PROTOCOL = "rafiq.term.v1"

        /** The device id of the single shared token, until per-device tokens replace it. */
        const val LEGACY_DEVICE_ID = "legacy"
        private val SOCKET_PATHS = setOf("/events", "/terminal")
        private val PROTOCOL_NAMES = setOf(WS_PROTOCOL, TERMINAL_WS_PROTOCOL)

        // `/pair` has no token by design: it is where a device gets one. See DeviceRoutes.
        private val OPEN_PATHS = setOf("/health", "/dev", "/pair")
        private const val BEARER_PREFIX = "Bearer "
        private const val MISDIRECTED_STATUS = 421
        private val MISDIRECTED_REQUEST = HttpStatusCode(MISDIRECTED_STATUS, "Misdirected Request")
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
                verdict.deviceId?.let { call.attributes.put(DevicePrincipalKey, DevicePrincipal(it)) }
                verdict.echoProtocol?.let { call.response.header(HttpHeaders.SecWebSocketProtocol, it) }
            }
        }
    }
}
