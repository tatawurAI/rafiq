package ai.tatawur.rafiq.bridge.server

import ai.tatawur.rafiq.bridge.store.RevokeOutcome
import ai.tatawur.rafiq.protocol.protocolJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** One line of JSON from the `rafiq-bridge` command to the running bridge. [op] is `pair`, `devices` or `revoke`. */
@Serializable
data class AdminRequest(
    @SerialName("op") val op: String,
    @SerialName("name") val name: String? = null,
    @SerialName("id") val id: String? = null,
)

/** One device as `rafiq-bridge devices` lists it. A null time means never or not revoked. */
@Serializable
data class AdminDevice(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("created_at_ms") val createdAtMs: Long,
    @SerialName("last_seen_ms") val lastSeenMs: Long? = null,
    @SerialName("revoked_at_ms") val revokedAtMs: Long? = null,
)

/** The bridge's one-line answer. On failure [ok] is false and [error] says why, in a sentence. */
@Serializable
data class AdminResponse(
    @SerialName("ok") val ok: Boolean,
    @SerialName("error") val error: String? = null,
    @SerialName("code") val code: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("expires_at_ms") val expiresAtMs: Long? = null,
    @SerialName("url") val url: String? = null,
    @SerialName("devices") val devices: List<AdminDevice>? = null,
    @SerialName("closed") val closed: Boolean? = null,
) {
    // Holds a pairing code, so it must never print one.
    override fun toString(): String = "AdminResponse(ok=$ok, error=$error)"
}

/**
 * The owner's way to pair and revoke: a Unix socket inside the bridge's data folder, never the network
 * port. Reaching it needs the folder, which is created owner-only (mode 0700) and refused if anyone else
 * owns it or it is looser. The socket file is 0600. Java has no portable peer-credential check, so the
 * folder is the access control, the same as for the database next to it.
 *
 * Each connection carries one JSON line each way.
 */
