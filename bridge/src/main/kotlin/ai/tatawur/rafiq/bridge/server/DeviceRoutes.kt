package ai.tatawur.rafiq.bridge.server

import ai.tatawur.rafiq.protocol.api.ApiError
import ai.tatawur.rafiq.protocol.api.ApiErrorDetail
import ai.tatawur.rafiq.protocol.api.AuthErrorCodes
import ai.tatawur.rafiq.protocol.api.PairRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post

/**
 * `DELETE /devices/me` (needs a device token): revokes the calling device and closes its sockets, as
 * `rafiq-bridge revoke` does. The app calls it when you unpair. It can never touch another device,
 * because the id comes from the verified token, not from the request.
 *
 * `POST /pair`: trades a pairing code for a device token. It is the only route besides `/health` that
 * needs no token, which [BridgeSecurity] allows by path. The host and origin checks still apply.
 */
fun Route.deviceRoutes(devices: DeviceService) {
    delete("/devices/me") {
        devices.revoke(call.attributes[DevicePrincipalKey].deviceId)
        call.respond(HttpStatusCode.NoContent)
    }
    post("/pair") {
        val request = call.receive<PairRequest>()
        when (val result = devices.redeem(request.code)) {
            is RedeemResult.Paired -> {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respond(HttpStatusCode.Created, result.response)
            }

            RedeemResult.Invalid -> {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ApiError(
                        ApiErrorDetail(
                            AuthErrorCodes.INVALID_PAIRING_CODE,
                            "That code is wrong, expired or already used. Run rafiq-bridge pair again.",
                        ),
                    ),
                )
            }

            is RedeemResult.Throttled -> {
                call.response.header(HttpHeaders.RetryAfter, result.retryAfterSeconds.toString())
                call.respond(
                    HttpStatusCode.TooManyRequests,
                    ApiError(
                        ApiErrorDetail(AuthErrorCodes.TOO_MANY_ATTEMPTS, "Too many wrong codes. Wait and try again."),
                    ),
                )
            }
        }
    }
}
