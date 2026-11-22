package ai.tatawur.rafiq.bridge.tmux

import ai.tatawur.rafiq.bridge.server.LoggingAuditLog
import ai.tatawur.rafiq.protocol.api.MAX_HISTORY_LINES
import ai.tatawur.rafiq.protocol.api.TerminalHistory
import ai.tatawur.rafiq.protocol.protocolJson
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `GET /terminal/history` against a real tmux on a throwaway socket. */
@Timeout(120)
class TerminalHistoryTest {
    @TempDir
    lateinit var dir: Path

    private val tmux = TestTmux()
    private var bridge: RealBridge? = null
    private val calls by lazy { dir.resolve("tmux-calls.txt") }
    private val auditLines = java.util.concurrent.CopyOnWriteArrayList<String>()

    @AfterEach
    fun cleanUp() {
        bridge?.close()
        tmux.close()
    }

    /** A bridge whose tmux runs through a script that records every command line. */
    private fun bridgeFor(vararg sessions: String): RealBridge {
        val wrapper = dir.resolve("tmux-wrapper")
        Files.writeString(wrapper, "#!/bin/sh\necho \"\$@\" >> '$calls'\nexec tmux \"\$@\"\n")
        wrapper.toFile().setExecutable(true)
        val settings = TerminalSettings(true, sessions.toSet(), tmux.socket, TerminalLimits())
        val gate = openGate(settings, TmuxServer(tmux.socket, wrapper.toString()))
        return RealBridge(dir.resolve("data"), gate, audit = LoggingAuditLog(auditLines::add)).also { bridge = it }
    }

    @Test
    fun a_device_that_reads_too_often_gets_429_and_every_read_is_audited() {
        tmux.newSession("weather-app")
        val bridge = bridgeFor("weather-app")
        val window = deskWindow()

        val statuses = (1..6).map { bridge.get("/terminal/history?session=tmux-weather-app&window=$window&lines=40") }

        assertEquals(List(5) { 200 } + 429, statuses.map { it.statusCode() })
        assertTrue("too_many_requests" in statuses.last().body())
        assertEquals(
            "2",
            statuses
                .last()
                .headers()
                .firstValue("Retry-After")
                .orElse(""),
        )
        assertEquals(200, bridge.get("/terminal/history?session=tmux-weather-app", device = "tablet").statusCode())
        assertEquals(
            List(5) { "Terminal history read: device=phone session=tmux-weather-app window=$window lines=40" } +
                "Terminal history read: device=tablet session=tmux-weather-app window=current lines=$MAX_HISTORY_LINES",
            auditLines,
        )
    }

    private fun recorded(): List<String> = if (Files.exists(calls)) Files.readAllLines(calls) else emptyList()

    @Test
    fun history_is_read_only_and_never_moves_or_resizes_the_desk() {
        longHistory()
        tmux.newSession("weather-app", "sh -c 'seq 1 3000; exec cat'", cols = 120, rows = 40)
        tmux.tmux("new-window", "-d", "-t", "=weather-app:", "cat")
        val desk = tmux.attachDesk("weather-app", 100, 30)
        eventuallyEquals("the desk has its size", 100 to 30) { tmux.windowSize("weather-app") }
        eventually("the pane printed its lines") { "3000" in tmux.capture("weather-app") }
        desk.typeSomething()
        val window = deskWindow()
        val bridge = bridgeFor("weather-app")

        val response = bridge.get("/terminal/history?session=tmux-weather-app&window=$window&lines=1000")

        assertEquals(200, response.statusCode(), response.body())
        val history = protocolJson.decodeFromString(TerminalHistory.serializer(), response.body())
        assertEquals(1000 + 30, history.lineCount, "1000 lines of history and the 30 rows of the screen")
        assertEquals(history.lineCount, history.text.split('\n').size)
        assertTrue(history.text.lines().contains("3000"))
        assertEquals(100, history.cols)
        assertFalse(history.alternateScreen)
        assertEquals(100 to 30, tmux.windowSize("weather-app"), "the window changed size")
        assertEquals(window, deskWindow(), "the desk changed window")
        assertEquals("0", tmux.tmux("display-message", "-p", "-t", "=weather-app:", "#{pane_in_mode}").trim())
        val commands = recorded().map { it.split(' ').drop(2).firstOrNull() }.toSet()
        assertEquals(setOf("list-sessions", "display-message", "capture-pane"), commands, recorded().toString())
    }

    @Test
    fun history_is_capped_and_says_when_the_pane_is_on_the_alternate_screen() {
        longHistory()
        tmux.newSession("weather-app", "sh -c 'seq 1 8000; exec cat'", cols = 80, rows = 20)
        tmux.newSession("docs-site", "sh -c 'printf \"\\033[?1049h\"; exec cat'", cols = 80, rows = 20)
        eventually("the pane printed its lines") { "8000" in tmux.capture("weather-app") }
        val bridge = bridgeFor("weather-app", "docs-site")

        val capped = bridge.get("/terminal/history?session=tmux-weather-app&lines=${MAX_HISTORY_LINES + 2000}")
        val history = protocolJson.decodeFromString(TerminalHistory.serializer(), capped.body())
        assertEquals(MAX_HISTORY_LINES + 20, history.lineCount)

        eventually("the program is on the alternate screen") {
            tmux.tmux("display-message", "-p", "-t", "=docs-site:", "#{alternate_on}").trim() == "1"
        }
        val alternate = bridge.get("/terminal/history?session=tmux-docs-site")
        assertTrue(protocolJson.decodeFromString(TerminalHistory.serializer(), alternate.body()).alternateScreen)
    }

    @Test
    fun history_needs_the_device_token_and_an_allowed_session() {
        tmux.newSession("weather-app")
        tmux.newSession("docs-site")
        val bridge = bridgeFor("weather-app")

        val noToken =
            HttpClient.newHttpClient().send(
                HttpRequest
                    .newBuilder(URI("http://127.0.0.1:${bridge.port}/terminal/history?session=tmux-weather-app"))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        assertEquals(401, noToken.statusCode())

        // Running in tmux but not on the allow-list, a prefix of a listed name, and a view session of the bridge.
        for (session in listOf("tmux-docs-site", "tmux-weather", "docs-site", "tmux-rafiq-view-1")) {
            val response = bridge.get("/terminal/history?session=$session")
            assertEquals(404, response.statusCode(), session)
            assertTrue("session_not_found" in response.body(), response.body())
        }
        assertTrue(recorded().none { "capture-pane" in it }, "a refused request read a pane: ${recorded()}")
        assertEquals(404, bridge.get("/terminal/history?session=tmux-weather-app&window=99").statusCode())
        assertEquals(400, bridge.get("/terminal/history?session=tmux-weather-app&lines=-5").statusCode())
    }

    /** Panes made from now on keep 10000 lines. A holder session keeps the server alive to remember it. */
    private fun longHistory() {
        tmux.tmux("new-session", "-d", "-s", "holder", ";", "set-option", "-g", "history-limit", "10000")
    }

    private fun deskWindow() =
        tmux.tmux("display-message", "-p", "-t", "=weather-app:", "#{window_index}").trim().toInt()
}
