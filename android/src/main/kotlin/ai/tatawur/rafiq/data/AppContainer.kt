package ai.tatawur.rafiq.data

import ai.tatawur.rafiq.approve.KeystoreApprovalKey
import ai.tatawur.rafiq.client.ApprovalUnlock
import ai.tatawur.rafiq.client.SessionRepository
import ai.tatawur.rafiq.client.pairing.DevicePairingApi
import ai.tatawur.rafiq.client.sync.BridgeApi
import ai.tatawur.rafiq.client.sync.PairingProbe
import ai.tatawur.rafiq.client.sync.SyncingSessionRepository
import ai.tatawur.rafiq.pairing.DevicePairing
import ai.tatawur.rafiq.pairing.OkHttpDeviceRevoker
import ai.tatawur.rafiq.pairing.StoredPairingRepository
import ai.tatawur.rafiq.pairing.TokenCipher
import ai.tatawur.rafiq.protocol.Event
import ai.tatawur.rafiq.push.PushModule
import ai.tatawur.rafiq.sessions.ApprovalGate
import ai.tatawur.rafiq.settings.SecuritySettings
import ai.tatawur.rafiq.terminal.OkHttpTerminalPort
import ai.tatawur.rafiq.terminal.TerminalAccess
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

// A damaged file reads as empty, which means pairing again, instead of failing every launch.
private val Context.pairingStore by preferencesDataStore(
    name = "pairing",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

// A damaged file turns the Terminal lock on (see SecuritySettings), so damage never weakens it.
private val Context.settingsStore by preferencesDataStore(
    name = "settings",
    corruptionHandler = SecuritySettings.corruptionHandler,
)

/**
 * The app's object graph, built once per process. Screens take [sessions] and [pairing] and nothing
 * else, so they can be previewed and tested against fakes.
 *
 * Create it on the main thread: [AndroidAppConditions] observes the process lifecycle.
 */
class AppContainer(
    application: Application,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val restClient: OkHttpClient =
        BridgeClients.rest { token ->
            scope.launch { storedPairing.deviceRevoked(token) }
        }
    private val transport = OkHttpTransport(restClient)
    private val cache =
        RoomEventStore(
            Room
                .databaseBuilder(application, CacheDatabase::class.java, "rafiq-cache.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                .cache(),
        )
    private val storedPairing: StoredPairingRepository =
        StoredPairingRepository(
            prefs = application.pairingStore,
            cipher = TokenCipher(),
            probe = PairingProbe(transport),
            api = DevicePairingApi(transport),
            revoker = OkHttpDeviceRevoker(restClient),
            cache = cache,
            io = Dispatchers.IO,
        )

    /** Pairs the app with your server. */
    val pairing: DevicePairing = storedPairing

    /** Settings that guard what the phone can do on your server. */
    val security = SecuritySettings(application.settingsStore)

    private val conditions = AndroidAppConditions(application)

    /**
     * The optional biometric check before answering an approval, backed by the approval lock's Keystore key
     * (issue #14). A key destroyed by a biometric change turns the lock off and says why.
     */
    val approvals =
        ApprovalGate(
            security.approvalLock,
            ApprovalUnlock(KeystoreApprovalKey()) { security.approvalLockKeyInvalidated() },
            reset = security.approvalLockReset,
            dismissReset = security::dismissApprovalLockReset,
        )

    /** Push through a UnifiedPush distributor, and the notifications it brings (ADR 0010). */
    val push =
        PushModule(
            context = application,
            api = BridgeApi(transport) { storedPairing.credentials.value },
            credentials = storedPairing.credentials,
            conditions = conditions,
            scope = scope,
        )

    /** Opens `WS /terminal` with the same host and token as `WS /events`, behind the Terminal lock. */
    val terminal =
        TerminalAccess(
            port = OkHttpTerminalPort(BridgeClients.sockets(restClient), storedPairing.credentials),
            lock = security.terminalLock,
        )

    /** Sessions from the cache and the paired bridge. */
    val sessions: SessionRepository =
        SyncingSessionRepository(
            http = transport,
            source = OkHttpEventSource(BridgeClients.sockets(restClient)),
            store = cache,
            conditions = conditions,
            credentials = storedPairing.credentials,
            scope = scope,
            nowMs = System::currentTimeMillis,
            onApplied = if (application.isDebuggable()) { event -> logApplied(event) } else { _ -> },
            onRejected = if (application.isDebuggable()) { status -> logRejected(status) } else { _ -> },
        )

    private companion object {
        // The airplane-mode check compares this log with the bridge's: `adb logcat -s RafiqSeq`.
        const val SEQ_TAG = "RafiqSeq"

        const val LINK_TAG = "RafiqLink"

        fun logApplied(event: Event) {
            Log.d(SEQ_TAG, "session=${event.sessionId} seq=${event.seq}")
        }

        fun logRejected(status: Int) {
            Log.d(LINK_TAG, "The bridge refused the socket with HTTP $status: it does not accept this host or origin.")
        }

        fun Context.isDebuggable(): Boolean = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
}
