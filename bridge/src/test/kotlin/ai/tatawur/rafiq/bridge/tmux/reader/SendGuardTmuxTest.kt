package ai.tatawur.rafiq.bridge.tmux.reader

import ai.tatawur.rafiq.bridge.hook.HookEnvelope
import ai.tatawur.rafiq.bridge.sessions.SessionException
import ai.tatawur.rafiq.bridge.tmux.TestTmux
import ai.tatawur.rafiq.bridge.tmux.eventually
import ai.tatawur.rafiq.protocol.DeliveryState
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.api.SendRefusal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The send guard typing into a real pane on a throwaway tmux server, where a small script stands in for Claude
 * Code's input box (`fake-claude.py`). What the script receives on Enter is what Claude would have received.
 */
@Timeout(120)
class SendGuardTmuxTest {
    @TempDir
    lateinit var dir: Path

    private val tmux = TestTmux()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = FakeReaderLog()

    @AfterEach
    fun cleanUp() {
        scope.cancel()
        tmux.close()
    }

    private val received by lazy { dir.resolve("received.jsonl") }

    /** Starts the stand-in in session `weather-app` and a reader that has seen its first hook. */
    private fun start(
        vararg flags: String,
        afterAgent: String = "exec cat",
        warmUp: Boolean = true,
    ): ClaudeReader {
        val script = dir.resolve("fake-claude.py")
        Files.copy(checkNotNull(javaClass.getResourceAsStream("/claude-reader/fake-claude.py")), script)
        val pidFile = dir.resolve("agent.pid")
        tmux.newSession(
            "weather-app",
            "python3 '$script' '$received' '$pidFile' ${flags.joinToString(" ")}; $afterAgent",
        )
        eventually("the stand-in agent started") { Files.exists(pidFile) }
        val pid = Files.readString(pidFile).trim().toLong()
        eventually("the stand-in drew its box") { "❯" in tmux.capture("weather-app") }
        val pane = tmux.tmux("display-message", "-p", "-t", "=weather-app:", "#{pane_id}").trim()
        val idle = AgentStatusSource { mapOf(pid to AgentStatus(pid, AgentStatus.IDLE)) }
        val reader =
            ClaudeReader(
                log = log,
                io = TmuxPaneIo(tmux.server),
                statuses = idle,
                allows = { it == "weather-app" },
                scope = scope,
                projectsDir = null,
            )
        val start =
            Json.parseToJsonElement(
                """{"hook_event_name":"SessionStart","session_id":"s-1","source":"startup"}""",
            )
        runBlocking {
            reader.onEvent(
                HookEnvelope("claude-code", pane, pid, start.jsonObject, System.currentTimeMillis()),
            )
        }
        if (warmUp) {
            eventually("the reader bound the pane") {
                runBlocking { runCatching { reader.prompt(SESSION, "ready?", "c-0") }.isSuccess }
            }
            eventually("the first prompt landed") { lines().isNotEmpty() }
        } else {
            // The hook is handled on the reader's own coroutine. A paste would end this stand-in, so just wait.
            Thread.sleep(SETTLE_MS)
        }
        return reader
    }

    private fun lines(): List<String> =
        if (Files.exists(received)) {
            Files
                .readAllLines(
                    received,
                ).map {
                    Json
                        .parseToJsonElement(it)
                        .jsonObject
                        .getValue("text")
                        .jsonPrimitive.content
                }
        } else {
            emptyList()
        }

    @Test
    fun text_reaches_the_agent_exactly_as_sent_with_shell_lines_and_slash_commands_untouched() {
        val reader = start()
        val sent =
            listOf(
                "!ls",
                "/compact",
                "/etc/hosts is a file path",
                "first line\n\tindented with a tab\nlast line",
                (1..17_000).map { 'a' + it % 26 }.joinToString(""),
            )

        for ((index, text) in sent.withIndex()) {
            runBlocking { reader.prompt(SESSION, text, "c-${index + 1}") }
            eventually("prompt ${index + 1} landed", seconds = 30) { lines().size == index + 2 }
        }

        assertEquals(listOf("ready?") + sent, lines())
        eventually("the shell line and the command were confirmed by the box clearing") {
            log.all<EventPayload.PromptDelivery>().count { it.state == DeliveryState.DELIVERED } >= 2
        }
    }

    @Test
    fun an_agent_that_exits_between_the_check_and_enter_gets_no_enter_and_nothing_reaches_the_shell() {
        val shellGot = dir.resolve("shell-got.txt")
        // After the agent, a shell-like program records any line it is given.
        val reader =
            start(
                "--exit-on-paste",
                afterAgent = "IFS= read -r line; printf '%s' \"\$line\" > '$shellGot'",
                warmUp = false,
            )
        val before = lines().size

        val refusal =
            assertFailsWith<SessionException.Conflict> { runBlocking { reader.prompt(SESSION, "rm -rf build", "c-9") } }

        assertEquals(SendRefusal.AGENT_NOT_RUNNING, refusal.code)
        Thread.sleep(SETTLE_MS)
        assertFalse(Files.exists(shellGot), "an Enter reached the shell")
        assertEquals(before, lines().size)
        assertTrue(refusal.message.orEmpty().isNotBlank())
    }

    private companion object {
        const val SETTLE_MS = 1_000L
    }
}
