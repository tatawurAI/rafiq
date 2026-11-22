package ai.tatawur.rafiq.bridge.server

import io.ktor.util.AttributeKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** The device a request authenticated as. Set by the security plugin on every authenticated call. */
data class DevicePrincipal(
    val deviceId: String,
)

/** Attribute key under which the security plugin stores the caller's [DevicePrincipal]. */
val DevicePrincipalKey: AttributeKey<DevicePrincipal> = AttributeKey("rafiq.device.principal")

/** Long-lived connections per device, so that revoking a device closes them at once. */
interface ConnectionRegistry {
    /**
     * Registers an open connection for [deviceId]. [close] is called at most once, if the device is
     * revoked. Close the returned handle when the connection ends for any other reason.
     */
    fun register(
        deviceId: String,
        kind: String,
        close: suspend (reason: String) -> Unit,
    ): AutoCloseable
}

/**
 * The registry the bridge runs with. Connections live in memory only: they cannot outlive the process,
 * so there is nothing to persist.
 *
 * A device is revoked for good, so once [closeAll] has run for it the registry remembers that and closes
 * any connection registered for it afterwards. That covers a handshake that passed the token check just
 * before the revoke and registers just after it.
 *
 * @param scope runs the close of a connection that registers after its device was revoked.
 */
class InMemoryConnectionRegistry(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : ConnectionRegistry {
    private class Entry(
        val kind: String,
        val close: suspend (String) -> Unit,
    )

    private val byDevice = ConcurrentHashMap<String, MutableSet<Entry>>()
    private val closedOut = ConcurrentHashMap<String, String>()

    override fun register(
        deviceId: String,
        kind: String,
        close: suspend (reason: String) -> Unit,
    ): AutoCloseable {
        val entry = Entry(kind, close)
        byDevice.computeIfAbsent(deviceId) { ConcurrentHashMap.newKeySet() }.add(entry)
        // Checked after adding: either closeAll sees this entry, or this sees closeAll's mark.
        closedOut[deviceId]?.let { reason ->
            if (byDevice[deviceId]?.remove(entry) == true) scope.launch { runClose(entry, reason) }
        }
        return AutoCloseable { byDevice[deviceId]?.remove(entry) }
    }

    /** How many connections [deviceId] holds open now. */
    fun openCount(deviceId: String): Int = byDevice[deviceId]?.size ?: 0

    /**
     * Closes every connection of [deviceId] with [reason] and forgets them. Each close callback runs at
     * most once, and one that throws does not stop the others. Connections registered later are closed too.
     *
     * @return how many connections were closed.
     */
    suspend fun closeAll(
        deviceId: String,
        reason: String,
    ): Int {
        closedOut[deviceId] = reason
        val entries = byDevice[deviceId]?.toList().orEmpty().filter { byDevice[deviceId]?.remove(it) == true }
        entries.forEach { runClose(it, reason) }
        return entries.size
    }

    @Suppress("TooGenericExceptionCaught") // A failing close must not keep another socket open.
    private suspend fun runClose(
        entry: Entry,
        reason: String,
    ) {
        try {
            entry.close(reason)
        } catch (_: Exception) {
            // The socket is already gone or going. Revocation still counts.
        }
    }
}