class DeviceAdminServer(
    private val socketPath: Path,
    private val devices: DeviceService,
    private val bridgeUrl: String?,
    private val scope: CoroutineScope,
) : AutoCloseable {
    private var channel: ServerSocketChannel? = null
    private var acceptor: Job? = null

    /**
     * Binds the socket and starts answering.
     *
     * @throws IllegalStateException if the folder is unsafe or another bridge holds the socket.
     */
    fun start(): DeviceAdminServer {
        prepareFolder(socketPath.parent)
        if (Files.exists(socketPath)) {
            check(!isAnswering(socketPath)) { "Another bridge is already answering on $socketPath." }
            Files.delete(socketPath)
        }
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(UnixDomainSocketAddress.of(socketPath))
        if (posix(socketPath)) Files.setPosixFilePermissions(socketPath, PosixFilePermissions.fromString("rw-------"))
        channel = server
        acceptor =
            scope.launch(Dispatchers.IO) {
                while (isActive) {
                    val client =
                        try {
                            server.accept()
                        } catch (_: ClosedChannelException) {
                            break
                        }
                    launch { serve(client) }
                }
            }
        return this
    }

    @Suppress("TooGenericExceptionCaught") // One bad connection must not stop the next.
    private suspend fun serve(client: SocketChannel) {
        client.use {
            val watchdog = scope.launch { delay(CONNECTION_LIMIT_MS).also { client.close() } }
            try {
                val line = readLine(client) ?: return
                val response =
                    try {
                        handle(protocolJson.decodeFromString(AdminRequest.serializer(), line))
                    } catch (_: IllegalArgumentException) {
                        AdminResponse(ok = false, error = "Not a request this bridge understands.")
                    }
                val bytes = (protocolJson.encodeToString(AdminResponse.serializer(), response) + "\n").toByteArray()
                client.write(ByteBuffer.wrap(bytes))
            } catch (_: IOException) {
                // The command went away. There is nobody to tell.
            } finally {
                watchdog.cancel()
            }
        }
    }

    private suspend fun handle(request: AdminRequest): AdminResponse =
        when (request.op) {
            "pair" -> {
                val name = request.name?.trim().orEmpty()
                if (name.isEmpty() || name.length > MAX_NAME || name.any(Char::isISOControl)) {
                    AdminResponse(ok = false, error = "Give the device a name of 1 to $MAX_NAME characters.")
                } else {
                    val issued = devices.issueCode(name)
                    AdminResponse(
                        ok = true,
                        code = issued.code,
                        name = name,
                        expiresAtMs = issued.expiresAtMs,
                        url = bridgeUrl,
                    )
                }
            }

            "devices" -> {
                AdminResponse(
                    ok = true,
                    devices =
                        devices.devices().map {
                            AdminDevice(it.id, it.name, it.createdAtMs, it.lastSeenMs, it.revokedAtMs)
                        },
                )
            }

            "revoke" -> {
                when (devices.revoke(request.id.orEmpty())) {
                    RevokeOutcome.REVOKED -> {
                        AdminResponse(ok = true, closed = true)
                    }

                    RevokeOutcome.ALREADY_REVOKED -> {
                        AdminResponse(ok = true, closed = false)
                    }

                    RevokeOutcome.NOT_FOUND -> {
                        AdminResponse(
                            ok = false,
                            error = NO_SUCH_DEVICE,
                        )
                    }
                }
            }

            else -> {
                AdminResponse(ok = false, error = "Unknown command.")
            }
        }

    override fun close() {
        channel?.close()
        acceptor?.cancel()
        Files.deleteIfExists(socketPath)
    }

    companion object {
        private const val NO_SUCH_DEVICE = "No device has that id. Run rafiq-bridge devices."
        private const val MAX_NAME = 60
        private const val MAX_LINE = 4096
        private const val CONNECTION_LIMIT_MS = 10_000L

        /** Where the socket lives for a bridge with [dataDir]: a folder of its own, so its mode is its own. */
        fun socketPathFor(dataDir: Path): Path = dataDir.resolve("admin").resolve("ctl.sock")

        /**
         * The address to print for `pair`: the first allowed host that is not the bind address or localhost,
         * as `https://`, because that is what `tailscale serve` publishes. Null when there is none.
         */
        fun urlFor(
            bindAddress: String,
            allowedHosts: List<String>,
        ): String? =
            allowedHosts
                .firstOrNull {
                    !it.equals(
                        bindAddress,
                        ignoreCase = true,
                    ) && !it.equals("localhost", ignoreCase = true)
                }?.let { "https://$it" }

        /**
         * Creates [folder] as 0700, or checks an existing one: not a symbolic link, owned by this user, mode
         * exactly 0700.
         */
        internal fun prepareFolder(folder: Path) {
            if (!posix(folder.parent ?: folder)) {
                Files.createDirectories(folder)
                return
            }
            if (!Files.exists(folder)) {
                Files.createDirectories(
                    folder,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
                )
            }
            check(!Files.isSymbolicLink(folder)) { "$folder must not be a symbolic link." }
            val owner = Files.getOwner(folder).name
            val me = System.getProperty("user.name")
            check(owner == me) { "$folder is owned by $owner, not $me. Remove it and restart the bridge." }
            val mode = PosixFilePermissions.toString(Files.getPosixFilePermissions(folder))
            check(mode == "rwx------") { "$folder has mode $mode. It must be rwx------ (chmod 700)." }
        }

        private fun posix(path: Path) = "posix" in path.fileSystem.supportedFileAttributeViews()

        private fun isAnswering(path: Path): Boolean =
            try {
                SocketChannel.open(StandardProtocolFamily.UNIX).use { it.connect(UnixDomainSocketAddress.of(path)) }
                true
            } catch (_: IOException) {
                false
            }

        internal fun readLine(channel: SocketChannel): String? {
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteBuffer.allocate(MAX_LINE)
            while (bytes.size() < MAX_LINE) {
                buffer.clear()
                if (channel.read(buffer) < 0) break
                buffer.flip()
                while (buffer.hasRemaining()) {
                    val next = buffer.get()
                    if (next == '\n'.code.toByte()) return bytes.toString(Charsets.UTF_8)
                    bytes.write(next.toInt())
                }
            }
            return null
        }
    }
}
