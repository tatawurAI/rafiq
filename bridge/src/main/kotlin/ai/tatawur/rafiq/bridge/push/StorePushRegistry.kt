package ai.tatawur.rafiq.bridge.push

import ai.tatawur.rafiq.bridge.sessions.SessionException
import ai.tatawur.rafiq.bridge.store.DeviceStore
import ai.tatawur.rafiq.bridge.store.StoredPush
import ai.tatawur.rafiq.protocol.api.PushRegistration
import java.net.URI
import java.net.URISyntaxException
import java.time.Clock
import java.util.Base64

/**
 * [PushRegistry] on the device store, so a registration lives next to its device and goes with it on revoke.
 * Every registration is checked by [validate] first.
 */
class StorePushRegistry(
    private val store: DeviceStore,
    private val clock: Clock = Clock.systemUTC(),
) : PushRegistry {
    /**
     * @throws SessionException.Conflict with [NOT_REGISTERED_CODE] when the caller is not an active paired device,
     *   such as the shared `RAFIQ_DEVICE_TOKEN` with no device record, or a request that raced its device's revoke.
     *   Nothing is stored then, and the phone must not believe it will get pushes.
     */
    override suspend fun register(
        deviceId: String,
        registration: PushRegistration,
    ) {
        if (!store.setPush(deviceId, validate(registration), clock.millis())) {
            throw SessionException.Conflict(
                NOT_REGISTERED_CODE,
                "Pushes need a paired device. Pair this phone with rafiq-bridge pair.",
            )
        }
    }

    override suspend fun unregister(deviceId: String) = store.deletePush(deviceId)

    companion object {
        /** The longest endpoint accepted. Push services hand out URLs well under this. */
        const val MAX_ENDPOINT_LENGTH = 1024

        /** An uncompressed P-256 point: the 0x04 marker and two 32-byte coordinates. */
        const val P256_PUBLIC_KEY_BYTES = 65

        /** RFC 8291 section 3.2: the authentication secret is 16 octets. */
        const val AUTH_SECRET_BYTES = 16

        /** The error code for a registration the bridge will not store. */
        const val INVALID_CODE = "invalid_push_registration"

        /** The error code when the caller is not an active paired device, so there is nothing to attach pushes to. */
        const val NOT_REGISTERED_CODE = "device_not_registered"

        /**
         * Checks a registration and decodes its keys.
         *
         * The endpoint is a URL the bridge will post to, chosen by a paired phone, so it must be `https://` with
         * a host, no user info and a sane length. The keys must be a P-256 point on the curve and a 16-byte secret.
         *
         * @throws SessionException.Invalid with [INVALID_CODE] naming what is wrong, never echoing the value.
         */
        fun validate(registration: PushRegistration): StoredPush {
            val endpoint = registration.endpoint
            if (endpoint.length > MAX_ENDPOINT_LENGTH || !isSafeEndpoint(endpoint)) {
                invalid("The push endpoint must be an https URL with a host and no user name or password.")
            }
            val p256dh = decode(registration.p256dh, "p256dh")
            if (p256dh.size != P256_PUBLIC_KEY_BYTES || !WebPushEncryptor.isP256Point(p256dh)) {
                invalid("p256dh must be an uncompressed P-256 public key of $P256_PUBLIC_KEY_BYTES bytes.")
            }
            val auth = decode(registration.auth, "auth")
            if (auth.size != AUTH_SECRET_BYTES) invalid("auth must be $AUTH_SECRET_BYTES bytes.")
            return StoredPush(endpoint, p256dh, auth)
        }

        /** True for an absolute `https` URL with a host and no user info. The sender checks this again. */
        fun isSafeEndpoint(endpoint: String): Boolean {
            val uri =
                try {
                    URI(endpoint)
                } catch (_: URISyntaxException) {
                    return false
                }
            return uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrEmpty() &&
                uri.rawUserInfo == null &&
                uri.rawFragment == null
        }

        private fun decode(
            value: String,
            field: String,
        ): ByteArray =
            try {
                // The app sends base64url without padding. A padded value decodes the same.
                Base64.getUrlDecoder().decode(value)
            } catch (_: IllegalArgumentException) {
                invalid("$field must be base64url.")
            }

        private fun invalid(message: String): Nothing = throw SessionException.Invalid(INVALID_CODE, message)
    }
}
