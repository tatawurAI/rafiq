package ai.tatawur.rafiq.bridge.tmux

import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.EventPayload
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The tmux session source against a real tmux on a throwaway socket. */
@Timeout(120)
class TmuxSessionSourceTest {
    @TempDir
    lateinit var dir: Path

    private val tmux = TestTmux()
    private val bridges = mutableListOf<RealBridge>()

    @AfterEach
    fun cleanUp() {
        bridges.forEach(RealBridge::close)
        tmux.close()
    }

    private fun open(
        vararg allowed: String,
        enabled: Boolean = true,
        agents: AgentDetector = AgentDetector(claudeCommand = "rafiq-no-such-claude"),
    ): RealBridge {
        val settings = TerminalSettings(enabled, allowed.toSet(), tmux.socket)
        return RealBridge(dir.resolve("data"), TerminalGate(settings, tmux.server), agents = agents).also(bridges::add)
    }

    private fun RealBridge.listed(): List<String> =
        Regex("\"id\":\"(tmux-[^\"]+)\"")
            .findAll(get("/sessions").body())
            .map { it.groupValues[1] }
            .toList()
            .sorted()

    private fun RealBridge.refresh() = runBlocking { tmuxSource!!.refresh() }

    @Test
    fun an_allowed_session_is_listed_as_a_terminal_session_and_a_killed_one_leaves_on_the_next_poll() {
        tmux.newSession("weather-app")
        tmux.newSession("docs-site")
        tmux.newSession("scratch")
        tmux.newSession("rafiq-view-1")
        val bridge = open("weather-app", "docs-site", "rafiq-view-1")
        assertEquals(emptyList(), bridge.listed(), "nothing is shown before the first poll")

        bridge.refresh()

        // The view session is on the allow-list by name and is still never shown. scratch is not allowed.
        assertEquals(listOf("tmux-docs-site", "tmux-weather-app"), bridge.listed())
        val body = bridge.get("/sessions").body()
        assertTrue("\"terminal\":true" in body && "\"agent\":\"terminal\"" in body, body)

        tmux.tmux("kill-session", "-t", "=weather-app")
        bridge.refresh()
        assertEquals(listOf("tmux-docs-site"), bridge.listed())

        // The last session going takes the tmux server with it, which is a normal answer and not a failure.
        tmux.tmux("kill-session", "-t", "=docs-site")
        tmux.tmux("kill-session", "-t", "=scratch")
        tmux.tmux("kill-session", "-t", "=rafiq-view-1")
        bridge.refresh()
        assertEquals(emptyList(), bridge.listed())
    }

    @Test
    fun a_session_killed_while_the_bridge_was_down_is_not_shown_as_waiting_after_it_restarts() {
        tmux.newSession("weather-app")
        val first = open("weather-app")
        first.refresh()
        runBlocking {
            first.fixture.log.append(
                "tmux-weather-app",
                EventPayload.StatusChanged("main", AgentState.WAITING_ON_YOU, null),
            )
        }
        assertEquals(
            AgentState.WAITING_ON_YOU,
            runBlocking {
                first.fixture.log
                    .session("tmux-weather-app")!!
                    .session.status
            },
        )
        first.close()
        bridges.remove(first)
        tmux.tmux("kill-session", "-t", "=weather-app")

        val restarted = open("weather-app")

        assertEquals(emptyList(), restarted.listed(), "hidden before any poll")
        restarted.refresh()
        assertEquals(emptyList(), restarted.listed())
        val settled =
            runBlocking {
                restarted.fixture.log
                    .session("tmux-weather-app")!!
                    .session.status
            }
        assertEquals(AgentState.DONE, settled, "the log no longer says waiting")
        assertEquals(
            AgentState.DONE,
            runBlocking {
                restarted.fixture.log
                    .threads("tmux-weather-app")
                    .single()
                    .status
            },
        )
    }

    @Test
    fun with_terminal_off_a_tmux_session_in_the_log_is_not_listed_even_when_tmux_has_it() {
        tmux.newSession("weather-app")
        val on = open("weather-app")
        on.refresh()
        on.close()
        bridges.remove(on)

        val off = open("weather-app", enabled = false)
        off.refresh()

        assertEquals(emptyList(), off.listed())
    }

    @Test
    fun a_claude_process_under_the_pane_makes_the_session_claude_code_when_the_agent_list_agrees() {
        val bin = Files.createDirectory(dir.resolve("bin"))
        val claude = bin.resolve("claude")
        // The process list names a program by the name it was started under, so a link to sleep is a "claude".
        Files.createSymbolicLink(claude, Path.of("/bin/sleep"))
        tmux.newSession("weather-app", "$claude 1000")
        val panePid = tmux.tmux("display-message", "-p", "-t", "=weather-app:", "#{pane_pid}").trim().toInt()
        val table = runBlocking { AgentDetector.processTable() }
        assumeTrue(
            AgentDetector.claudeProcessesUnder(panePid, table).isNotEmpty(),
            "this system does not report the copied program under the name claude",
        )
        val listed = bin.resolve("claude-agents-listed")
        Files.writeString(listed, "#!/bin/sh\necho '[{\"pid\": $panePid, \"cwd\": \"/work/weather-app\"}]'\n")
        listed.toFile().setExecutable(true)
        val empty = bin.resolve("claude-agents-empty")
        Files.writeString(empty, "#!/bin/sh\necho '[]'\n")
        empty.toFile().setExecutable(true)

        val agreeing = open("weather-app", agents = AgentDetector(claudeCommand = listed.toString()))
        agreeing.refresh()
        val body = agreeing.get("/sessions").body()
        assertTrue("\"agent\":\"claude-code\"" in body && "/work/weather-app" in body, body)

        // A claude process the agent list does not know is not a live session.
        val sessions = runBlocking { tmux.server.panes() }
        val detected =
            runBlocking { AgentDetector(claudeCommand = empty.toString()).detect(sessions, table) }
        assertEquals("terminal", detected.getValue("weather-app").agent)
        assertFalse(detected.isEmpty())
    }
}
