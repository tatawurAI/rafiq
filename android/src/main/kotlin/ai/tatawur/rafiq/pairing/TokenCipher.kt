package ai.tatawur.rafiq.pairing

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals the device token for storage. [TokenCipher] is the app's, and tests use a fake. */
interface TokenSeal {
    /** Returns [token] sealed as one string, safe to store in the clear. */
    fun encrypt(token: String): String

    /** Returns the token, or null when [stored] is damaged or the key is gone. */
    fun decrypt(stored: String): String?

    /** Deletes the key, which makes every stored ciphertext useless. Never throws. */
    fun forget()
}

/**
 * Encrypts the device token with AES-GCM under a key that lives in the Android Keystore and never
 * leaves it. Only the ciphertext is stored, in DataStore. No user authentication is required, because
 * the app reconnects on its own whenever it comes to the foreground.
 */
class TokenCipher(
    private val alias: String = "rafiq-device-token",
) : TokenSeal {
    // Opened on first use, not in the constructor: a vendor Keystore that fails to load must not stop the
    // app from starting. The failure then surfaces where the caller already handles one.
    private val keyStore by lazy { KeyStore.getInstance(KEYSTORE).apply { load(null) } }

    /** Returns the IV and ciphertext of [token], Base64 encoded, as one string. */
    override fun encrypt(token: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The Keystore picks a fresh random IV for every encryption.
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(sealed)
    }

    @Suppress("TooGenericExceptionCaught") // Vendor Keystores also throw ProviderException and others.
    override fun decrypt(stored: String): String? =
        try {
            val sealed = Base64.getDecoder().decode(stored)
            val key = keyStore.getKey(alias, null) as? SecretKey
            key?.let {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, it, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
                String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.UTF_8)
            }
        } catch (_: IllegalArgumentException) {
            // Not Base64.
            null
        } catch (_: Exception) {
            // A GeneralSecurityException, or a ProviderException from a vendor Keystore. Either way the
            // token is lost and the app pairs again.
            null
        }

    @Suppress("TooGenericExceptionCaught") // It runs while recovering from a broken Keystore.
    override fun forget() {
        try {
            keyStore.deleteEntry(alias)
        } catch (_: Exception) {
            // Nothing to delete, or the Keystore refused. Either way the stored ciphertext is cleared too.
        }
    }

    private fun key(): SecretKey =
        keyStore.getKey(alias, null) as? SecretKey
            ?: KeyGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
                .apply {
                    init(
                        KeyGenParameterSpec
                            .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(KEY_BITS)
                            .build(),
                    )
                }.generateKey()

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
        const val IV_BYTES = 12
    }
}
