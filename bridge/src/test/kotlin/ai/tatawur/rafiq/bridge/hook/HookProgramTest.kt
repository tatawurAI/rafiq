package ai.tatawur.rafiq.bridge.hook

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Timeout
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Runs the real hook program, `bridge/hooks/rafiq-hook.py`, with `python3` against a bridge that misbehaves in
 * each way the program must survive. Whatever goes wrong, it prints nothing and exits 0, so Claude Code shows
 * its own dialog at the desk.
 */
@Timeout(120)
class HookProgramTest {
    private val dir: Path = Files.createTempDirectory("rp")
    private val hookDir: Path =
        Files.createDirectories(dir.resolve("hook")).also {
            if ("posix" in it.fileSystem.supportedFileAttributeViews()) {
                Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwx------"))
            }
        }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        val python = runCatching { ProcessBuilder("python3", "--version").start().waitFor() }.getOrNull()
        check(python == 0 || System.getenv("CI") == null) { "These tests need python3, and CI has none." }
        assumeTrue(python == 0, "python3 is not installed")
    }

    @AfterEach
    fun cleanUp() {
        scope.cancel()
        dir.toFile().deleteRecursively()
    }

    private class Run(
        val exit: Int,
        val out: String,
    )

    /** Runs the hook with [event] on stdin, as Claude Code would inside tmux pane `%3`. */
    private fun runHook(
        event: String,
        timeoutSeconds: Long = 30,
    ): Run {
        val process =
            ProcessBuilder("python3", HOOK.toString(), "--data-dir", dir.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .also {
                    it.environment()["TMUX_PANE"] = "%3"
                    it.environment()["CLAUDE_PID"] = "4242"
                }.start()
        process.outputStream.use { it.write(event.toByteArray()) }
        val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
        check(process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { "the hook did not exit" }
        return Run(process.exitValue(), out)
    }

    /**
     * A stand-in bridge: accepts one connection, reads the request line, then lets [then] answer it. Returns
     * the request line it read.
     */
    private fun fakeBridge(then: (SocketChannel) -> Unit): CompletableDeferred<String> {
        Files.writeString(hookDir.resolve("credential"), "placeholder-credential")
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(UnixDomainSocketAddress.of(hookDir.resolve("hook.sock")))
        val request = CompletableDeferred<String>()
        thread(isDaemon = true) {
            server.use {
                server.accept().use { client ->
                    request.complete(HookChannel.readRequestLine(client).orEmpty())
                    then(client)
                }
            }
        }
        return request
    }

    private fun SocketChannel.reply(text: String) {
        val buffer = ByteBuffer.wrap(text.toByteArray())
        while (buffer.hasRemaining()) write(buffer)
    }

    private fun assertSilent(run: Run) {
        assertEquals("", run.out)
        assertEquals(0, run.exit)
    }

    @Test
    fun the_bridge_dying_mid_wait_leaves_the_decision_to_the_desk() {
        val waiting = CompletableDeferred<Unit>()
        val channel =
            HookChannel(
                dir,
                object : HookSink {
                    override suspend fun onEvent(envelope: HookEnvelope) = Unit

                    override suspend fun onPermission(envelope: HookEnvelope): HookReply? {
                        waiting.complete(Unit)
                        awaitCancellation()
                    }
                },
                scope,
            ).start()
        thread(isDaemon = true) {
            while (!waiting.isCompleted) Thread.sleep(POLL_MS)
            channel.close()
        }

        assertSilent(runHook(BASH_REQUEST))
        assertEquals(true, waiting.isCompleted, "the hook never reached the bridge")
    }

    @Test
    fun the_real_channel_and_the_program_agree_on_the_wire() {
        val seen = CompletableDeferred<HookEnvelope>()
        HookChannel(
            dir,
            object : HookSink {
                override suspend fun onEvent(envelope: HookEnvelope) = Unit

                override suspend fun onPermission(envelope: HookEnvelope): HookReply {
                    seen.complete(envelope)
                    return HookReply(HookDecision.ALLOW_SESSION, rule = "Bash(npm test)")
                }
            },
            scope,
        ).start().use {
            val run = runHook(BASH_REQUEST)

            val expected =
                """{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"allow",""" +
                    """"updatedPermissions":[{"type":"addRules",""" +
                    """"rules":[{"toolName":"Bash","ruleContent":"npm test"}],""" +
                    """"behavior":"allow","destination":"session"}]}}}"""
            assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(run.out))
            assertEquals("%3", seen.getCompleted().pane)
            assertEquals(4242L, seen.getCompleted().agentPid)
        }
    }

    @Test
    fun garbage_an_oversize_reply_or_an_unknown_decision_prints_nothing() {
        val replies =
            listOf(
                "this is not json\n",
                "[\"allow\"]\n",
                "{\"decision\":\"maybe\"}\n",
                // A valid allow, padded past the reply limit.
                "{\"decision\":\"allow\",\"message\":\"${"a".repeat(MAX_HOOK_REPLY_BYTES)}\"}\n",
                // A session rule for a different tool than the one that asked.
                "{\"decision\":\"allow_session\",\"rule\":\"Write\"}\n",
                // A plain allow cannot answer a question, and answers cannot allow a command.
                "{\"decision\":\"answers\",\"answers\":{\"Which?\":\"A\"}}\n",
                // No newline before the bridge hangs up.
                "{\"decision\":\"allow\"}",
            )
        for (reply in replies) {
            val request = fakeBridge { it.reply(reply) }
            assertSilent(runHook(BASH_REQUEST))
            assertEquals(true, request.isCompleted, reply.take(60))
            Files.deleteIfExists(hookDir.resolve("hook.sock"))
        }
    }

    @Test
    fun with_no_bridge_running_the_hook_prints_nothing() {
        Files.writeString(hookDir.resolve("credential"), "placeholder-credential")

        assertSilent(runHook(BASH_REQUEST))
        assertSilent(runHook("not json at all"))
    }

    @Test
    fun a_valid_reply_becomes_exactly_the_decision_claude_code_expects() {
        val cases =
            listOf(
                Triple(BASH_REQUEST, "{\"decision\":\"allow\"}", """{"behavior":"allow"}"""),
                Triple(
                    BASH_REQUEST,
                    "{\"decision\":\"deny\",\"message\":\"Not on main.\"}",
                    """{"behavior":"deny","message":"Not on main."}""",
                ),
                Triple(
                    BASH_REQUEST,
                    "{\"decision\":\"allow_session\",\"rule\":\"Bash(npm test)\"}",
                    """{"behavior":"allow","updatedPermissions":[{"type":"addRules",""" +
                        """"rules":[{"toolName":"Bash","ruleContent":"npm test"}],"behavior":"allow",""" +
                        """"destination":"session"}]}""",
                ),
                Triple(
                    QUESTION_REQUEST,
                    "{\"decision\":\"answers\",\"answers\":{\"Which database?\":\"SQLite\"}}",
                    """{"behavior":"allow","updatedInput":{"questions":[{"question":"Which database?",""" +
                        """"options":[{"label":"SQLite"},{"label":"Postgres"}]}],""" +
                        """"answers":{"Which database?":"SQLite"}}}""",
                ),
            )
        for ((event, reply, decision) in cases) {
            val request = fakeBridge { it.reply(reply + "\n") }

            val run = runHook(event)

            val expected = """{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":$decision}}"""
            assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(run.out), reply)
            assertEquals(0, run.exit)
            val sent = Json.parseToJsonElement(request.getCompleted()).jsonObject
            assertEquals("permission", sent["kind"].toString().trim('"'))
            assertEquals("%3", sent["pane"].toString().trim('"'))
            assertEquals("4242", sent["agent_pid"].toString())
            assertEquals(Json.parseToJsonElement(event), sent["event"] as JsonObject)
            Files.deleteIfExists(hookDir.resolve("hook.sock"))
        }
    }

    private companion object {
        // Gradle runs the tests in the bridge module's folder.
        val HOOK: Path = Path.of("hooks", "rafiq-hook.py").toAbsolutePath()
        const val POLL_MS = 20L

        const val BASH_REQUEST =
            """{"hook_event_name":"PermissionRequest","session_id":"s-1","tool_name":"Bash",""" +
                """"tool_input":{"command":"npm test"}}"""

        const val QUESTION_REQUEST =
            """{"hook_event_name":"PermissionRequest","session_id":"s-1","tool_name":"AskUserQuestion",""" +
                """"tool_input":{"questions":[{"question":"Which database?",""" +
                """"options":[{"label":"SQLite"},{"label":"Postgres"}]}]}}"""
    }
}
