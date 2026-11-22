package ai.tatawur.rafiq.pairing

import ai.tatawur.rafiq.client.PairingRepository
import ai.tatawur.rafiq.client.pairing.CodePairResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Pairing with a code from `rafiq-bridge pair`, and the notice shown when the bridge revoked this phone.
 * The pairing screen takes this rather than [PairingRepository], so it can be previewed against a fake.
 */
interface DevicePairing : PairingRepository {
    /**
     * Trades [code] for this phone's own device token and stores it, with its key in the Android Keystore.
     * Nothing is stored unless the bridge accepts the code.
     */
    suspend fun pairWithCode(
        baseUrl: String,
        code: String,
    ): CodePairResult

    /** True after the bridge revoked this phone, until the user dismisses the notice or pairs again. */
    val revokedNotice: StateFlow<Boolean>

    /** Hides the revoked notice. */
    fun dismissRevokedNotice()
}
