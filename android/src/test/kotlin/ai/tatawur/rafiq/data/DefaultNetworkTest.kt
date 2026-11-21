package ai.tatawur.rafiq.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultNetworkTest {
    @Test
    fun a_late_loss_of_the_old_network_after_a_handover_keeps_the_network_usable() {
        val network = DefaultNetwork(initial = "wifi", initialUsable = true)

        network.onCapabilities("cellular", hasInternet = true)
        network.onLost("wifi")

        assertTrue(network.usable.value)
    }

    @Test
    fun losing_the_current_default_network_makes_it_unusable() {
        val network = DefaultNetwork(initial = "wifi", initialUsable = true)

        network.onLost("wifi")

        assertFalse(network.usable.value)
    }
}
