package ai.tatawur.rafiq.bridge.tmux

import ai.tatawur.rafiq.protocol.api.TerminalListWindows
import ai.tatawur.rafiq.protocol.api.TerminalMessage
import ai.tatawur.rafiq.protocol.api.TerminalNotice
import ai.tatawur.rafiq.protocol.api.TerminalSelectWindow
import ai.tatawur.rafiq.protocol.api.TerminalSnapshot
import ai.tatawur.rafiq.protocol.api.TerminalWindow
import ai.tatawur.rafiq.protocol.api.TerminalWindows
import ai.tatawur.rafiq.protocol.api.terminalJson
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Which window the phone shows, and the size a window is left with, against a real tmux. */
@Timeout(120)
class TerminalWindowsTest {
    @TempDir
    lateinit var dir: Path

    private val tmux = TestTmux()
    private var bridge: RealBridge? = null

    @AfterEach
    fun cleanUp() {
        bridge?.close()
        tmux.close()
    }

    private fun bridgeFor(vararg sessions: String): RealBridge {
        val settings = TerminalSettings(true, sessions.toSet(), tmux.socket, TerminalLimits())
        return RealBridge(dir.resolve("data"), openGate(settings, tmux.server)).also { bridge = it }
    }

    @Test
    fun leaving_with_no_desk_attached_gives_the_window_its_earlier_size_back() {
        tmux.newSession("weather-app", cols = 120, rows = 40)
        val socket = bridgeFor("weather-app").terminal("weather-app")
        socket.sendResize(60, 20)
        socket.snapshot()
        eventuallyEquals("the window takes the phone's size", 60 to 20) { tmux.windowSize("weather-app") }

        socket.close()

        eventuallyEquals("the window gets its earlier size back", 120 to 40) { tmux.windowSize("weather-app") }
        eventually("no view session remains") { tmux.sessionNames().none { it.startsWith(TmuxServer.VIEW_PREFIX) } }
        assertEquals(120 to 40, tmux.windowSize("weather-app"), "the size holds once the view is gone")
    }

    @Test
    fun leaving_after_a_desk_attached_leaves_the_size_to_the_desk() {
        tmux.newSession("weather-app", cols = 120, rows = 40)
        val socket = bridgeFor("weather-app").terminal("weather-app")
        socket.sendResize(60, 20)
        socket.snapshot()
        tmux.attachDesk("weather-app", 100, 30)
        eventuallyEquals("the desk attached and took the size", 100 to 30) { tmux.windowSize("weather-app") }
        socket.sendResize(70, 25)
        eventuallyEquals("the phone holds the size", 70 to 25) { tmux.windowSize("weather-app") }

        socket.close()

        eventuallyEquals("the desk's size wins, not the size from before", 100 to 30) {
            tmux.windowSize("weather-app")
        }
    }

    @Test
    fun a_window_switch_shows_only_a_window_of_the_session_and_never_moves_the_desk() {
        tmux.newSession("weather-app")
        tmux.tmux("rename-window", "-t", "=weather-app:", "shell")
        val first = currentIndex("weather-app")
        tmux.tmux("new-window", "-d", "-t", "=weather-app:", "-n", "logs", "cat")
        val second = indexOf("weather-app", "logs")
        // Another session, not on the allow-list, with more windows than weather-app has.
        tmux.newSession("docs-site")
        repeat(4) { tmux.tmux("new-window", "-d", "-t", "=docs-site:", "cat") }
        val onlyInDocs = windowIndexes("docs-site").max()
        val docsBefore = windowSizes("docs-site")
        val desk = tmux.attachDesk("weather-app", 120, 40)
        eventuallyEquals("the desk has its size", 120 to 40) { tmux.windowSize("weather-app") }
        val socket = bridgeFor("weather-app").terminal("weather-app")
        socket.sendResize(60, 20)
        socket.snapshot()

        socket.send(TerminalListWindows)
        val windows = socket.message<TerminalWindows>()
        assertEquals(listOf(TerminalWindow(first, "shell"), TerminalWindow(second, "logs")), windows.windows)

        socket.send(TerminalSelectWindow(second))
        assertEquals(TerminalWindow(second, "logs"), socket.snapshot().first.window)
        eventuallyEquals("the picked window takes the phone's size", 60 to 20) { windowSize("weather-app", second) }
        assertEquals(first, currentIndex("weather-app"), "the desk's session changed window")
        // As with one window, the desk's next keystroke gives its window the desk's size. The phone's stays.
        desk.typeSomething()
        eventuallyEquals("the desk's window has the desk's size", 120 to 40) { windowSize("weather-app", first) }
        assertEquals(60 to 20, windowSize("weather-app", second), "the phone's window kept the phone's size")

        socket.send(TerminalSelectWindow(onlyInDocs))
        assertEquals("no_such_window", socket.message<TerminalNotice>().code)
        assertEquals(docsBefore, windowSizes("docs-site"), "a window of another session changed")
        assertEquals(first, currentIndex("weather-app"))
    }

