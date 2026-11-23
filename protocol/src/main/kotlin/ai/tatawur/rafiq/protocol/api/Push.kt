package ai.tatawur.rafiq.protocol.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Body of `PUT /devices/me/push`: where the bridge sends this phone's pushes, through UnifiedPush (see ADR 0010).
 * `DELETE /devices/me/push` stops them. Revoking the device deletes the registration too.
 *
 * @property endpoint the https URL the phone's UnifiedPush distributor gave the app.
 * @property p256dh the phone's P-256 public key for Web Push message encryption (RFC 8291), base64url.
 * @property auth the 16-byte authentication secret for the same, base64url.
 */
@Serializable
data class PushRegistration(
    @SerialName("endpoint") val endpoint: String,
    @SerialName("p256dh") val p256dh: String,
    @SerialName("auth") val auth: String,
) {
    // The endpoint URL is a capability: anyone holding it can push to the phone.
    override fun toString(): String = "PushRegistration(endpoint=<hidden>)"
}

/**
 * What a push carries, encrypted end to end. Ids and a kind only: never prompt text, command text or output.
 * The app fetches what it shows from the bridge over the tailnet.
 *
 * @property version the payload format, [PUSH_PAYLOAD_VERSION] today.
 * @property approvalId set for [PushKind.APPROVAL] and [PushKind.APPROVAL_CLEARED].
 */
@Serializable
data class PushPayload(
    @SerialName("v") val version: Int = PUSH_PAYLOAD_VERSION,
    @SerialName("kind") val kind: PushKind = PushKind.UNKNOWN,
    @SerialName("session_id") val sessionId: String,
    @SerialName("approval_id") val approvalId: String? = null,
)

/** Why the bridge pushed. */
@Serializable
enum class PushKind {
    /** An approval is waiting on you. */
    @SerialName("approval")
    APPROVAL,

    /** An approval went away, answered at the desk or elsewhere. The app removes its notification. */
    @SerialName("approval_cleared")
    APPROVAL_CLEARED,

    /** The agent finished its turn and waits for your next prompt. */
    @SerialName("turn_done")
    TURN_DONE,

    /** The session or a thread stopped with an error. */
    @SerialName("error")
    ERROR,

    /** A kind this build does not know. The app ignores it. */
    @SerialName("unknown")
    UNKNOWN,
}

/** The [PushPayload] format version. */
const val PUSH_PAYLOAD_VERSION = 1
