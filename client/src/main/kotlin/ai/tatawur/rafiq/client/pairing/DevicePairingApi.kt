package ai.tatawur.rafiq.client.pairing

import ai.tatawur.rafiq.client.PairedServer
import ai.tatawur.rafiq.client.sync.HttpMethod
import ai.tatawur.rafiq.client.sync.HttpRequest
import ai.tatawur.rafiq.client.sync.HttpResponse
import ai.tatawur.rafiq.client.sync.HttpTransport
import ai.tatawur.rafiq.client.sync.PairingProbe
import ai.tatawur.rafiq.protocol.api.ApiError
import ai.tatawur.rafiq.protocol.api.AuthErrorCodes
import ai.tatawur.rafiq.protocol.api.Health
import ai.tatawur.rafiq.protocol.api.PairRequest
import ai.tatawur.rafiq.protocol.api.PairResponse
import ai.tatawur.rafiq.protocol.protocolJson
import java.io.IOException

/** How pairing with a code went. Each failure gets its own hint on the pairing screen. */
sealed interface CodePairResult {
    /** The bridge issued a token for this phone. [deviceToken] is shown to nobody and stored encrypted. */
    data class Paired(
        val server: PairedServer,
        val deviceId: String,
        val deviceToken: String,
    ) : CodePairResult {
        // Holds a secret, so it must never print one.
        override fun toString(): String = "Paired(server=$server)"
    }

    /** Not a URL, or not `https://`. The app has no cleartext exception (ADR 0012). */
    data object InvalidUrl : CodePairResult

    /** Nothing answered, or TLS failed. */
    data class Unreachable(
        val detail: String,
    ) : CodePairResult

    /** Something answered, but not like a Rafiq bridge. */
    data object NotABridge : CodePairResult

    /** The bridge refused the host name (421). Add it to `RAFIQ_ALLOWED_HOSTS`. */
    data object HostRejected : CodePairResult

    /** The code is wrong, expired or already used. Run `rafiq-bridge pair` for a new one. */
    data object CodeRejected : CodePairResult

    /** Too many wrong codes. The bridge refuses every code for a few minutes, a right one too. */
    data object TooManyAttempts : CodePairResult
}

/**
 * Trades a pairing code for a device token: `GET /health` to check this is a bridge, then `POST /pair`.
 * Stores nothing and keeps nothing. The code is never logged or kept past the call.
 */
class DevicePairingApi(
    private val http: HttpTransport,
) {
    /** Pairs with the bridge at [baseUrl] using [code] from `rafiq-bridge pair`. Surrounding whitespace is ignored. */
    suspend fun pair(
        baseUrl: String,
        code: String,
    ): CodePairResult {
        val url = PairingProbe.normaliseUrl(baseUrl) ?: return CodePairResult.InvalidUrl
        return try {
            val health = http.execute(HttpRequest(HttpMethod.GET, "$url/health", token = null))
            val version = versionOf(health)
            when {
                health.status == MISDIRECTED -> CodePairResult.HostRejected
                version == null -> CodePairResult.NotABridge
                else -> redeem(url, version, code.trim())
            }
        } catch (failure: IOException) {
            CodePairResult.Unreachable(failure.message ?: failure::class.simpleName.orEmpty())
        }
    }

    private suspend fun redeem(
        url: String,
        version: String,
        code: String,
    ): CodePairResult {
        val body = protocolJson.encodeToString(PairRequest.serializer(), PairRequest(code))
        val response = http.execute(HttpRequest(HttpMethod.POST, "$url/pair", token = null, jsonBody = body))
        return when (response.status) {
            in SUCCESS -> {
                paired(response, PairedServer(url, version))
            }

            MISDIRECTED -> {
                CodePairResult.HostRejected
            }

            TOO_MANY -> {
                CodePairResult.TooManyAttempts
            }

            UNAUTHORIZED -> {
                if (errorCode(response) == AuthErrorCodes.INVALID_PAIRING_CODE) {
                    CodePairResult.CodeRejected
                } else {
                    CodePairResult.NotABridge
                }
            }

            else -> {
                CodePairResult.NotABridge
            }
        }
    }

    private fun paired(
        response: HttpResponse,
        server: PairedServer,
    ): CodePairResult =
        try {
            val answer = protocolJson.decodeFromString(PairResponse.serializer(), response.body)
            if (answer.token.isBlank()) {
                CodePairResult.NotABridge
            } else {
                CodePairResult.Paired(
                    server,
                    answer.deviceId,
                    answer.token,
                )
            }
        } catch (_: IllegalArgumentException) {
            CodePairResult.NotABridge
        }

    private fun versionOf(response: HttpResponse): String? =
        if (response.status !in SUCCESS) {
            null
        } else {
            try {
                protocolJson.decodeFromString(Health.serializer(), response.body).version
            } catch (_: IllegalArgumentException) {
                null
            }
        }

    private fun errorCode(response: HttpResponse): String? =
        try {
            protocolJson.decodeFromString(ApiError.serializer(), response.body).error.code
        } catch (_: IllegalArgumentException) {
            null
        }

    private companion object {
        val SUCCESS = 200..299
        const val UNAUTHORIZED = 401
        const val MISDIRECTED = 421
        const val TOO_MANY = 429
    }
}

/** Asks the bridge to revoke the calling device: `DELETE /devices/me`. Used when the user unpairs. */
fun interface DeviceRevoker {
    /** Revokes the device that owns [token] at [baseUrl]. A network failure is a result, not an exception. */
    suspend fun revokeSelf(
        baseUrl: String,
        token: String,
    ): RevokeSelfResult
}

/** What [DeviceRevoker.revokeSelf] achieved. */
enum class RevokeSelfResult {
    /** The bridge no longer accepts this device's token: revoked now, or it already refused the token. */
    GONE,

    /** The bridge could not be reached or did not answer as expected. The device stays paired there. */
    UNCONFIRMED,
}

/** Recognises the answer a revoked device gets, so the app can clear its token and ask to pair again. */
object DeviceRevoked {
    /** True for HTTP 401 whose error body carries `device_revoked`. A plain wrong token is not it. */
    fun matches(
        status: Int,
        body: String,
    ): Boolean =
        status == UNAUTHORIZED &&
            try {
                protocolJson.decodeFromString(ApiError.serializer(), body).error.code == AuthErrorCodes.DEVICE_REVOKED
            } catch (_: IllegalArgumentException) {
                false
            }

    private const val UNAUTHORIZED = 401
}
