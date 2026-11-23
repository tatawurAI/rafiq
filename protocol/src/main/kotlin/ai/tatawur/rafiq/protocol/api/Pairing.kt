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

    /**
     * A 403: the bridge checks your Tailscale login, and this request came from another login, a tagged device or
     * no login at all. The app says so and keeps its token.
     */
    const val IDENTITY_MISMATCH = "identity_mismatch"
}

/**
 * The pairing link a QR code holds: `rafiq://pair?url=<bridge https URL>&code=<pairing code>`. It carries the
 * short-lived, single-use code from `rafiq-bridge pair`, never a device token. The app scans it, or opens it as a
 * link, and then pairs exactly as with a typed code.
 */
object PairingLink {
    private const val PREFIX = "rafiq://pair?"

    /** Builds the link for [baseUrl], an `https://` URL, and [code]. */
    fun build(
        baseUrl: String,
        code: String,
    ): String = PREFIX + "url=" + encode(baseUrl) + "&code=" + encode(code)

    /**
     * Reads a link made by [build]. Returns null for anything else, including a URL that is not `https://`, so a
     * QR code from elsewhere cannot point the app at a plain-HTTP server.
     */
    fun parse(link: String): Parsed? {
        val params =
            link
                .takeIf { it.startsWith(PREFIX) }
                ?.removePrefix(PREFIX)
                ?.split('&')
                ?.mapNotNull { part ->
                    val key = part.substringBefore('=', "")
                    val value = part.substringAfter('=', "")
                    if (key.isEmpty()) null else key to decode(value)
                }?.toMap()
                .orEmpty()
        val url = params["url"]?.takeIf { it.startsWith("https://") && it.length > "https://".length }
        val code = params["code"]?.takeIf { it.isNotBlank() }
        return if (url != null && code != null) Parsed(url.trimEnd('/'), code) else null
    }

    /** A parsed pairing link. */
    data class Parsed(
        val baseUrl: String,
        val code: String,
    ) {
        // The code is a short-lived secret.
        override fun toString(): String = "PairingLink.Parsed(baseUrl=$baseUrl)"
    }

    private fun encode(value: String): String =
        buildString {
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val char = (byte.toInt() and BYTE_MASK).toChar()
                if ((char.isLetterOrDigit() && char.code < ASCII_LIMIT) || char in "-._~:/") {
                    append(char)
                } else {
                    append('%').append("%02X".format(byte.toInt() and BYTE_MASK))
                }
            }
        }

    /** Percent-decodes [value]. Returns null for a broken escape. Plain Kotlin, so it works on every Android level. */
    private fun decode(value: String): String? {
        val bytes = java.io.ByteArrayOutputStream()
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '%') {
                val hex = value.substring(index + 1, minOf(index + ESCAPE_LENGTH, value.length))
                val byte = hex.takeIf { it.length == 2 }?.toIntOrNull(HEX_RADIX) ?: return null
                bytes.write(byte)
                index += ESCAPE_LENGTH
            } else {
                bytes.write(char.toString().toByteArray(Charsets.UTF_8))
                index++
            }
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    private const val BYTE_MASK = 0xFF
    private const val ASCII_LIMIT = 128
    private const val ESCAPE_LENGTH = 3
    private const val HEX_RADIX = 16
}
