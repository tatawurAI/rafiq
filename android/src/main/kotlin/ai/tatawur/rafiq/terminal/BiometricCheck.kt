package ai.tatawur.rafiq.terminal

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal

/**
 * The biometric check that guards Terminal: a strong biometric (class 3 fingerprint or face) and
 * nothing else. There is no fallback to the screen lock PIN, so a person who only knows your PIN
 * cannot open Terminal. It fails closed: no enrolled strong biometric means no Terminal while the
 * setting is on.
 */
internal object BiometricCheck {
    /** True when the phone has a strong biometric enrolled that can pass the check. */
    fun available(context: Context): Boolean =
        context.getSystemService(BiometricManager::class.java)?.canAuthenticate(BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Shows the system prompt. [onResult] runs once on the main thread, with true only when a strong
     * biometric matched. Cancelling, a lockout and an error all count as false.
     *
     * @return a signal that dismisses the prompt, for when the screen goes away first.
     */
    fun ask(
        context: Context,
        title: String,
        subtitle: String,
        cancel: String,
        onResult: (Boolean) -> Unit,
    ): CancellationSignal {
        val executor = context.mainExecutor
        var answered = false
        val answer = { passed: Boolean ->
            if (!answered) {
                answered = true
                onResult(passed)
            }
        }
        val prompt =
            BiometricPrompt
                .Builder(context)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                .setNegativeButton(cancel, executor) { _, _ -> answer(false) }
                .build()
        val signal = CancellationSignal()
        prompt.authenticate(
            signal,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = answer(true)

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence,
                ) = answer(false)
            },
        )
        return signal
    }
}
