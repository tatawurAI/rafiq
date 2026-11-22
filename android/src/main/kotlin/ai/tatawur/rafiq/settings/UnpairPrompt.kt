package ai.tatawur.rafiq.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Unpair row's confirmation. Unpairing is permanent: your server forgets this phone and the
 * phone needs a new pairing code. So [unpair] runs only after you ask and then confirm, and only
 * once, however many times the button is tapped.
 *
 * @param unpair starts the unpair. It must outlive the Settings screen, which closes as soon as
 *   the phone is no longer paired.
 */
internal class UnpairPrompt(
    private val unpair: () -> Unit,
) {
    private val showing = MutableStateFlow(false)
    private var sent = false

    /** True while the confirmation shows. */
    val asking: StateFlow<Boolean> = showing.asStateFlow()

    /** You tapped Unpair. */
    fun ask() {
        if (!sent) showing.value = true
    }

    /** You backed out. */
    fun cancel() {
        showing.value = false
    }

    /** You confirmed. */
    fun confirm() {
        if (!showing.value || sent) return
        showing.value = false
        sent = true
        unpair()
    }
}
