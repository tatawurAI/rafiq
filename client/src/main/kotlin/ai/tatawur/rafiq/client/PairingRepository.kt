package ai.tatawur.rafiq.client

import kotlinx.coroutines.flow.StateFlow

/**
 * Pairs the app with one bridge. In this version you type the server URL and paste the device token.
 * The token is stored encrypted, with its key in the Android Keystore, and is never shown again.
 */
interface PairingRepository {
    /** The paired bridge, or null before pairing and after [unpair]. */
    val server: StateFlow<PairedServer?>

    /**
     * Checks [baseUrl] with `GET /health`, then makes one authenticated call with [token]. Stores both
     * only when the two succeed.
     *
     * @param baseUrl an `https://` URL, for example the bridge's `tailscale serve` name.
     */
    suspend fun pair(
        baseUrl: String,
        token: String,
    ): PairingResult

    /** Forgets the server, the token and the cached sessions. */
    suspend fun unpair()
}

/**
 * @property baseUrl the bridge's URL as the user typed it, normalised: `https://`, no trailing slash.
 * @property bridgeVersion the version `GET /health` reported at pairing.
 */
data class PairedServer(
    val baseUrl: String,
    val bridgeVersion: String,
)

/** Why pairing worked or failed. Each failure kind gets its own hint on the pairing screen. */
sealed interface PairingResult {
    data class Paired(
        val server: PairedServer,
    ) : PairingResult

    /** Not a URL, or not `https://`. The app has no cleartext exception. */
    data object InvalidUrl : PairingResult

    /** Nothing answered, or TLS failed. Is Tailscale on, and is `tailscale serve` running? */
    data class Unreachable(
        val detail: String,
    ) : PairingResult

    /** Something answered, but not with a Rafiq `/health` body. */
    data object NotABridge : PairingResult

    /** The bridge refused the host name (421). Add it to `RAFIQ_ALLOWED_HOSTS`. */
    data object HostRejected : PairingResult

    /** The bridge refused the token (401). */
    data object TokenRejected : PairingResult
}
