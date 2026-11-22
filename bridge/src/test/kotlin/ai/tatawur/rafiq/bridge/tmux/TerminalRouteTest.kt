package ai.tatawur.rafiq.bridge.tmux

import ai.tatawur.rafiq.protocol.api.TerminalMessage
import ai.tatawur.rafiq.protocol.api.TerminalSnapshot
import ai.tatawur.rafiq.protocol.api.TerminalWindow
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
        return RealBridge(dir.resolve("data-${opened++}"), openGate(settings, server)).also { bridge = it }
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
        val views = tmux.sessionNames().filter { it.startsWith(TmuxServer.VIEW_PREFIX) }
        assertTrue(views.all { Regex("rafiq-view-[0-9a-f]{6}-[0-9a-f]{8}").matches(it) }, "view names: $views")

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
    fun a_size_the_desk_took_is_announced_once_with_one_fresh_snapshot() {
        tmux.newSession("weather-app")
        val desk = tmux.attachDesk("weather-app", 120, 40)
        val socket = bridgeFor("weather-app").terminal("weather-app")
        socket.sendResize(60, 20)
        socket.snapshot()
        eventuallyEquals("the phone has the size", 60 to 20) { tmux.windowSize("weather-app") }

        // The desk takes the size back in steps that all end at one size, so tmux reports several layout changes.
        desk.send("refresh-client -C 100x30")
        desk.typeSomething()
        desk.typeSomething()
        desk.typeSomething()
        eventuallyEquals("the desk has the size", 100 to 30) { tmux.windowSize("weather-app") }

        val sizes = mutableListOf<String>()
        var snapshots = 0
        val deadline = System.nanoTime() + SETTLE_MS * 1_000_000
        while (System.nanoTime() < deadline) {
            val item = runCatching { socket.next(1) }.getOrNull() ?: continue
            if (item is Received.Text && "\"type\":\"size\"" in item.text) sizes += item.text
            if (item is Received.Text && "\"type\":\"snapshot\"" in item.text) snapshots++
        }
        assertEquals(1, sizes.size, "the new size was announced more than once: $sizes")
        assertEquals(1, snapshots, "more than one snapshot followed")
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
    fun the_snapshot_names_the_mirrored_window_and_a_switch_at_the_desk_changes_it_on_the_next_snapshot() {
        tmux.newSession("weather-app")
        tmux.tmux("rename-window", "-t", "=weather-app:", "api server")
        // A user's tmux config may number windows from 1, so ask tmux instead of assuming 0.
        val index = tmux.tmux("display-message", "-p", "-t", "=weather-app:", "#{window_index}").trim().toInt()
        val socket = bridgeFor("weather-app").terminal("weather-app")
        socket.sendResize(80, 24)
        assertEquals(TerminalWindow(index, "api server"), socket.snapshot().first.window)

        // The mirrored pane stays put when the desk moves to another window. A new window gets a new number.
        tmux.tmux("new-window", "-t", "=weather-app:", "-n", "docs", "cat")
        socket.sendResize(90, 30)
        assertEquals(TerminalWindow(index, "api server"), socket.snapshot().first.window)
        tmux.tmux("rename-window", "-t", "=weather-app:$index", "renamed")
        socket.sendResize(100, 30)
        assertEquals(TerminalWindow(index, "renamed"), socket.snapshot().first.window)
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
    fun a_pane_tmux_paused_is_continued_and_followed_by_a_fresh_snapshot() {
        tmux.newSession("flood", "yes")
        // Pause as soon as any output is waiting, and leave the bridge's own queue limit out of it.
        val limits = TerminalLimits(pauseAfterSeconds = 0, maxPendingOutputBytes = Int.MAX_VALUE)
        val socket = bridgeFor("flood", limits = limits).terminal("flood")
        socket.sendResize(80, 24)
        socket.snapshot()

        // A pause drops what tmux produced meanwhile. The screen that follows the continue replaces it.
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
    fun an_open_view_ends_and_detaches_when_a_scan_no_longer_allows_its_session() {
        tmux.newSession("weather-app")
        val bridge = bridgeFor("weather-app")
        val socket = bridge.terminal("weather-app")
        socket.sendResize(60, 20)
        socket.snapshot()

        bridge.gate!!.updateOpenable(emptySet())

        val ended = socket.until(seconds = 5) { it is Received.Text && "\"ended\"" in it.text } as Received.Text
        assertTrue("no longer allowed" in ended.text, ended.text)
        assertIs<Received.Closed>(socket.until(seconds = 5) { it is Received.Closed })
        eventually("the view session is gone", check = ::noViewSession)
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
    fun a_revoked_phone_that_stopped_reading_cannot_type_after_the_revoke() {
        val keys = dir.resolve("keys.txt")
        // The pane floods the phone with output and keeps everything typed in a file.
        tmux.newSession("flood", "sh -c 'yes & cat > $keys'")
        val bridge = bridgeFor("flood")
        val socket = bridge.terminal("flood")
        socket.sendResize(80, 24)
        socket.snapshot()
        socket.sendKeys("BEFORE\r".toByteArray())
        eventually("the first line reaches the pane") { Files.exists(keys) && "BEFORE" in Files.readString(keys) }

        // The phone stops reading, so the server's own messages to it back up, and keeps typing.
        socket.stall()
        Thread.sleep(STALL_MS)
        val revoke = Thread { runBlocking { bridge.connections.closeAll("phone", "device_revoked") } }.apply { start() }
        Thread.sleep(REVOKE_SETTLE_MS)
        socket.sendKeys("AFTER\r".toByteArray())
        revoke.join(30_000)

        Thread.sleep(REVOKE_SETTLE_MS)
        assertFalse("AFTER" in Files.readString(keys), "keys typed after the revoke reached the pane")
    }

    @Test
    fun a_repeat_of_the_current_size_is_free_and_a_flood_of_new_sizes_closes_the_socket() {
        tmux.newSession("weather-app")
        val socket = bridgeFor("weather-app").terminal("weather-app")
        socket.sendResize(60, 20)
        socket.snapshot()

        repeat(40) { socket.sendResize(60, 20) }
        assertFalse(socket.closedWithin(1), "the same size again was counted")

        // The server closes part way, so a later send may find the socket closed.
        repeat(150) { runCatching { socket.sendResize(61 + it, 20) } }
        assertTrue(socket.closedWithin(10), "a flood of sizes was not stopped")
        eventually("the view session is gone", check = ::noViewSession)
    }

    @Test
    fun a_text_frame_counts_toward_the_input_limits_like_keys() {
        tmux.newSession("weather-app")
        val socket = bridgeFor("weather-app", limits = TerminalLimits(maxFrameBytes = 64)).terminal("weather-app")
        socket.sendResize(60, 20)
        socket.snapshot()

        socket.sendText("x".repeat(65))

        assertTrue(socket.closedWithin(10), "an oversized text frame was accepted")
    }

    @Test
    fun a_burst_of_sizes_like_a_fold_animation_ends_at_the_last_size_and_does_not_end_the_view() {
        tmux.newSession("weather-app")
        val socket = bridgeFor("weather-app").terminal("weather-app")
        socket.sendResize(60, 20)
        socket.snapshot()

        // 40 sizes at once: more than the burst, far fewer than abuse. Only the newest has to arrive.
        repeat(40) { socket.sendResize(61 + it, 20) }

        eventuallyEquals("the window ends at the last size", 100 to 20) { tmux.windowSize("weather-app") }
        assertFalse(socket.closedWithin(1), "an animation ended the view")
    }

    @Test
    fun a_device_that_opens_terminals_too_often_is_refused_for_a_moment() {
        tmux.newSession("weather-app")
        val bridge = bridgeFor("weather-app", limits = TerminalLimits(opensBurst = 2, opensPerMinute = 1.0))
        repeat(2) {
            val socket = bridge.terminal("weather-app", "a")
            socket.sendResize(60, 20)
            socket.snapshot()
            socket.close()
        }

        val refused = bridge.terminal("weather-app", "a").until { it is Received.Closed } as Received.Closed

        assertEquals(1013, refused.code)
        assertTrue("too many terminal opens" in refused.reason, refused.reason)
        // Another device is not held up by it.
        val other = bridge.terminal("weather-app", "b")
        other.sendResize(60, 20)
        other.snapshot()
    }

    @Test
    fun a_terminal_closes_at_its_maximum_age_even_while_output_keeps_flowing() {
        tmux.newSession("ticker", "bash -c 'while :; do echo tick; sleep 0.1; done'")
        val bridge = bridgeFor("ticker", limits = TerminalLimits(maxAge = 1500.milliseconds))
        val socket = bridge.terminal("ticker")
        socket.sendResize(60, 20)
        socket.snapshot()

        assertTrue(socket.closedWithin(15), "a busy terminal stayed open past its maximum age")
        eventually("the view session is gone", check = ::noViewSession)
    }

    @Test
    fun a_held_escape_is_never_sent_after_the_key_that_followed_it() {
        val keys = dir.resolve("keys.bin")
        tmux.newSession("weather-app", "sh -c 'stty raw -echo; cat > $keys'")
        val socket = bridgeFor("weather-app").terminal("weather-app")
        socket.sendResize(60, 20)
        socket.snapshot()

        // Each Escape is followed by a key right at the moment the wait for more of a sequence runs out.
        repeat(ROUNDS) {
            socket.sendKeys(byteArrayOf(0x1b))
            Thread.sleep(ESCAPE_EDGE_MS)
            socket.sendKeys("x".toByteArray())
        }

        eventually({ "all keys arrived, file has ${if (Files.exists(keys)) Files.size(keys) else -1} bytes" }) {
            Files.exists(keys) && Files.size(keys) >= ROUNDS * 2L
        }
        val text = Files.readAllBytes(keys).toString(Charsets.ISO_8859_1)
        assertFalse("x\u001b" in text.replace("\u001bx", ""), "an Escape arrived after the key that followed it")
    }

    @Test
    fun a_socket_that_closed_before_its_first_size_is_never_attached() {
        tmux.newSession("weather-app")
        val calls = dir.resolve("tmux-calls.txt")
        val wrapper = dir.resolve("tmux-wrapper")
        Files.writeString(wrapper, "#!/bin/sh\necho \"\$@\" >> '$calls'\nexec tmux \"\$@\"\n")
        wrapper.toFile().setExecutable(true)
        val bridge = bridgeFor("weather-app", server = TmuxServer(tmux.socket, wrapper.toString()))

        bridge.terminal("weather-app").close()

        Thread.sleep(SETTLE_MS)
        assertFalse("new-session" in Files.readString(calls), "a view was attached at a guessed size")
    }

    @Test
    fun a_phone_watching_output_or_resizing_is_not_idle_but_a_silent_one_is() {
        tmux.newSession("ticker", "bash -c 'while :; do echo tick; sleep 0.1; done'")
        val limits = TerminalLimits(idleTimeout = 600.milliseconds)
        tmux.newSession("quiet")
        val bridge = bridgeFor("ticker", "quiet", limits = limits)
        val watching = bridge.terminal("ticker")
        watching.sendResize(60, 20)
        watching.snapshot()
        assertFalse(watching.closedWithin(3), "a phone that is watching output was closed as idle")

        val resizing = bridge.terminal("quiet")
        resizing.sendResize(60, 20)
        resizing.snapshot()
        repeat(8) {
            // Longer than the idle limit in all, though each gap is shorter.
            assertFalse(resizing.closedWithinMs(300), "a phone that kept resizing was closed as idle")
            resizing.sendResize(61 + it, 20)
        }
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
        const val ROUNDS = 120
        const val ESCAPE_EDGE_MS = 59L
        const val STALL_MS = 5000L
        const val REVOKE_SETTLE_MS = 400L
        const val SETTLE_MS = 2500L
    }
}
