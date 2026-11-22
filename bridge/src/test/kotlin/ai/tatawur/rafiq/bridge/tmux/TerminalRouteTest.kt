package ai.tatawur.rafiq.bridge.tmux

import ai.tatawur.rafiq.protocol.api.TerminalMessage
import ai.tatawur.rafiq.protocol.api.TerminalSnapshot
import ai.tatawur.rafiq.protocol.api.terminalJson
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** The terminal socket against a real tmux on a throwaway socket. */
@Timeout(120)
class TerminalRouteTest {
    @TempDir
    lateinit var dir: Path

    private val tmux = TestTmux()
    private var bridge: RealBridge? = null
    private var opened = 0

    @AfterEach
    fun cleanUp() {
        bridge?.close()
        tmux.close()
    }

    private fun bridgeFor(
        vararg sessions: String,
        enabled: Boolean = true,
        limits: TerminalLimits = TerminalLimits(),
        server: TmuxServer = tmux.server,
    ): RealBridge {
        val settings = TerminalSettings(enabled, sessions.toSet(), tmux.socket, limits)
        return RealBridge(dir.resolve("data-${opened++}"), TerminalGate(settings, server)).also { bridge = it }
    }

    /** The snapshot text frame and the one binary frame that must follow it at once. */
    private fun TestSocket.snapshot(): Pair<TerminalSnapshot, ByteArray> {
        val text = until { it is Received.Text && "\"snapshot\"" in it.text } as Received.Text
        val message = terminalJson.decodeFromString(TerminalMessage.serializer(), text.text)
        assertIs<TerminalSnapshot>(message)
        val screen = next()
        assertIs<Received.Binary>(screen, "the screen follows its snapshot in the very next frame")
        return message to screen.bytes
    }

    private fun noViewSession() = tmux.sessionNames().none { it.startsWith(TmuxServer.VIEW_PREFIX) }

    @Test
    fun the_view_takes_the_phone_size_and_leaving_hands_the_desk_its_size_back() {
        tmux.newSession("weather-app")
        val desk = tmux.attachDesk("weather-app", 120, 40)
        eventuallyEquals("the desk claims its size", 120 to 40) { tmux.windowSize("weather-app") }
        val socket = bridgeFor("weather-app").terminal("weather-app")
        socket.sendResize(60, 20)
        socket.snapshot()
        eventuallyEquals("the window takes the phone's size", 60 to 20) { tmux.windowSize("weather-app") }
        assertFalse(noViewSession(), "a view session exists while the socket is open")

        // The desk types, so the window follows the desk. A phone resize must win it back, not only ask.
        desk.typeSomething()
        eventuallyEquals("the desk wins the size while it types", 120 to 40) { tmux.windowSize("weather-app") }
        socket.sendResize(70, 25)
        eventuallyEquals("a later resize wins the size back", 70 to 25) { tmux.windowSize("weather-app") }

        // And so must the phone typing, once the throttle since the last claim has passed.
        desk.typeSomething()
        eventuallyEquals("the desk wins the size again", 120 to 40) { tmux.windowSize("weather-app") }
        Thread.sleep(CLAIM_THROTTLE_MS)
        socket.sendKeys("x".toByteArray())
        eventuallyEquals("typing on the phone wins the size back", 70 to 25) { tmux.windowSize("weather-app") }

        socket.close()

        eventuallyEquals("the desk gets its size back", 120 to 40) { tmux.windowSize("weather-app") }
        eventually("no view session remains", check = ::noViewSession)
    }

    @Test
    fun a_resize_to_an_absurd_size_is_clamped_on_the_first_resize_and_on_later_ones() {
        tmux.newSession("weather-app")
        val socket = bridgeFor("weather-app").terminal("weather-app")
        socket.sendResize(100_000, 100_000)
        socket.snapshot()
        eventuallyEquals("the first resize is clamped", 500 to 200) { tmux.windowSize("weather-app") }
        socket.sendResize(30, 10)
        eventuallyEquals("a small later resize is followed", 30 to 10) { tmux.windowSize("weather-app") }
        socket.sendResize(100_000, 100_000)
        eventuallyEquals("a later resize is clamped too", 500 to 200) { tmux.windowSize("weather-app") }
    }