    @Test
    fun when_the_shown_window_closes_the_view_moves_to_the_session_window_and_says_so() {
        tmux.newSession("weather-app")
        val first = currentIndex("weather-app")
        tmux.tmux("new-window", "-d", "-t", "=weather-app:", "-n", "logs", "cat")
        val second = indexOf("weather-app", "logs")
        val socket = bridgeFor("weather-app").terminal("weather-app")
        socket.sendResize(60, 20)
        socket.snapshot()
        socket.send(TerminalSelectWindow(second))
        assertEquals(
            second,
            socket
                .snapshot()
                .first.window
                ?.index,
        )

        tmux.tmux("kill-window", "-t", "=weather-app:$second")

        assertEquals("window_closed", socket.message<TerminalNotice>().code)
        assertEquals(
            first,
            socket
                .snapshot()
                .first.window
                ?.index,
        )
        socket.sendKeys("still here\r".toByteArray())
        eventually("keys reach the window the view moved to") { "still here" in tmux.capture("weather-app") }
    }

    private fun TestSocket.send(message: TerminalMessage) =
        sendText(terminalJson.encodeToString(TerminalMessage.serializer(), message))

    private inline fun <reified T : TerminalMessage> TestSocket.message(): T {
        val text = until { it is Received.Text && decode(it.text) is T } as Received.Text
        return decode(text.text) as T
    }

    /** The snapshot text frame and the one binary frame that must follow it at once. */
    private fun TestSocket.snapshot(): Pair<TerminalSnapshot, ByteArray> {
        val snapshot = message<TerminalSnapshot>()
        val screen = next()
        assertIs<Received.Binary>(screen, "the screen follows its snapshot in the very next frame")
        return snapshot to screen.bytes
    }

    private fun decode(text: String) = terminalJson.decodeFromString(TerminalMessage.serializer(), text)

    private fun currentIndex(session: String) =
        tmux.tmux("display-message", "-p", "-t", "=$session:", "#{window_index}").trim().toInt()

    private fun indexOf(
        session: String,
        name: String,
    ) = tmux
        .tmux("list-windows", "-t", "=$session:", "-F", "#{window_index} #{window_name}")
        .lines()
        .first { it.endsWith(" $name") }
        .substringBefore(' ')
        .toInt()

    private fun windowIndexes(session: String) =
        tmux
            .tmux("list-windows", "-t", "=$session:", "-F", "#{window_index}")
            .lines()
            .filter(String::isNotBlank)
            .map(String::toInt)

    private fun windowSizes(session: String) =
        tmux.tmux("list-windows", "-t", "=$session:", "-F", "#{window_index} #{window_width}x#{window_height}")

    private fun windowSize(
        session: String,
        index: Int,
    ): Pair<Int, Int> {
        val parts =
            tmux
                .tmux("display-message", "-p", "-t", "=$session:$index", "#{window_width} #{window_height}")
                .trim()
                .split(' ')
        return parts[0].toInt() to parts[1].toInt()
    }
}
