package ai.tatawur.rafiq.bridge.push

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Web Push message encryption (RFC 8291) in the `aes128gcm` content coding (RFC 8188), with JDK crypto only.
 *
 * Each message gets a fresh P-256 key pair and a random 16-byte salt, so the push service sees neither the
 * payload nor anything that links two messages beyond their endpoint. The output is one record: the
 * 86-byte header (salt, record size, key id) followed by the ciphertext and its 16-byte tag.
 */
class WebPushEncryptor(
    private val random: SecureRandom = SecureRandom(),
) {
    /**
     * Encrypts [plaintext] for the phone whose public key is [uaPublic] (65 bytes, uncompressed) and whose
     * authentication secret is [authSecret] (16 bytes).
     *
     * @throws IllegalArgumentException when the plaintext does not fit one record.
     */
    fun encrypt(
        plaintext: ByteArray,
        uaPublic: ByteArray,
        authSecret: ByteArray,
    ): ByteArray {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(CURVE), random)
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        return encrypt(plaintext, uaPublic, authSecret, generator.generateKeyPair(), salt, PADDED_BYTES)
    }

    /**
     * The deterministic core, with the sender's key pair and the salt passed in. Tests feed it RFC 8291's values.
     *
     * @param paddedBytes the record before encryption (the plaintext, the 0x02 delimiter and zero padding after it,
     *   RFC 8188 section 2) is padded up to a multiple of this, and never past what one record holds. Zero means
     *   no padding. A payload never fails for being longer than one step.
     */
    internal fun encrypt(
        plaintext: ByteArray,
        uaPublic: ByteArray,
        authSecret: ByteArray,
        asKeys: KeyPair,
        salt: ByteArray,
        paddedBytes: Int = 0,
    ): ByteArray {
        require(plaintext.size + 1 + TAG_BYTES <= RECORD_SIZE - HEADER_BYTES) { "The push payload is too large." }
        val asPublic = encodePoint(asKeys.public as ECPublicKey)
        val ecdhSecret =
            KeyAgreement.getInstance("ECDH").run {
                init(asKeys.private)
                doPhase(publicKey(uaPublic), true)
                generateSecret()
            }
        // RFC 8291 section 3.4: combine the ECDH secret with the auth secret, bound to both public keys.
        val prkKey = hmac(authSecret, ecdhSecret)
        val keyInfo = "WebPush: info".toByteArray() + 0 + uaPublic + asPublic
        val ikm = hmac(prkKey, keyInfo + 1)
        // RFC 8188 section 2.2 and 2.3: the content key and the nonce, from the salt.
        val prk = hmac(salt, ikm)
        val cek = hmac(prk, "Content-Encoding: aes128gcm".toByteArray() + 0 + 1).copyOf(CEK_BYTES)
        val nonce = hmac(prk, "Content-Encoding: nonce".toByteArray() + 0 + 1).copyOf(NONCE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(TAG_BYTES * Byte.SIZE_BITS, nonce))
        // 0x02 marks the last record. Zeros after it pad every payload kind to one size, so the push service cannot
        // tell an approval from a finished turn by its length.
        val record = (plaintext + LAST_RECORD).copyOf(paddedLength(plaintext.size + 1, paddedBytes))
        val ciphertext = cipher.doFinal(record)
        return ByteBuffer
            .allocate(HEADER_BYTES + ciphertext.size)
            .put(salt)
            .putInt(RECORD_SIZE)
            .put(asPublic.size.toByte())
            .put(asPublic)
            .put(ciphertext)
            .array()
    }

    companion object {
        private const val CURVE = "secp256r1"
        private const val SALT_BYTES = 16
        private const val CEK_BYTES = 16
        private const val NONCE_BYTES = 12
        private const val TAG_BYTES = 16
        private const val COORDINATE_BYTES = 32
        private const val UNCOMPRESSED: Byte = 0x04
        private const val LAST_RECORD: Byte = 0x02

        /** Every push's record before encryption is a multiple of this long, whatever its kind. */
        const val PADDED_BYTES = 256

        /** The record size written in the header. Push services accept at least 4096 bytes. */
        const val RECORD_SIZE = 4096

        /** Salt, record size, key id length and a 65-byte key id. */
        const val HEADER_BYTES = SALT_BYTES + Int.SIZE_BYTES + 1 + 65

        /** The most a record can hold before encryption: the record size less the header and the tag. */
        private const val MAX_RECORD_BYTES = RECORD_SIZE - HEADER_BYTES - TAG_BYTES

        /** [unpadded] rounded up to a multiple of [step], capped at [MAX_RECORD_BYTES] but never below [unpadded]. */
        private fun paddedLength(
            unpadded: Int,
            step: Int,
        ): Int {
            if (step == 0) return unpadded
            val rounded = (unpadded + step - 1) / step * step
            return maxOf(unpadded, minOf(rounded, MAX_RECORD_BYTES))
        }

        /** The P-256 curve parameters, from the JDK. */
        val P256: ECParameterSpec =
            AlgorithmParameters
                .getInstance("EC")
                .apply { init(ECGenParameterSpec(CURVE)) }
                .getParameterSpec(ECParameterSpec::class.java)

        /** True when [encoded] is an uncompressed point that lies on P-256. */
        fun isP256Point(encoded: ByteArray): Boolean {
            if (encoded.size != 1 + 2 * COORDINATE_BYTES || encoded[0] != UNCOMPRESSED) return false
            val point = decodePoint(encoded)
            val p = (P256.curve.field as ECFieldFp).p
            val x = point.affineX
            val y = point.affineY
            // y^2 = x^3 + ax + b (mod p), with both coordinates reduced.
            val right =
                x
                    .multiply(x)
                    .multiply(x)
                    .add(P256.curve.a.multiply(x))
                    .add(P256.curve.b)
                    .mod(p)
            return x < p && y < p && y.multiply(y).mod(p) == right
        }

        /** The JDK key for an uncompressed P-256 point. */
        fun publicKey(encoded: ByteArray): ECPublicKey =
            KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(decodePoint(encoded), P256)) as ECPublicKey

        /** The 65-byte uncompressed encoding of [key]. */
        fun encodePoint(key: ECPublicKey): ByteArray =
            byteArrayOf(UNCOMPRESSED) + fixed(key.w.affineX) + fixed(key.w.affineY)

        private fun decodePoint(encoded: ByteArray): ECPoint =
            ECPoint(
                BigInteger(1, encoded.copyOfRange(1, 1 + COORDINATE_BYTES)),
                BigInteger(1, encoded.copyOfRange(1 + COORDINATE_BYTES, encoded.size)),
            )

        // BigInteger drops leading zeros and may add a sign byte. A coordinate is always 32 bytes on the wire.
        private fun fixed(value: BigInteger): ByteArray {
            val raw = value.toByteArray().takeLast(COORDINATE_BYTES).toByteArray()
            return ByteArray(COORDINATE_BYTES - raw.size) + raw
        }

        private fun hmac(
            key: ByteArray,
            data: ByteArray,
        ): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(data)
            }

        private operator fun ByteArray.plus(byte: Int): ByteArray = this + byte.toByte()
    }
}
