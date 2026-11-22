package ai.tatawur.rafiq.bridge.tmux

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The two switches and the list of shells, end to end: session list, `WS /terminal`, and a real tmux. */
@Timeout(120)
class TerminalAccessTest {
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
        settings: TerminalSettings,
        agents: AgentDetector = AgentDetector(claudeCommand = "rafiq-no-such-claude"),
    ): RealBridge =
        RealBridge(dir.resolve("data-${bridges.size}"), TerminalGate(settings, tmux.server), agents = agents)
            .also(bridges::add)

    private fun settings(
        terminal: Boolean,
        shells: Set<String> = emptySet(),
    ) = TerminalSettings(
        enabled = true,
        sessions = setOf("weather-app"),
        socket = tmux.socket,
        terminal = terminal,
        shellSessions = shells,
    )

    private fun RealBridge.sessionsJson(): String {
        runBlocking { tmuxSource!!.refresh() }
        return get("/sessions").body()
    }

    /** True when the socket got as far as a snapshot, which means a tmux client was attached. */
    private fun TestSocket.opens(): Boolean {
        sendResize(80, 24)
        val first = until { it is Received.Closed || (it is Received.Text && "\"snapshot\"" in it.text) }
        return first is Received.Text
    }

    private fun TestSocket.refusal(): String {
        val first = next()
        assertIs<Received.Closed>(first, "the socket must be refused, not opened")
        return first.reason
    }

    private fun RealBridge.historyStatus(): Int =
        get("/terminal/history?session=tmux-weather-app&lines=10").statusCode()

    private fun noTmuxClient(): Boolean =
        tmux.tmux("list-clients").isBlank() && tmux.sessionNames().none { it.startsWith(TmuxServer.VIEW_PREFIX) }

    @Test
    fun with_listing_on_and_terminal_off_the_session_is_listed_and_the_socket_refused_before_tmux() {
        tmux.newSession("weather-app")
        val bridge = open(settings(terminal = false, shells = setOf("weather-app")))

        val body = bridge.sessionsJson()
        assertTrue("\"id\":\"tmux-weather-app\"" in body && "\"terminal_allowed\":false" in body, body)
        assertEquals("no such session", bridge.terminal("weather-app").refusal())
        assertTrue(noTmuxClient(), "no tmux client may start for a refused socket")
        assertEquals(404, bridge.historyStatus(), "history follows the same rule as the socket")
    }

    @Test
    fun a_session_with_no_agent_opens_only_once_its_name_is_on_the_list_of_shells() {
        tmux.newSession("weather-app")
        val before = open(settings(terminal = true))
        assertTrue("\"terminal_allowed\":false" in before.sessionsJson())
        assertEquals("no such session", before.terminal("weather-app").refusal())
        assertTrue(noTmuxClient())
        assertEquals(404, before.historyStatus())

        val after = open(settings(terminal = true, shells = setOf("weather-app")))
        assertTrue("\"terminal_allowed\":false" !in after.sessionsJson())
        assertEquals(200, after.historyStatus())
        assertTrue(after.terminal("weather-app").opens(), "a named shell opens")
    }

    @Test
    fun a_session_with_an_agent_opens_without_being_on_the_list_of_shells() {
        val bin = Files.createDirectory(dir.resolve("bin"))
        val claude = bin.resolve("claude")
        // The process list names a program by the name it was started under, so a link to sleep is a "claude".
        Files.createSymbolicLink(claude, Path.of("/bin/sleep"))
        tmux.newSession("weather-app", "$claude 1000")
        val panePid = tmux.tmux("display-message", "-p", "-t", "=weather-app:", "#{pane_pid}").trim().toInt()
        val table = runBlocking { AgentDetector.processTable() }
        assumeTrue(
            AgentDetector.claudeProcessesUnder(panePid, table).isNotEmpty(),
            "this system does not report the linked program under the name claude",
        )
        val bridge = open(settings(terminal = true))

        val body = bridge.sessionsJson()
        assertTrue("\"agent\":\"claude-code\"" in body && "\"terminal_allowed\":false" !in body, body)
        assertTrue(bridge.terminal("weather-app").opens(), "a session with an agent opens")
    }
}
