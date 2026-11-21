package ai.tatawur.rafiq.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the phone's default network can reach the internet, fed from a default-network callback.
 *
 * On a handover, for example from Wi-Fi to cellular, the callback can report the new network before it
 * reports the old one lost. Only the loss of the network that is the default right now clears [usable],
 * so a late loss of the old one cannot leave the app waiting for a network it already has.
 *
 * @param N the platform's network handle. Generic so the rule runs in a JVM test.
 */
class DefaultNetwork<N : Any>(
    initial: N?,
    initialUsable: Boolean,
) {
    private var current: N? = initial
    private val state = MutableStateFlow(initial != null && initialUsable)

    /** True while the default network has internet. */
    val usable: StateFlow<Boolean> = state.asStateFlow()

    /** [network] is the default now, with or without internet. */
    @Synchronized
    fun onCapabilities(
        network: N,
        hasInternet: Boolean,
    ) {
        current = network
        state.value = hasInternet
    }

    /** [network] went away. Ignored unless it is the default right now. */
    @Synchronized
    fun onLost(network: N) {
        if (network != current) return
        current = null
        state.value = false
    }
}