    @Test
    fun the_snapshot_and_the_stream_join_with_no_gap_and_no_duplicate() {
        tmux.newSession("ticker", "bash -c 'i=0; while :; do i=\$((i+1)); echo L\$i; sleep 0.01; done'")
        val bridge = bridgeFor("ticker")
        repeat(5) {
            val socket = bridge.terminal("ticker")
            socket.sendResize(80, 24)
            val (_, screen) = socket.snapshot()
            val last =
                SCREEN_LINE
                    .findAll(String(screen, Charsets.ISO_8859_1))
                    .last()
                    .groupValues[1]
                    .toInt()
            val after = StringBuilder()
            while (LINE.findAll(after).count() < 30) {
                val frame = socket.next()
                if (frame is Received.Binary) after.append(String(frame.bytes, Charsets.ISO_8859_1))
            }
            val numbers = LINE.findAll(after).map { it.groupValues[1].toInt() }.toList()
            assertEquals((last + 1..last + numbers.size).toList(), numbers, "the stream continues the snapshot")
            socket.close()
        }
    }

    @Test
    fun a_phone_that_falls_behind_gets_a_fresh_snapshot_and_the_stream_goes_on() {
        tmux.newSession("flood", "yes")
        // A wait of one byte: any output the phone is slower than counts as falling behind.
        val socket = bridgeFor("flood", limits = TerminalLimits(maxPendingOutputBytes = 1)).terminal("flood")
        socket.sendResize(80, 24)
        socket.snapshot()

        // Output the bridge had to drop is replaced by a screen, which the stream then continues from.
        socket.snapshot()
        assertIs<Received.Binary>(socket.until { it is Received.Binary })
    }

    @Test
    fun keys_reach_the_pane_but_terminal_replies_and_other_escape_sequences_do_not() {
        tmux.newSession("weather-app")
        val socket = bridgeFor("weather-app").terminal("weather-app")
        socket.sendResize(80, 24)
        socket.snapshot()

        // Replies a phone emulator could produce, an OSC string, and a CSI that is not a key.
        socket.sendKeys(
            "\u001b[12;40R\u001b[?62;22c\u001b]11;rgb:0000/0000/0000\u001b\\\u001b[?2004;1\$y\u001b[5;9H".toByteArray(),
        )
        socket.sendKeys("hello\r".toByteArray())

        eventually("the typed word reaches the pane") { "hello" in tmux.capture("weather-app") }
        val screen = tmux.capture("weather-app")
        for (leaked in listOf("12;40R", "62;22c", "rgb:", "2004", "5;9H")) {
            assertFalse(leaked in screen, "$leaked reached the pane:\n$screen")
        }
    }

    @Test
    fun an_idle_socket_is_closed_and_detaches() {
        tmux.newSession("weather-app")
        val bridge = bridgeFor("weather-app", limits = TerminalLimits(idleTimeout = 600.milliseconds))
        val socket = bridge.terminal("weather-app")
        socket.sendResize(60, 20)
        socket.snapshot()

        val closed = socket.until { it is Received.Closed } as Received.Closed

        assertEquals(1000, closed.code)
        eventually("the view session is gone after the idle close", check = ::noViewSession)
    }

    @Test
    fun input_over_the_size_limit_closes_the_socket() {
        tmux.newSession("weather-app")
        val socket = bridgeFor("weather-app", limits = TerminalLimits(maxFrameBytes = 64)).terminal("weather-app")
        socket.sendResize(60, 20)
        socket.snapshot()

        socket.sendKeys(ByteArray(65) { 'a'.code.toByte() })

        assertEquals(1008, (socket.until { it is Received.Closed } as Received.Closed).code)
        eventually("the view session is gone", check = ::noViewSession)
    }

    @Test
    fun a_fifth_socket_and_a_third_from_one_device_are_refused() {
        tmux.newSession("weather-app")
        val bridge = bridgeFor("weather-app")

        fun open(device: String): TestSocket {
            val socket = bridge.terminal("weather-app", device)
            socket.sendResize(60, 20)
            socket.snapshot()
            return socket
        }
        val first = listOf(open("a"), open("a"))

        // Two sockets in all: only the per-device limit can refuse a third from the same device.
        val thirdFromOneDevice = bridge.terminal("weather-app", "a")
        assertEquals(1013, (thirdFromOneDevice.until { it is Received.Closed } as Received.Closed).code)

        // Four in all: now the total limit refuses a socket from a device that has none open.
        open("b")
        open("c")
        val fifthSocket = bridge.terminal("weather-app", "d")
        assertEquals(1013, (fifthSocket.until { it is Received.Closed } as Received.Closed).code)

        // A socket that ends frees its place.
        first[0].close()
        eventually("a freed slot can be used again") {
            val again = bridge.terminal("weather-app", "a")
            // The server frees the slot a moment after the close, so an early try is refused and closed.
            runCatching { again.sendResize(60, 20) }
            again.until { it is Received.Text || it is Received.Closed } is Received.Text
        }
    }

