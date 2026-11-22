package ai.tatawur.rafiq.bridge.hook

import ai.tatawur.rafiq.bridge.server.DeviceAdminServer
import ai.tatawur.rafiq.protocol.protocolJson
import jdk.net.ExtendedSocketOptions
import jdk.net.UnixDomainPrincipal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The bridge's end of the hook channel (ADR 0013 guard 5, issue #48): a Unix socket at
 * `$RAFIQ_DATA_DIR/hook/hook.sock` that the hook program talks to in the wire format of `HookContract.kt`.
 *
 * Who can reach it is decided by the file system. The `hook` folder is owner-only (0700, checked like the admin
 * socket's folder), and the socket file is 0600, so another OS user cannot connect. Where the JDK reports the
 * peer's user (Linux and macOS), a peer that is not the bridge's user is also refused. Every request must carry
 * the per-start credential from `hook/credential`, compared in constant time. It is regenerated at every start
 * and never logged, so a hook process left over from an earlier run cannot speak for the new one.
 *
 * A call that fails any check, is malformed or is too big is closed with no reply, which the hook program
 * treats as "ask at the desk". Nothing in a rejected request is logged.
 *
 * @param dataDir the bridge's data folder.
 * @param sink where accepted calls go.
 * @param requestTimeout how long a connection may take to send its request line.
 */
class HookChannel(
    private val dataDir: Path,
    private val sink: HookSink,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    private val requestTimeout: Duration = REQUEST_TIMEOUT,
    private val random: SecureRandom = SecureRandom(),
) : AutoCloseable {
    private var server: ServerSocketChannel? = null
    private var acceptor: Job? = null
    private var credential: Credential? = null
    private val open = ConcurrentHashMap.newKeySet<SocketChannel>()
    private val connections = AtomicInteger()

    /** The socket file. */
    val socketPath: Path = socketPathFor(dataDir)

    /** The credential file the hook program reads. */
    val credentialPath: Path = credentialPathFor(dataDir)

    /**
     * Writes a new credential, binds the socket and starts answering.
     *
     * @throws IllegalStateException if the folder is unsafe, the path is too long, or another bridge holds it.
     */
    fun start(): HookChannel {
        DeviceAdminServer.checkPathLength(socketPath)
        DeviceAdminServer.prepareFolder(socketPath.parent)
        // Checked before anything is written, so a second bridge on the same folder leaves the first one's
        // credential alone.
        if (Files.exists(socketPath)) {
            check(!isAnswering(socketPath)) { "Another bridge is already answering on $socketPath." }
            Files.delete(socketPath)
        }
        credential = writeCredential()
        val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        channel.bind(UnixDomainSocketAddress.of(socketPath))
        if (posix(socketPath)) Files.setPosixFilePermissions(socketPath, OWNER_ONLY_FILE)
        server = channel
        acceptor =
            scope.launch(Dispatchers.IO) {
                // Supervised, so a failure in one connection never stops the acceptor.
                supervisorScope {
                    while (isActive) {
                        val client =
                            try {
                                channel.accept()
                            } catch (_: ClosedChannelException) {
                                break
                            }
                        if (connections.incrementAndGet() > MAX_CONNECTIONS) {
                            connections.decrementAndGet()
                            runCatching { client.close() }
                        } else {
                            open += client
                            launch {
                                try {
                                    serve(client)
                                } finally {
                                    open -= client
                                    connections.decrementAndGet()
                                    runCatching { client.close() }
                                }
                            }
                        }
                    }
                }
            }
        return this
    }

    @Suppress("TooGenericExceptionCaught") // A failing sink or a broken connection must not stop the next call.
    private suspend fun serve(client: SocketChannel) {
        try {
            // Interruptible, so a peer that never finishes its line is cut off at the timeout.
            val line =
                if (peerIsOwner(client)) {
                    withTimeoutOrNull(requestTimeout) { runInterruptible { readRequestLine(client) } }
                } else {
                    null
                }
            val envelope = line?.let(::accept) ?: return
            when (envelope.second) {
                HookKind.EVENT -> sink.onEvent(envelope.first)
                HookKind.PERMISSION -> answerPermission(client, envelope.first)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            // The hook went away or the channel closed. There is nobody to answer.
        } catch (failure: Exception) {
            // Only the type: the request may hold prompts or file contents.
            log.warn("A hook call failed: {}", failure.javaClass.simpleName)
        }
    }

    /** Parses and checks a request line. Null for anything that must be closed without a reply. */
    private fun accept(line: String): Pair<HookEnvelope, HookKind>? {
        val expected = credential
        return decode(line)
            ?.takeIf {
                expected != null &&
                    expected.matches(it.credential) &&
                    it.version == HOOK_WIRE_VERSION &&
                    PANE.matches(it.pane) &&
                    AGENT.matches(it.agent)
            }?.let { HookEnvelope(it.agent, it.pane, it.agentPid, it.event, clock.millis(), it.tmuxSocket) to it.kind }
    }

    private fun decode(line: String): HookRequest? =
        try {
            protocolJson.decodeFromString(HookRequest.serializer(), line)
        } catch (_: IllegalArgumentException) {
            // Malformed JSON, a missing field or an unknown kind. SerializationException is one of these.
            null
        }

    /**
     * Holds the connection while the sink decides. The hook program closing its end cancels the sink's call at
     * once, which is how a request the desk answered first is released.
     */
    private suspend fun answerPermission(
        client: SocketChannel,
        envelope: HookEnvelope,
    ) = coroutineScope {
        val answer = async { sink.onPermission(envelope) }
        val watcher =
            launch(Dispatchers.IO) {
                waitForHangUp(client)
                answer.cancel()
            }
        try {
            val reply =
                try {
                    answer.await()
                } catch (cancelled: CancellationException) {
                    // Cancelled because the hook hung up: nothing to send. Cancelled from outside: pass it on.
                    if (!isActive) throw cancelled
                    null
                }
            if (reply != null) send(client, reply)
        } finally {
            // Closing the channel ends the watcher's blocking read, whatever happened above.
            runCatching { client.close() }
            watcher.cancel()
        }
    }

    private fun send(
        client: SocketChannel,
        reply: HookReply,
    ) {
        val bytes = (protocolJson.encodeToString(HookReply.serializer(), reply) + "\n").toByteArray()
        // The hook program drops a longer reply anyway, and then asks at the desk. Saying so here is clearer.
        if (bytes.size > MAX_HOOK_REPLY_BYTES) {
            log.warn("A hook reply of {} bytes is over the limit and was not sent.", bytes.size)
            return
        }
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) client.write(buffer)
    }

    private fun peerIsOwner(client: SocketChannel): Boolean {
        if (ExtendedSocketOptions.SO_PEERCRED !in client.supportedOptions()) return true
        val peer = runCatching { client.getOption(ExtendedSocketOptions.SO_PEERCRED) }.getOrNull()
        return peer is UnixDomainPrincipal && peer.user().name == System.getProperty("user.name")
    }

    private fun writeCredential(): Credential {
        val bytes = ByteArray(CREDENTIAL_BYTES).also(random::nextBytes)
        val value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val folder = credentialPath.parent
        // Written to a private temporary file and moved into place, so the file is never readable by others,
        // not even for a moment, and a hook never reads half a credential.
        val temporary =
            if (posix(folder)) {
                val mode = PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE)
                Files.createTempFile(folder, ".credential", ".tmp", mode)
            } else {
                Files.createTempFile(folder, ".credential", ".tmp")
            }
        Files.writeString(temporary, value)
        Files.move(temporary, credentialPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return Credential(value)
    }

    override fun close() {
        server?.close()
        acceptor?.cancel()
        open.forEach { runCatching { it.close() } }
        Files.deleteIfExists(socketPath)
        Files.deleteIfExists(credentialPath)
    }

    // Holds the hook credential, so it must never print it.
    override fun toString(): String = "HookChannel(socket=$socketPath)"

    /** The per-start secret. Compared by digest, in constant time. */
    private class Credential(
        value: String,
    ) {
        private val digest = sha256(value)

        fun matches(candidate: String): Boolean = MessageDigest.isEqual(digest, sha256(candidate))

        override fun toString(): String = "Credential(redacted)"
    }

    companion object {
        private val log = LoggerFactory.getLogger(HookChannel::class.java)
        private val OWNER_ONLY_FILE = PosixFilePermissions.fromString("rw-------")
        private val REQUEST_TIMEOUT = 10.seconds
        private const val CREDENTIAL_BYTES = 32
        private const val READ_CHUNK = 8192

        // Far above what one agent's hooks open at once, and low enough that a flood cannot use up file handles.
        private const val MAX_CONNECTIONS = 256
        private val PANE = Regex("%[0-9]{1,9}")
        private val AGENT = Regex("[a-z0-9-]{1,32}")

        /** Where the socket lives for a bridge with [dataDir]. */
        fun socketPathFor(dataDir: Path): Path = dataDir.resolve("hook").resolve("hook.sock")

        /** Where the per-start credential lives for a bridge with [dataDir]. */
        fun credentialPathFor(dataDir: Path): Path = dataDir.resolve("hook").resolve("credential")

        private fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())

        private fun posix(path: Path) = "posix" in path.fileSystem.supportedFileAttributeViews()

        private fun isAnswering(path: Path): Boolean =
            try {
                SocketChannel.open(StandardProtocolFamily.UNIX).use { it.connect(UnixDomainSocketAddress.of(path)) }
                true
            } catch (_: IOException) {
                false
            }

        /**
         * Reads one line of at most [MAX_HOOK_REQUEST_BYTES]. Null when the peer closes first or sends more
         * than that without a newline.
         */
        @Suppress("ReturnCount") // Three ways a line ends: a newline, the peer closing, or the size limit.
        internal fun readRequestLine(channel: SocketChannel): String? {
            val bytes = ByteArrayOutputStream()
            val buffer = ByteBuffer.allocate(READ_CHUNK)
            while (true) {
                buffer.clear()
                if (channel.read(buffer) < 0) return null
                buffer.flip()
                while (buffer.hasRemaining()) {
                    val next = buffer.get()
                    if (next == '\n'.code.toByte()) return bytes.toString(Charsets.UTF_8)
                    if (bytes.size() >= MAX_HOOK_REQUEST_BYTES) return null
                    bytes.write(next.toInt())
                }
            }
        }

        /** Blocks until the peer closes its end. Anything it sends after the request is ignored. */
        private fun waitForHangUp(channel: SocketChannel) {
            val buffer = ByteBuffer.allocate(READ_CHUNK)
            try {
                while (true) {
                    buffer.clear()
                    if (channel.read(buffer) < 0) return
                }
            } catch (_: IOException) {
                // Closed under us, by the reply being sent or the bridge stopping.
            }
        }
    }
}
