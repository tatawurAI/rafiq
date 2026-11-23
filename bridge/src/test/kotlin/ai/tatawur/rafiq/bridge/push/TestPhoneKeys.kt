package ai.tatawur.rafiq.bridge.push

import ai.tatawur.rafiq.protocol.api.PushRegistration
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A phone's side of Web Push: its key pair and auth secret, made fresh for each test, and a decryptor. */
class TestPhoneKeys {
    val keys: KeyPair =
        KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
    val auth: ByteArray = ByteArray(16).also(SecureRandom()::nextBytes)
    val publicBytes: ByteArray = WebPushEncryptor.encodePoint(keys.public as ECPublicKey)

    fun registration(endpoint: String) = PushRegistration(endpoint, b64(publicBytes), b64(auth))

    /** Decrypts one `aes128gcm` record as the phone would (RFC 8291, RFC 8188), written apart from the bridge. */
    fun decrypt(body: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(body)
        val salt = ByteArray(16).also(buffer::get)
        buffer.int // record size
        val keyId = ByteArray(buffer.get().toInt()).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        val secret =
            KeyAgreement.getInstance("ECDH").run {
                init(keys.private)
                doPhase(WebPushEncryptor.publicKey(keyId), true)
                generateSecret()
            }
        val ikm = hmac(hmac(auth, secret), "WebPush: info".toByteArray() + 0 + publicBytes + keyId + 1)
        val prk = hmac(salt, ikm)
        val cek = hmac(prk, "Content-Encoding: aes128gcm".toByteArray() + 0 + 1).copyOf(16)
        val nonce = hmac(prk, "Content-Encoding: nonce".toByteArray() + 0 + 1).copyOf(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
        val padded = cipher.doFinal(ciphertext)
        val delimiter = padded.indexOfLast { it != 0.toByte() }
        check(padded[delimiter] == 2.toByte()) { "The last record must end with the 0x02 delimiter." }
        return padded.copyOf(delimiter)
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

fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

fun unb64(text: String): ByteArray = Base64.getUrlDecoder().decode(text.filterNot(Char::isWhitespace))
