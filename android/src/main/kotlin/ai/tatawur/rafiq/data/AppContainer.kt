package ai.tatawur.rafiq.data

import ai.tatawur.rafiq.client.PairingRepository
import ai.tatawur.rafiq.client.SessionRepository
import ai.tatawur.rafiq.client.sync.PairingProbe
import ai.tatawur.rafiq.client.sync.SyncingSessionRepository
import ai.tatawur.rafiq.pairing.StoredPairingRepository
import ai.tatawur.rafiq.pairing.TokenCipher
import ai.tatawur.rafiq.protocol.Event
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

private val Context.pairingStore by preferencesDataStore(name = "pairing")

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
    private val restClient = BridgeClients.rest()
    private val transport = OkHttpTransport(restClient)
    private val cache =
        RoomEventStore(
            Room
                .databaseBuilder(application, CacheDatabase::class.java, "rafiq-cache.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                .cache(),
        )
    private val storedPairing =
        StoredPairingRepository(
            prefs = application.pairingStore,
            cipher = TokenCipher(),
            probe = PairingProbe(transport),
            cache = cache,
            io = Dispatchers.IO,
        )

    /** Pairs the app with your server. */
    val pairing: PairingRepository = storedPairing

    /** Sessions from the cache and the paired bridge. */
    val sessions: SessionRepository =
        SyncingSessionRepository(
            http = transport,
            source = OkHttpEventSource(BridgeClients.sockets(restClient)),
            store = cache,
            conditions = AndroidAppConditions(application),
            credentials = storedPairing.credentials,
            scope = scope,
            nowMs = System::currentTimeMillis,
            onApplied = if (application.isDebuggable()) { event -> logApplied(event) } else { _ -> },
        )

    private companion object {
        // The airplane-mode check compares this log with the bridge's: `adb logcat -s RafiqSeq`.
        const val SEQ_TAG = "RafiqSeq"

        fun logApplied(event: Event) {
            Log.d(SEQ_TAG, "session=${event.sessionId} seq=${event.seq}")
        }

        fun Context.isDebuggable(): Boolean = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
}
