package ai.tatawur.rafiq.protocol.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Body of `POST /pair`, the one call that needs no device token.
 *
 * @property code the pairing code `rafiq-bridge pair` printed. Case and a hyphen do not matter.
 */
@Serializable
data class PairRequest(
    @SerialName("code") val code: String,
)

/**
 * Answer to a successful `POST /pair`. The app stores [token] and sends it on every later call. The
 * bridge keeps only a hash, so this is the only time anyone sees it.
 *
 * @property deviceId the id `rafiq-bridge devices` lists and `rafiq-bridge revoke` takes.
 * @property name the name the owner gave the device when creating the code.
 */
@Serializable
data class PairResponse(
    @SerialName("device_id") val deviceId: String,
    @SerialName("name") val name: String,
    @SerialName("token") val token: String,
) {
    // Holds a secret, so it must never print one.
    override fun toString(): String = "PairResponse(deviceId=$deviceId, name=$name)"
}

/** Error codes of the pairing and device checks. Branch on these, not on messages. */
object AuthErrorCodes {
    /** The device token belongs to a device that was revoked. The app clears it and asks to pair again. */
    const val DEVICE_REVOKED = "device_revoked"

    /** `POST /pair` got a code that is wrong, expired or already used. The three look the same on purpose. */
    const val INVALID_PAIRING_CODE = "invalid_pairing_code"

    /** `POST /pair` was tried too often. The `Retry-After` header says how many seconds to wait. */
    const val TOO_MANY_ATTEMPTS = "too_many_attempts"
}
