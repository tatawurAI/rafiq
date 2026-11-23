package ai.tatawur.rafiq.bridge.hook

import ai.tatawur.rafiq.bridge.tmux.eventually
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Timeout
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Timeout(60)
class HookChannelTest {
    // Short, because a Unix socket path must fit in about 100 bytes.
    private val dir: Path = Files.createTempDirectory("rh")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val channels = mutableListOf<HookChannel>()

    @AfterEach
    fun cleanUp() {
        channels.forEach(HookChannel::close)
        scope.cancel()
        dir.toFile().deleteRecursively()
    }

    private fun start(sink: HookSink): HookChannel = HookChannel(dir, sink, scope).start().also(channels::add)

    private fun credential(channel: HookChannel) = Files.readString(channel.credentialPath)

    private fun requestLine(
        credential: String,
        kind: String = "event",
        pane: String = "%3",
    ): String {
        val request =
            buildJsonObject {
                put("v", HOOK_WIRE_VERSION)
                put("credential", credential)
                put("kind", kind)
                put("agent", "claude-code")
                put("pane", pane)
                put("agent_pid", 4242)
                put("event", buildJsonObject { put("hook_event_name", "Stop") })
            }
        return request.toString() + "\n"
    }

    private fun connect(channel: HookChannel): SocketChannel =
        SocketChannel
            .open(
                StandardProtocolFamily.UNIX,
            ).apply { connect(UnixDomainSocketAddress.of(channel.socketPath)) }

    /** Sends [line] and returns what the bridge wrote back before closing, or null for nothing. */
    private fun call(
        channel: HookChannel,
        line: String,
    ): String? =
        connect(channel).use { socket ->
            socket.write(ByteBuffer.wrap(line.toByteArray()))
            Channels
                .newInputStream(socket)
                .readBytes()
                .toString(Charsets.UTF_8)
                .takeIf { it.isNotEmpty() }
        }

    @Test
    fun an_event_with_the_credential_reaches_the_sink_with_its_pane_and_pid() {
        val seen = CopyOnWriteArrayList<HookEnvelope>()
        val channel =
            start(
                object : HookSink {
                    override suspend fun onEvent(envelope: HookEnvelope) {
                        seen += envelope
                    }

                    override suspend fun onPermission(envelope: HookEnvelope): HookReply? = null
                },
            )

        assertNull(call(channel, requestLine(credential(channel))))

        eventually("the event arrives") { seen.size == 1 }
        assertEquals("%3", seen.single().pane)
        assertEquals(4242L, seen.single().agentPid)
        assertEquals(JsonPrimitive("Stop"), seen.single().event["hook_event_name"])
    }

    @Test
    fun a_call_with_a_wrong_or_stale_credential_or_a_bad_line_is_closed_unheard() {
        val heard = AtomicBoolean(false)
        val sink =
            object : HookSink {
                override suspend fun onEvent(envelope: HookEnvelope) = heard.set(true)

                override suspend fun onPermission(envelope: HookEnvelope): HookReply? {
                    heard.set(true)
                    return HookReply(HookDecision.ALLOW)
                }
            }
        val first = start(sink)
        val stale = credential(first)
        first.close()
        channels.remove(first)
        val channel = start(sink)
        val fresh = credential(channel)

        // A new start means a new credential, so a hook holding the old one is refused.
        assertNotEquals(stale, fresh)
        val refused =
            listOf(
                requestLine(stale, kind = "permission"),
                requestLine(fresh.dropLast(1) + "x", kind = "permission"),
                requestLine("", kind = "permission"),
                requestLine(fresh, kind = "permission", pane = "not-a-pane"),
                "{not json\n",
            )
        for (line in refused) assertNull(call(channel, line), line)
        // One more over the size limit, with no newline in reach.
        assertNull(call(channel, "x".repeat(MAX_HOOK_REQUEST_BYTES + 10)))

        assertEquals(false, heard.get())
        // And the channel still serves a good call afterwards.
        assertEquals("{\"decision\":\"allow\"}\n", call(channel, requestLine(fresh, kind = "permission")))
    }

    @Test
    fun a_permission_reply_is_one_line_and_hanging_up_cancels_the_wait() {
        val started = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val channel =
            start(
                object : HookSink {
                    override suspend fun onEvent(envelope: HookEnvelope) = Unit

                    override suspend fun onPermission(envelope: HookEnvelope): HookReply? {
                        started.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            released.complete(Unit)
                        }
                    }
                },
            )
        val socket = connect(channel)
        socket.write(ByteBuffer.wrap(requestLine(credential(channel), kind = "permission").toByteArray()))
        eventually("the sink is waiting") { started.isCompleted }

        // The desk answered first, so the hook exits and closes its end.
        socket.close()

        eventually("the held request is released", seconds = 5) { released.isCompleted }
    }

    @Test
    fun a_second_bridge_on_the_same_folder_fails_and_leaves_the_first_credential_alone() {
        val first = start(NoopHookSink)
        val before = Files.readAllBytes(first.credentialPath)

        val failure = runCatching { HookChannel(dir, NoopHookSink, scope).start() }.exceptionOrNull()

        assertTrue(failure is IllegalStateException, "a second channel started on a folder in use")
        assertTrue(before.contentEquals(Files.readAllBytes(first.credentialPath)), "the credential was replaced")
    }

    @Test
    fun the_folder_credential_and_socket_are_private_to_the_bridge_user() {
        val channel = start(NoopHookSink)
        if ("posix" !in dir.fileSystem.supportedFileAttributeViews()) return

        fun mode(path: Path) = PosixFilePermissions.toString(Files.getPosixFilePermissions(path))
        assertEquals("rwx------", mode(channel.socketPath.parent))
        assertEquals("rw-------", mode(channel.credentialPath))
        assertEquals("rw-------", mode(channel.socketPath))
        assertTrue(credential(channel).length >= 40, "the credential is 256 random bits")
    }

    @Test
    fun a_hook_folder_that_others_can_enter_stops_the_start() {
        if ("posix" !in dir.fileSystem.supportedFileAttributeViews()) return
        Files.createDirectories(dir.resolve("hook"))
        Files.setPosixFilePermissions(dir.resolve("hook"), PosixFilePermissions.fromString("rwxr-xr-x"))

        val failure = runCatching { HookChannel(dir, NoopHookSink, scope).start() }.exceptionOrNull()

        assertTrue(failure is IllegalStateException, "started with a hook folder of mode 0755")
        assertEquals(false, Files.exists(dir.resolve("hook").resolve("credential")))
    }
}
