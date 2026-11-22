package ai.tatawur.rafiq.pairing

import ai.tatawur.rafiq.client.PairedServer
import ai.tatawur.rafiq.client.PairingRepository
import ai.tatawur.rafiq.client.PairingResult
import ai.tatawur.rafiq.client.pairing.CodePairResult
import ai.tatawur.rafiq.client.pairing.DevicePairingApi
import ai.tatawur.rafiq.client.pairing.DeviceRevoker
import ai.tatawur.rafiq.client.pairing.RevokeSelfResult
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
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The app's [PairingRepository]. The server URL and bridge version are stored in DataStore in the
 * clear, the token only as [TokenCipher] ciphertext.
 *
 * The stored pairing is read once, when this is created, so the first screen already knows whether the
 * app is paired and never flashes the pairing screen. It is a small file and one Keystore operation.
 * A pairing that cannot be read, for any reason, is cleared, and the app starts on the pairing screen
 * instead of crashing on every launch.
 *
 * @param probe pairs with a device token the user already holds, which is how a phone paired before
 *   per-device tokens still works.
 * @param api pairs with a code from `rafiq-bridge pair`.
 * @param revoker tells the bridge to revoke this device when the user unpairs.
 * @param cache cleared on unpair, and when pairing with a different server.
 */
@Suppress("LongParameterList", "TooManyFunctions") // One class owns pairing, its two notices and unpair.
class StoredPairingRepository(
    private val prefs: DataStore<Preferences>,
    private val cipher: TokenSeal,
    private val probe: PairingProbe,
    private val api: DevicePairingApi,
    private val revoker: DeviceRevoker,
    private val cache: EventStore,
    private val io: CoroutineDispatcher,
) : DevicePairing {
    private val paired = MutableStateFlow(runBlocking(io) { load() })
    private val currentServer = MutableStateFlow(paired.value?.server)
    private val currentCredentials = MutableStateFlow(paired.value?.credentials)
    private val revoked = MutableStateFlow(false)
    private val unpairNote = MutableStateFlow<UnpairNotice?>(null)

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

    override suspend fun pairWithCode(
        baseUrl: String,
        code: String,
    ): CodePairResult {
        val result = api.pair(baseUrl, code)
        if (result is CodePairResult.Paired) {
            save(Pairing(result.server, BridgeCredentials(result.server.baseUrl, result.deviceToken), result.deviceId))
            revoked.value = false
            unpairNote.value = null
        }
        return result
    }

    override val revokedNotice: StateFlow<Boolean> = revoked.asStateFlow()

    override fun dismissRevokedNotice() {
        revoked.value = false
    }

    override val unpairNotice: StateFlow<UnpairNotice?> = unpairNote.asStateFlow()

    override fun dismissUnpairNotice() {
        unpairNote.value = null
    }

    /**
     * The bridge answered [usedToken] with `device_revoked`. Clears the token, the cached sessions and the
     * pairing, and raises the notice. Ignored when [usedToken] is no longer the stored token, for example
     * a late answer to a request made before the phone paired again.
     */
    suspend fun deviceRevoked(usedToken: String) {
        if (currentCredentials.value?.token != usedToken) return
        revoked.value = true
        clearLocal()
    }

    /**
     * Asks the bridge to revoke this device first, then clears everything here. If the bridge cannot be
     * reached within a few seconds, it still clears here and raises [unpairNotice], because the device
     * stays paired on your server until you revoke it there.
     */
    override suspend fun unpair() {
        val credentials = currentCredentials.value
        val deviceId = paired.value?.deviceId
        val result =
            credentials?.let {
                withTimeoutOrNull(REVOKE_TIMEOUT_MS) { revoker.revokeSelf(it.baseUrl, it.token) }
            }
        clearLocal()
        if (credentials != null && result != RevokeSelfResult.GONE) unpairNote.value = UnpairNotice(deviceId)
    }

    private suspend fun clearLocal() {
        // Drop the credentials first, so no socket can add events to the cache after it is cleared.
        publish(null)
        withContext(io) {
            prefs.edit { it.clear() }
            cipher.forget()
            cache.clear()
        }
    }

    private suspend fun save(pairing: Pairing) {
        withContext(io) {
            if (paired.value?.server?.baseUrl != pairing.server.baseUrl) cache.clear()
            val sealed = cipher.encrypt(pairing.credentials.token)
            prefs.edit {
                it[BASE_URL] = pairing.server.baseUrl
                it[BRIDGE_VERSION] = pairing.server.bridgeVersion
                it[TOKEN] = sealed
                if (pairing.deviceId != null) it[DEVICE_ID] = pairing.deviceId else it.remove(DEVICE_ID)
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
        if (token == null) forgetBroken()
        return token?.let {
            Pairing(
                PairedServer(baseUrl, stored[BRIDGE_VERSION].orEmpty()),
                BridgeCredentials(baseUrl, it),
                stored[DEVICE_ID],
            )
        }
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
        val deviceId: String? = null,
    )

    private companion object {
        val BASE_URL = stringPreferencesKey("base_url")
        val BRIDGE_VERSION = stringPreferencesKey("bridge_version")
        val TOKEN = stringPreferencesKey("token_ciphertext")
        val DEVICE_ID = stringPreferencesKey("device_id")
        const val REVOKE_TIMEOUT_MS = 5_000L
    }
}
