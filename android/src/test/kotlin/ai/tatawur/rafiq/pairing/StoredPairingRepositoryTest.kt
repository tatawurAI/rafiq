package ai.tatawur.rafiq.pairing

import ai.tatawur.rafiq.client.pairing.CodePairResult
import ai.tatawur.rafiq.client.pairing.DevicePairingApi
import ai.tatawur.rafiq.client.pairing.DeviceRevoker
import ai.tatawur.rafiq.client.pairing.RevokeSelfResult
import ai.tatawur.rafiq.client.sync.EventStore
import ai.tatawur.rafiq.client.sync.HttpResponse
import ai.tatawur.rafiq.client.sync.HttpTransport
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
import kotlinx.coroutines.runBlocking
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

    @Test
    fun a_revoked_answer_for_the_stored_token_unpairs_and_raises_the_notice_but_a_stale_one_is_ignored() =
        runBlocking {
            val prefs = FakePrefs(storedPairing(token = "sealed:rfqd_old"))
            val repository = repository(prefs, PlainSeal)
            assertEquals("rfqd_old", repository.credentials.value?.token)

            // A late answer to a request made with a token this phone no longer holds.
            repository.deviceRevoked("rfqd_older")
            assertEquals("rfqd_old", repository.credentials.value?.token)
            assertEquals(false, repository.revokedNotice.value)

            repository.deviceRevoked("rfqd_old")
            assertNull(repository.server.value)
            assertNull(repository.credentials.value)
            assertEquals(emptyPreferences(), prefs.current.value)
            assertEquals(true, repository.revokedNotice.value)
        }

    @Test
    fun pairing_with_a_code_stores_the_new_token_sealed_and_clears_the_notice() =
        runBlocking {
            val prefs = FakePrefs(emptyPreferences())
            val transport =
                HttpTransport { request ->
                    if (request.url.endsWith("/health")) {
                        HttpResponse(200, """{"status":"ok","version":"0.9.0"}""")
                    } else {
                        HttpResponse(201, """{"device_id":"dev_1","name":"Pixel","token":"rfqd_new"}""")
                    }
                }
            val repository = repository(prefs, PlainSeal, api = DevicePairingApi(transport))

            val result = repository.pairWithCode("https://bridge.example.ts.net", "ABCD-EFGH")

            assertTrue(result is CodePairResult.Paired)
            assertEquals("rfqd_new", repository.credentials.value?.token)
            assertEquals("sealed:rfqd_new", prefs.current.value[stringPreferencesKey("token_ciphertext")])
            assertEquals(false, repository.revokedNotice.value)
        }

    @Test
    fun unpair_tells_the_bridge_first_and_clears_locally_even_when_it_cannot() =
        runBlocking {
            val calls = mutableListOf<String>()
            val prefs = FakePrefs(storedPairing(token = "sealed:rfqd_old"))
            val reached =
                repository(prefs, PlainSeal, revoker = { url, token ->
                    calls += "$url $token"
                    RevokeSelfResult.GONE
                })

            reached.unpair()

            assertEquals(listOf("https://bridge.example.ts.net rfqd_old"), calls)
            assertNull(reached.server.value)
            assertNull(reached.unpairNotice.value)

            val offline = FakePrefs(storedPairing(token = "sealed:rfqd_old"))
            val cannot = repository(offline, PlainSeal, revoker = { _, _ -> RevokeSelfResult.UNCONFIRMED })

            cannot.unpair()

            assertNull(cannot.server.value)
            assertNull(cannot.credentials.value)
            assertEquals(emptyPreferences(), offline.current.value)
            assertEquals(UnpairNotice(deviceId = null), cannot.unpairNotice.value)
        }

    private fun repository(
        prefs: DataStore<Preferences>,
        seal: TokenSeal,
        api: DevicePairingApi = DevicePairingApi { throw IOException("This test makes no calls.") },
        revoker: DeviceRevoker = DeviceRevoker { _, _ -> throw IOException("This test makes no calls.") },
    ) = StoredPairingRepository(
        prefs = prefs,
        cipher = seal,
        probe = PairingProbe { throw IOException("This test makes no calls.") },
        api = api,
        revoker = revoker,
        cache = NoCache,
        io = Dispatchers.Unconfined,
    )

    private fun storedPairing(token: String = "c2VhbGVk"): Preferences =
        mutablePreferencesOf(
            stringPreferencesKey("base_url") to "https://bridge.example.ts.net",
            stringPreferencesKey("bridge_version") to "0.2.0",
            stringPreferencesKey("token_ciphertext") to token,
        )
}

/** A seal that only tags the token, so a test can see what was stored. */
private object PlainSeal : TokenSeal {
    override fun encrypt(token: String): String = "sealed:$token"

    override fun decrypt(stored: String): String? =
        stored.removePrefix("sealed:").takeIf { stored.startsWith("sealed:") }

    override fun forget() = Unit
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