    @Test
    fun revoking_a_device_closes_its_terminal_at_once_and_detaches_but_not_another_devices() {
        tmux.newSession("weather-app")
        val bridge = bridgeFor("weather-app")
        val revoked = bridge.terminal("weather-app", "phone-1")
        revoked.sendResize(60, 20)
        revoked.snapshot()
        val other = bridge.terminal("weather-app", "phone-2")
        other.sendResize(60, 20)
        other.snapshot()

        val closed = runBlocking { bridge.connections.closeAll("phone-1", "device_revoked") }

        assertEquals(1, closed)
        assertEquals("device_revoked", (revoked.until { it is Received.Closed } as Received.Closed).reason)
        eventually({ "only the other device's view session is left: ${tmux.sessionNames()}" }) {
            tmux.sessionNames().count { it.startsWith(TmuxServer.VIEW_PREFIX) } == 1
        }
        other.sendKeys("x".toByteArray())
        assertEquals(1, bridge.connections.openCount("phone-2"))
    }

    @Test
    fun only_the_exact_session_name_on_the_allow_list_can_be_opened() {
        tmux.newSession("weather-app-2")
        val bridge = bridgeFor("weather-app")

        // Not on the list, whatever its prefix says.
        assertRefused(bridge.terminal("weather-app-2"))
        // On the list but not running: tmux would match the prefix of weather-app-2, and the bridge must not.
        assertRefused(bridge.terminal("weather-app"))
        assertTrue(tmux.sessionNames().none { it.startsWith(TmuxServer.VIEW_PREFIX) })
        // A view session of the bridge's own is never reachable, even if someone lists its name.
        assertRefused(bridgeFor("rafiq-view-1").terminal("rafiq-view-1"))
    }

    @Test
    fun with_terminal_off_nothing_is_listed_and_no_tmux_command_runs() {
        val calls = dir.resolve("tmux-calls.txt")
        val script = dir.resolve("fake-tmux")
        Files.writeString(script, "#!/bin/sh\necho \"\$@\" >> '$calls'\nexit 1\n")
        script.toFile().setExecutable(true)
        val bridge = bridgeFor("weather-app", enabled = false, server = TmuxServer(tmux.socket, script.toString()))

        assertRefused(bridge.terminal("weather-app"))
        kotlinx.coroutines.runBlocking { bridge.tmuxSource!!.refresh() }

        assertFalse(Files.exists(calls), "tmux was run: ${if (Files.exists(calls)) Files.readString(calls) else ""}")
    }

    @Test
    fun a_socket_without_the_device_token_is_refused() {
        tmux.newSession("weather-app")
        val bridge = bridgeFor("weather-app")

        val closed = bridge.terminal("weather-app", token = null).until { it is Received.Closed } as Received.Closed
        assertTrue("401" in closed.reason, closed.reason)

        val wrong = bridge.terminal("weather-app", token = "wrong").until { it is Received.Closed } as Received.Closed
        assertTrue("401" in wrong.reason, wrong.reason)
        assertTrue(noViewSession(), "a refused socket started no tmux client")
    }

    @Test
    fun the_token_can_ride_as_a_subprotocol_and_the_server_selects_the_terminal_one() {
        tmux.newSession("weather-app")
        val socket = bridgeFor("weather-app").terminal("weather-app", viaSubprotocol = true)

        socket.sendResize(60, 20)
        socket.snapshot()

        assertEquals("rafiq.term.v1", socket.subprotocol)
    }

    private fun assertRefused(socket: TestSocket) {
        val closed = socket.until { it is Received.Closed } as Received.Closed
        assertEquals("no such session", closed.reason)
    }

    private companion object {
        val LINE = Regex("L(\\d+)\r?\n")
        val SCREEN_LINE = Regex("L(\\d+)")
        const val CLAIM_THROTTLE_MS = 1100L
    }
}
