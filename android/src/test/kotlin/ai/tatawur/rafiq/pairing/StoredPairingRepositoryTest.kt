package ai.tatawur.rafiq.pairing

import ai.tatawur.rafiq.client.sync.EventStore
import ai.tatawur.rafiq.client.sync.PairingProbe
import ai.tatawur.rafiq.protocol.Event
import ai.tatawur.rafiq.protocol.api.SessionSummary
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import org.junit.jupiter.api.Test
import java.io.IOException
import java.security.ProviderException
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A broken Keystore or pairing file must send the app to the pairing screen, never into a crash loop. */
class StoredPairingRepositoryTest {
    @Test
    fun a_keystore_that_throws_on_decrypt_starts_unpaired_and_clears_the_stored_pairing() {
        val prefs = FakePrefs(storedPairing())
        val seal = ThrowingSeal()

        val repository = repository(prefs, seal)

        assertNull(repository.server.value)
        assertNull(repository.credentials.value)
        assertEquals(emptyPreferences(), prefs.current.value)
        assertTrue(seal.forgotten)
    }

    @Test
    fun a_pairing_file_that_cannot_be_read_starts_unpaired() {
        val prefs = FakePrefs(storedPairing(), readFails = true)

        val repository = repository(prefs, ThrowingSeal())

        assertNull(repository.server.value)
    }

    private fun repository(
        prefs: DataStore<Preferences>,
        seal: TokenSeal,
    ) = StoredPairingRepository(
        prefs = prefs,
        cipher = seal,
        probe = PairingProbe { throw IOException("This test makes no calls.") },
        cache = NoCache,
        io = Dispatchers.Unconfined,
    )

    private fun storedPairing(): Preferences =
        mutablePreferencesOf(
            stringPreferencesKey("base_url") to "https://bridge.example.ts.net",
            stringPreferencesKey("bridge_version") to "0.2.0",
            stringPreferencesKey("token_ciphertext") to "c2VhbGVk",
        )
}

/** A vendor Keystore that fails the way some phones do: with an unchecked ProviderException. */
private class ThrowingSeal : TokenSeal {
    var forgotten = false

    override fun encrypt(token: String): String = throw ProviderException("Keystore operation failed")

    override fun decrypt(stored: String): String = throw ProviderException("Keystore operation failed")

    override fun forget() {
        forgotten = true
    }
}

private class FakePrefs(
    initial: Preferences,
    private val readFails: Boolean = false,
) : DataStore<Preferences> {
    val current = MutableStateFlow(initial)

    override val data: Flow<Preferences> =
        flow {
            if (readFails) throw IOException("Unable to read the pairing file.")
            emit(current.value)
        }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        transform(current.value).also { current.value = it }
}

private object NoCache : EventStore {
    override suspend fun events(sessionId: String): List<Event> = emptyList()

    override suspend fun append(event: Event) = Unit

    override suspend fun summaries(): List<SessionSummary> = emptyList()

    override suspend fun replaceSummaries(summaries: List<SessionSummary>) = Unit

    override suspend fun clear() = Unit
}
