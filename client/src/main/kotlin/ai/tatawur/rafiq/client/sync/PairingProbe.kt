package ai.tatawur.rafiq.client.sync

import ai.tatawur.rafiq.client.PairedServer
import ai.tatawur.rafiq.client.PairingResult
import ai.tatawur.rafiq.protocol.api.Health
import ai.tatawur.rafiq.protocol.protocolJson
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException

/**
 * Checks that a URL and token reach a Rafiq bridge that accepts the token, without storing anything.
 * First `GET /health`, which needs no token, then `GET /sessions`, which does. Each failure maps to the
 * [PairingResult] kind whose hint fixes it.
 */
class PairingProbe(
    private val http: HttpTransport,
) {
    /** Probes the bridge at [baseUrl] with [token]. Whitespace around either is ignored. */
    suspend fun probe(
        baseUrl: String,
        token: String,
    ): PairingResult {
        val url = normaliseUrl(baseUrl) ?: return PairingResult.InvalidUrl
        return when (val health = health(url)) {
            is Checked.Failed -> health.result
            is Checked.Passed -> authenticate(url, token.trim(), health.version)
        }
    }

    private suspend fun health(url: String): Checked {
        val response =
            try {
                http.execute(HttpRequest(HttpMethod.GET, "$url/health", token = null))
            } catch (failure: IOException) {
                return Checked.Failed(unreachable(failure))
            }
        val body =
            try {
                protocolJson.decodeFromString(Health.serializer(), response.body)
            } catch (_: IllegalArgumentException) {
                null
            }
        return when {
            response.status == MISDIRECTED -> Checked.Failed(PairingResult.HostRejected)
            response.status !in SUCCESS || body == null -> Checked.Failed(PairingResult.NotABridge)
            else -> Checked.Passed(body.version)
        }
    }

    private suspend fun authenticate(
        url: String,
        token: String,
        version: String,
    ): PairingResult {
        val response =
            try {
                http.execute(HttpRequest(HttpMethod.GET, "$url/sessions", token))
            } catch (failure: IOException) {
                return unreachable(failure)
            }
        return when (response.status) {
            in SUCCESS -> PairingResult.Paired(PairedServer(url, version))
            UNAUTHORIZED -> PairingResult.TokenRejected
            MISDIRECTED -> PairingResult.HostRejected
            else -> PairingResult.NotABridge
        }
    }

    private sealed interface Checked {
        data class Passed(
            val version: String,
        ) : Checked

        data class Failed(
            val result: PairingResult,
        ) : Checked
    }

    companion object {
        private val SUCCESS = 200..299
        private const val UNAUTHORIZED = 401
        private const val MISDIRECTED = 421

        private fun unreachable(failure: IOException) =
            PairingResult.Unreachable(failure.message ?: failure::class.simpleName.orEmpty())

        /**
         * Returns [input] as `https://host[:port][/path]` with no trailing slash, or null when it is not a
         * usable `https://` URL. A URL typed without a scheme is taken as `https://`. Any other scheme,
         * including `http://`, is refused: the app has no cleartext exception (ADR 0012).
         */
        fun normaliseUrl(input: String): String? {
            val trimmed = input.trim().trimEnd('/')
            val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
            val uri =
                try {
                    URI(withScheme)
                } catch (_: URISyntaxException) {
                    null
                }
            val valid =
                uri != null &&
                    uri.scheme.equals("https", ignoreCase = true) &&
                    !uri.host.isNullOrEmpty() &&
                    uri.rawQuery == null &&
                    uri.rawFragment == null &&
                    uri.rawUserInfo == null
            if (!valid) return null
            val port = if (uri.port == -1) "" else ":${uri.port}"
            return "https://${uri.host.lowercase()}$port${uri.rawPath.orEmpty()}"
        }
    }
}
