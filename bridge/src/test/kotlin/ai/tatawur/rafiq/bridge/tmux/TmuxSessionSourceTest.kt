package ai.tatawur.rafiq.bridge.tmux

import ai.tatawur.rafiq.bridge.FakeAdapter
import ai.tatawur.rafiq.bridge.store.EventLog
import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.EventPayload
import kotlinx.coroutines.Dispatchers
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

    private fun RealBridge.post(path: String) = post(path, """{"text":"hello"}""")

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
    fun a_tmux_session_without_a_reader_refuses_a_chat_prompt_and_starts_no_agent() {
        tmux.newSession("weather-app")
        // An agent named like the session's own, so an unguarded prompt would start it through the adapter.
        val adapter = FakeAdapter("terminal")
        val settings = TerminalSettings(true, setOf("weather-app"), tmux.socket)
        val bridge =
            RealBridge(
                dir.resolve("data"),
                TerminalGate(settings, tmux.server),
                adapter = adapter,
            ).also(bridges::add)
        bridge.refresh()

        val answer = bridge.post("/sessions/tmux-weather-app/prompt")

        assertEquals(409, answer.statusCode())
        assertTrue("chat_unavailable" in answer.body(), answer.body())
        assertTrue(adapter.started.isEmpty(), "an agent was started for a tmux session")
    }

    @Test
    fun the_agent_list_is_asked_once_for_a_while_and_a_hung_one_does_not_hold_the_poll() {
        val bin = Files.createDirectory(dir.resolve("bin"))
        Files.createSymbolicLink(bin.resolve("claude"), Path.of("/bin/sleep"))
        tmux.newSession("weather-app", "${bin.resolve("claude")} 1000")
        val panePid = tmux.tmux("display-message", "-p", "-t", "=weather-app:", "#{pane_pid}").trim().toInt()
        val table = runBlocking { AgentDetector.processTable() }
        assumeTrue(AgentDetector.claudeProcessesUnder(panePid, table).isNotEmpty(), "no process named claude here")
        val panes = runBlocking { tmux.server.panes() }
        val counter = dir.resolve("asked.txt")
        val counting = bin.resolve("claude-counting")
        Files.writeString(counting, "#!/bin/sh\necho x >> '$counter'\necho '[{\"pid\": $panePid, \"cwd\": \"/w\"}]'\n")
        counting.toFile().setExecutable(true)
        var now = 1_000_000L
        val detector = AgentDetector(claudeCommand = counting.toString(), nowMs = { now })

        runBlocking {
            detector.detect(panes, table)
            now += 3_000
            detector.detect(panes, table)
        }
        assertEquals(1, Files.readAllLines(counter).size, "asked again within the cache time")
        now += 30_000
        runBlocking { detector.detect(panes, table) }
        assertEquals(2, Files.readAllLines(counter).size, "never asked again after the cache time")

        // A list command that hangs is given up on, and the process tree decides.
        val hanging = bin.resolve("claude-hanging")
        Files.writeString(hanging, "#!/bin/sh\nexec sleep 300\n")
        hanging.toFile().setExecutable(true)
        val hung = AgentDetector(claudeCommand = hanging.toString(), timeoutSeconds = 1)
        val found = runBlocking { hung.detect(panes, table) }
        assertEquals("claude-code", found.getValue("weather-app").agent)
    }

    @Test
    fun a_session_listed_as_a_shell_takes_claudes_folder_once_claude_runs_in_it() {
        val home = Files.createDirectory(dir.resolve("home"))
        val project = Files.createDirectory(dir.resolve("weather-app")).toRealPath()
        tmux.tmux("new-session", "-d", "-s", "weather-app", "-c", home.toString(), "-x", "120", "-y", "40", "cat")
        val bin = Files.createDirectory(dir.resolve("bin"))
        Files.createSymbolicLink(bin.resolve("claude"), Path.of("/bin/sleep"))
        val pidFile = dir.resolve("claude.pid")
        val list = bin.resolve("claude-list")
        Files.writeString(
            list,
            "#!/bin/sh\nprintf '[{\"pid\": %s, \"cwd\": \"%s\"}]' \"\$(cat '$pidFile')\" '$project'\n",
        )
        list.toFile().setExecutable(true)
        val bridge = open("weather-app", agents = AgentDetector(claudeCommand = list.toString()))
        bridge.tmuxSource!!.permissionModeOf = { "plan" }
        bridge.refresh()
        val stored = {
            runBlocking {
                bridge.fixture.log
                    .session("tmux-weather-app")!!
                    .session
            }
        }
        assertEquals(home.toRealPath().toString(), Path.of(stored().projectPath).toRealPath().toString())

        tmux.tmux("respawn-pane", "-k", "-t", "=weather-app:", "${bin.resolve("claude")} 1000")
        val panePid = tmux.tmux("display-message", "-p", "-t", "=weather-app:", "#{pane_pid}").trim()
        Files.writeString(pidFile, panePid)
        val table = runBlocking { AgentDetector.processTable() }
        assumeTrue(AgentDetector.claudeProcessesUnder(panePid.toInt(), table).isNotEmpty(), "no process named claude")
        bridge.refresh()

        assertEquals("claude-code", stored().agent)
        assertEquals(project.toString(), stored().projectPath)
        // The update keeps the fields the log does not store: the permission mode and the Terminal flag.
        val update =
            runBlocking { bridge.fixture.log.latest("tmux-weather-app", 20) }
                .mapNotNull { it.payload as? EventPayload.SessionUpdated }
                .last()
                .session
        assertEquals("plan", update.permissionMode)
        assertEquals(bridge.tmuxSource!!.present(stored()).terminalAllowed, update.terminalAllowed)
    }

    @Test
    fun a_failed_poll_after_a_good_one_keeps_which_sessions_terminal_may_open() {
        val failing = dir.resolve("tmux-fails")
        val tmuxStandIn = dir.resolve("tmux")
        Files.writeString(
            tmuxStandIn,
            "#!/bin/sh\n[ -e '$failing' ] && { echo boom >&2; exit 1; }\n" +
                "case \"$1\" in\n" +
                "  list-sessions) printf '\$1\\t0\\tweather-app\\n' ;;\n" +
                "  list-panes) printf 'weather-app\\t%%1\\t999999\\t1\\t1\\t/tmp\\n' ;;\n" +
                "esac\n",
        )
        tmuxStandIn.toFile().setExecutable(true)
        val settings =
            TerminalSettings(true, setOf("weather-app"), terminal = true, shellSessions = setOf("weather-app"))
        val gate = TerminalGate(settings, TmuxServer(binary = tmuxStandIn.toString()))
        val log = EventLog.open(null, Dispatchers.IO.limitedParallelism(1))
        val source = TmuxSessionSource(gate, log, AgentDetector(claudeCommand = "rafiq-no-such-claude"))
        try {
            runBlocking { source.poll() }
            assertTrue(gate.canOpen("weather-app"), "the good poll opens the session")

            Files.createFile(failing)
            runBlocking { source.poll() }

            assertTrue(gate.canOpen("weather-app"), "one failed poll took Terminal away")
        } finally {
            log.close()
        }
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
