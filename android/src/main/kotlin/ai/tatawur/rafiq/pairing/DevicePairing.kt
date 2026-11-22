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

    /**
     * Set when [unpair] cleared this phone but could not tell the bridge, so the bridge still lists the
     * device. Carries the device id for `rafiq-bridge revoke <id>`, or null when it is not known.
     */
    val unpairNotice: StateFlow<UnpairNotice?>

    /** Hides the unpair notice. */
    fun dismissUnpairNotice()
}

/**
 * This phone is unpaired here, but your server still lists it.
 *
 * @property deviceId for `rafiq-bridge revoke`, or null for a phone paired with a pasted token.
 */
data class UnpairNotice(
    val deviceId: String?,
)
