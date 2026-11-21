package ai.tatawur.rafiq.pairing

import ai.tatawur.rafiq.client.PairedServer
import ai.tatawur.rafiq.client.PairingRepository
import ai.tatawur.rafiq.client.PairingResult
import ai.tatawur.rafiq.client.sync.BridgeCredentials
import ai.tatawur.rafiq.client.sync.EventStore
import ai.tatawur.rafiq.client.sync.PairingProbe
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * The app's [PairingRepository]. The server URL and bridge version are stored in DataStore in the
 * clear, the token only as [TokenCipher] ciphertext.
 *
 * The stored pairing is read once, when this is created, so the first screen already knows whether the
 * app is paired and never flashes the pairing screen. It is a small file and one Keystore operation.
 * A pairing that cannot be read, for any reason, is cleared, and the app starts on the pairing screen
 * instead of crashing on every launch.
 *
 * @param cache cleared on unpair, and when pairing with a different server.
 */
class StoredPairingRepository(
    private val prefs: DataStore<Preferences>,
    private val cipher: TokenSeal,
    private val probe: PairingProbe,
    private val cache: EventStore,
    private val io: CoroutineDispatcher,
) : PairingRepository {
    private val paired = MutableStateFlow(runBlocking(io) { load() })
    private val currentServer = MutableStateFlow(paired.value?.server)
    private val currentCredentials = MutableStateFlow(paired.value?.credentials)

    override val server: StateFlow<PairedServer?> = currentServer.asStateFlow()

    /** What the session repository connects with, or null when not paired. */
    val credentials: StateFlow<BridgeCredentials?> = currentCredentials.asStateFlow()

    override suspend fun pair(
        baseUrl: String,
        token: String,
    ): PairingResult {
        val result = probe.probe(baseUrl, token)
        if (result is PairingResult.Paired) {
            save(
                Pairing(result.server, BridgeCredentials(result.server.baseUrl, token.trim())),
            )
        }
        return result
    }

    override suspend fun unpair() {
        withContext(io) {
            prefs.edit { it.clear() }
            cipher.forget()
            cache.clear()
        }
        publish(null)
    }

    private suspend fun save(pairing: Pairing) {
        withContext(io) {
            if (paired.value?.server?.baseUrl != pairing.server.baseUrl) cache.clear()
            val sealed = cipher.encrypt(pairing.credentials.token)
            prefs.edit {
                it[BASE_URL] = pairing.server.baseUrl
                it[BRIDGE_VERSION] = pairing.server.bridgeVersion
                it[TOKEN] = sealed
            }
        }
        publish(pairing)
    }

    private fun publish(pairing: Pairing?) {
        paired.value = pairing
        currentServer.value = pairing?.server
        currentCredentials.value = pairing?.credentials
    }

    @Suppress("TooGenericExceptionCaught") // DataStore and vendor Keystores throw more than they document.
    private suspend fun load(): Pairing? =
        try {
            read()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            forgetBroken()
            null
        }

    private suspend fun read(): Pairing? {
        val stored = prefs.data.first()
        val baseUrl = stored[BASE_URL] ?: return null
        // A token that no longer decrypts (its key is gone) means pairing again.
        val token = stored[TOKEN]?.let(cipher::decrypt)
        if (token == null) {
            forgetBroken()
            return null
        }
        return Pairing(PairedServer(baseUrl, stored[BRIDGE_VERSION].orEmpty()), BridgeCredentials(baseUrl, token))
    }

    /** Clears a pairing that cannot be read, so the next launch does not trip over it again. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Best effort: pairing again overwrites it.
    private suspend fun forgetBroken() {
        cipher.forget()
        try {
            prefs.edit { it.clear() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The file cannot be written either. Pairing again replaces it.
        }
    }

    private data class Pairing(
        val server: PairedServer,
        val credentials: BridgeCredentials,
    )

    private companion object {
        val BASE_URL = stringPreferencesKey("base_url")
        val BRIDGE_VERSION = stringPreferencesKey("bridge_version")
        val TOKEN = stringPreferencesKey("token_ciphertext")
    }
}
