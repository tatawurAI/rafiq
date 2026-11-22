package ai.tatawur.rafiq.bridge.server

import io.ktor.util.AttributeKey
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
 */
class InMemoryConnectionRegistry : ConnectionRegistry {
    private class Entry(
        val kind: String,
        val close: suspend (String) -> Unit,
    )

    private val byDevice = ConcurrentHashMap<String, MutableSet<Entry>>()

    override fun register(
        deviceId: String,
        kind: String,
        close: suspend (reason: String) -> Unit,
    ): AutoCloseable {
        val entry = Entry(kind, close)
        byDevice.computeIfAbsent(deviceId) { ConcurrentHashMap.newKeySet() }.add(entry)
        return AutoCloseable { byDevice[deviceId]?.remove(entry) }
    }

    /** How many connections [deviceId] holds open now. */
    fun openCount(deviceId: String): Int = byDevice[deviceId]?.size ?: 0

    /**
     * Closes every connection of [deviceId] with [reason] and forgets them. Each close callback runs at
     * most once, and one that throws does not stop the others.
     *
     * @return how many connections were closed.
     */
    @Suppress("TooGenericExceptionCaught") // A failing close must not keep another socket open.
    suspend fun closeAll(
        deviceId: String,
        reason: String,
    ): Int {
        val entries = byDevice.remove(deviceId)?.toList().orEmpty()
        entries.forEach { entry ->
            try {
                entry.close(reason)
            } catch (_: Exception) {
                // The socket is already gone or going. Revocation still counts.
            }
        }
        return entries.size
    }
}
