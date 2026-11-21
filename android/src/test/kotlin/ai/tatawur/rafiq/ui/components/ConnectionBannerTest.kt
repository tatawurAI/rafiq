package ai.tatawur.rafiq.ui.components

import ai.tatawur.rafiq.client.ConnectionState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ConnectionBannerTest {
    // Pair again unpairs and clears the cache, so it must not appear for states that recover alone.
    @Test
    fun only_a_refused_token_offers_pair_again() {
        val offered =
            listOf(
                ConnectionState.NotPaired,
                ConnectionState.Connecting,
                ConnectionState.Live,
                ConnectionState.Offline(retryAtMs = null),
                ConnectionState.Unauthorized,
            ).filter(::offersPairAgain)

        assertEquals(listOf<ConnectionState>(ConnectionState.Unauthorized), offered)
    }
}
