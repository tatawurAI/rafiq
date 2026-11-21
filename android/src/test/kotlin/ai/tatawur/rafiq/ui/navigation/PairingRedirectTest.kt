package ai.tatawur.rafiq.ui.navigation

import ai.tatawur.rafiq.client.ConnectionState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PairingRedirectTest {
    // A cold start can pick Pairing from an early NotPaired before the stored pairing is read.
    @Test
    fun a_paired_connection_leaves_the_pairing_screen() {
        listOf(
            ConnectionState.Connecting,
            ConnectionState.Live,
            ConnectionState.Offline(retryAtMs = null),
            ConnectionState.Unauthorized,
        ).forEach { connection ->
            assertEquals(Routes.SESSIONS, pairingRedirect(connection, Routes.PAIRING), "$connection")
        }
    }

    @Test
    fun losing_the_pairing_returns_to_the_pairing_screen_from_anywhere() {
        listOf(Routes.SESSIONS, Routes.NEW_SESSION, Routes.SESSION).forEach { route ->
            assertEquals(Routes.PAIRING, pairingRedirect(ConnectionState.NotPaired, route), route)
        }
    }

    @Test
    fun a_screen_that_fits_the_pairing_stays() {
        assertNull(pairingRedirect(ConnectionState.NotPaired, Routes.PAIRING))
        assertNull(pairingRedirect(ConnectionState.Live, Routes.SESSION))
        assertNull(pairingRedirect(ConnectionState.Unauthorized, Routes.SESSIONS))
    }
}
