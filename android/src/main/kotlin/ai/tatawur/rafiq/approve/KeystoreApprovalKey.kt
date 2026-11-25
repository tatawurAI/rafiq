package ai.tatawur.rafiq.approve

import ai.tatawur.rafiq.client.ApprovalKey
import ai.tatawur.rafiq.client.KeyUse
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * The approval lock's key (issue #14): an HMAC key in the Android Keystore that only works within
 * [VALID_FOR_SECONDS] of a strong biometric check, and that Android destroys when a fingerprint or face is
 * added or all are removed. Signing one byte with it is the whole test; the signature is thrown away.
 */
class KeystoreApprovalKey(
    private val alias: String = "rafiq-approval-lock",
) : ApprovalKey {
    // Opened on first use: a vendor Keystore that fails to load must not stop the app from starting.
    private val keyStore by lazy { KeyStore.getInstance(KEYSTORE).apply { load(null) } }

    @Suppress("TooGenericExceptionCaught") // Vendor Keystores throw ProviderException and others too.
    override fun use(): KeyUse =
        try {
            val key = keyStore.getKey(alias, null) as? SecretKey
            if (key == null) {
                KeyUse.MISSING
            } else {
                Mac.getInstance(MAC).apply { init(key) }.doFinal(byteArrayOf(0))
                KeyUse.USABLE
            }
        } catch (failure: Exception) {
            classify(failure)
        }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Any failure means no key, so no lock.
    override fun create(): Boolean =
        try {
            KeyGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE)
                .apply {
                    init(
                        KeyGenParameterSpec
                            .Builder(alias, KeyProperties.PURPOSE_SIGN)
                            .setUserAuthenticationRequired(true)
                            .setUserAuthenticationParameters(VALID_FOR_SECONDS, KeyProperties.AUTH_BIOMETRIC_STRONG)
                            .setInvalidatedByBiometricEnrollment(true)
                            .build(),
                    )
                }.generateKey()
            true
        } catch (_: Exception) {
            // No strong biometric enrolled, or no secure lock screen: Android refuses such a key.
            false
        }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Best effort: a missing entry is the goal.
    override fun delete() {
        try {
            keyStore.deleteEntry(alias)
        } catch (_: Exception) {
            // Nothing to delete, or the Keystore refused; the lock's setting decides either way.
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val MAC = "HmacSHA256"

        /** The decided window: one fingerprint, then 30 seconds to answer (issue #14). */
        const val VALID_FOR_SECONDS = 30

        // Some vendors wrap the Keystore's answer in a ProviderException, so the whole cause chain is read.
        fun classify(failure: Throwable): KeyUse =
            generateSequence(failure) { it.cause }
                .firstNotNullOfOrNull {
                    when (it) {
                        is KeyPermanentlyInvalidatedException -> KeyUse.INVALIDATED
                        is UserNotAuthenticatedException -> KeyUse.NEEDS_CHECK
                        else -> null
                    }
                } ?: KeyUse.BROKEN
    }
}
