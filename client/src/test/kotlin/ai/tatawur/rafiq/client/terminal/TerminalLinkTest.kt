package ai.tatawur.rafiq.client.terminal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class) // The test scheduler's clock stands in for the wall clock.
class TerminalLinkTest {
    /** Sockets the test drives: what went up and when, how many are open, and a way to end one. */
    private class FakeSockets(
        private val scope: TestScope,
    ) : TerminalPort {
        val sent = mutableListOf<String>()
        var onText: (String) -> Unit = {}
        val sentAtMs = mutableListOf<Pair<Long, Int>>()
        var open = 0
        var opens = 0
        private var down = Channel<TerminalFrame>(Channel.UNLIMITED)

        private val uplink =
            object : TerminalUplink {
                override fun sendBinary(bytes: ByteArray) {
                    sent += "bin:" + String(bytes)
                    sentAtMs += scope.currentTime to bytes.size
                }

                override fun sendText(text: String) {
                    sent += text
                    onText(text)
                }
            }

        override fun open(sessionId: String): Flow<TerminalFrame> {
            val frames = down
            return flow {
                open++
                opens++
                try {
                    emit(TerminalFrame.Opened(uplink))
                    for (frame in frames) emit(frame)
                } finally {
                    open--
                }
            }
        }

        fun push(frame: TerminalFrame) = down.trySend(frame)

        /** Ends the current socket, and gives the next one a fresh set of frames. */
        fun drop(failure: Throwable? = null) {
            down.close(failure)
            down = Channel(Channel.UNLIMITED)
        }
    }

    private companion object {
        const val PACED_BYTES_PER_SECOND = 32 * 1024
    }

    private fun resize(
        cols: Int,
        rows: Int,
    ) = """{"type":"resize","cols":$cols,"rows":$rows}"""

    private fun TestScope.attached(): Pair<FakeSockets, TerminalLink> {
        val sockets = FakeSockets(this)
        val link =
            TerminalLink(
                sockets,
                "weather-app",
                resizeDebounce = 150.milliseconds,
                timeSource = testScheduler.timeSource,
            )
        link.setViewport(CellSize(80, 24))
        return sockets to link
    }

    @Test
    fun nothing_opens_until_the_screen_has_measured_a_viewport() =
        runTest {
            val sockets = FakeSockets(this)
            val link = TerminalLink(sockets, "weather-app")
            backgroundScope.launch { link.attach().collect {} }
            runCurrent()
            assertEquals(0, sockets.opens)
            assertEquals(TerminalStatus.Connecting, link.status.value)

            link.setViewport(CellSize(80, 24))
            runCurrent()

            assertEquals(1, sockets.opens)
            assertEquals(listOf(resize(80, 24)), sockets.sent)
        }

    @Test
    fun the_viewport_goes_up_at_once_on_open_then_only_after_it_settles() =
        runTest {
            val (sockets, link) = attached()
            backgroundScope.launch { link.attach().collect {} }
            runCurrent()
            assertEquals(listOf(resize(80, 24)), sockets.sent)

            // A fold or a keyboard animation reports several sizes in a row.
            link.setViewport(CellSize(60, 30))
            advanceTimeBy(50)
            link.setViewport(CellSize(50, 35))
            advanceTimeBy(50)
            link.setViewport(CellSize(45, 40))
            advanceTimeBy(149)
            assertEquals(listOf(resize(80, 24)), sockets.sent)

            advanceTimeBy(2)
            assertEquals(listOf(resize(80, 24), resize(45, 40)), sockets.sent)
        }

    @Test
    fun a_long_paste_goes_up_paced_under_the_bridge_input_limit() =
        runTest {
            val (sockets, link) = attached()
            backgroundScope.launch { link.attach().collect {} }
            runCurrent()

            val paste = "\u001b[200~" + "x".repeat(100_000) + "\u001b[201~"
            link.sendKeys(paste.toByteArray())
            advanceTimeBy(10_000)

            val frames = sockets.sentAtMs
            assertTrue(frames.size >= 5)
            assertEquals(
                paste,
                sockets.sent.filter { it.startsWith("bin:") }.joinToString("") { it.removePrefix("bin:") },
            )
            // In any second, at most about 32 KiB plus one frame has gone up: half the bridge's 64 KiB/s.
            for ((startMs, _) in frames) {
                val inSecond = frames.filter { it.first in startMs until startMs + 1_000 }.sumOf { it.second }
                assertTrue(
                    inSecond <= PACED_BYTES_PER_SECOND + 8 * 1024,
                    "$inSecond B in 1 s",
                )
            }
        }

    @Test
    fun leaving_terminal_closes_the_socket() {
        // The toggle, back, the cover screen and the app going to the background all stop the
        // screen's collection of attach(). This is what each of them comes down to.
        runTest {
            val (sockets, link) = attached()
            val view = backgroundScope.launch { link.attach().collect {} }
            runCurrent()
            assertEquals(1, sockets.open)
            assertEquals(TerminalStatus.Live, link.status.value)

            view.cancel()
            runCurrent()

            assertEquals(0, sockets.open)
            assertEquals(TerminalStatus.Detached, link.status.value)
            link.sendKeys("ls".toByteArray())
            runCurrent()
            assertEquals(listOf(resize(80, 24)), sockets.sent)
        }
    }

    @Test
    fun a_dropped_socket_ends_the_view_and_nothing_more_goes_up() =
        runTest {
            val (sockets, link) = attached()
            val view = backgroundScope.launch { link.attach().collect {} }
            runCurrent()
            link.sendKeys("a".toByteArray())
            runCurrent()

            sockets.drop(IOException("connection reset"))
            runCurrent()

            assertTrue(view.isCompleted)
            assertEquals(0, sockets.open)
            assertIs<TerminalStatus.Failed>(link.status.value)
            link.sendKeys("b".toByteArray())
            link.setViewport(CellSize(70, 20))
            advanceTimeBy(1_000)
            assertEquals(listOf(resize(80, 24), "bin:a"), sockets.sent)
        }

    @Test
    fun falling_behind_the_pane_reopens_the_socket_for_a_fresh_snapshot() =
        runTest {
            val (sockets, link) = attached()
            val events = mutableListOf<TerminalEvent>()
            backgroundScope.launch { link.attach().collect { events += it } }
            runCurrent()

            sockets.drop(TerminalOverflowException())
            runCurrent()
            assertEquals(1, sockets.opens, "it waits before it reopens")
            advanceTimeBy(501)
            sockets.push(TerminalFrame.Text("""{"type":"snapshot","cols":80,"rows":24,"cursor_x":0,"cursor_y":0}"""))
            sockets.push(TerminalFrame.Binary("fresh".toByteArray()))
            runCurrent()

            assertEquals(2, sockets.opens)
            assertEquals(1, sockets.open)
            assertEquals(TerminalStatus.Live, link.status.value)
            assertIs<TerminalEvent.Snapshot>(events.single())
            // The new socket gets the viewport at once, like the first.
            assertEquals(listOf(resize(80, 24), resize(80, 24)), sockets.sent)
        }

    @Test
    fun a_phone_that_keeps_falling_behind_backs_off_then_stops_with_failed() =
        runTest {
            var opens = 0
            // Every socket overflows a moment after it opens.
            val behind =
                TerminalPort {
                    flow {
                        opens++
                        delay(1)
                        throw TerminalOverflowException()
                    }
                }
            val link = TerminalLink(behind, "weather-app", timeSource = testScheduler.timeSource)
            link.setViewport(CellSize(80, 24))
            backgroundScope.launch { link.attach().collect {} }

            advanceTimeBy(30_000)

            // Open, wait 0.5 s, open, wait 1 s, open: the third overflow in 30 s stops it.
            assertEquals(3, opens)
            assertEquals(TerminalStatus.Failed(TerminalLink.FELL_BEHIND), link.status.value)
        }

    @Test
    fun a_viewport_change_right_after_the_socket_opens_still_goes_up() =
        runTest {
            val (sockets, link) = attached()
            // The layout settles at a new size while the open is still being handled.
            sockets.onText = { if (it == resize(80, 24)) link.setViewport(CellSize(60, 30)) }
            backgroundScope.launch { link.attach().collect {} }
            runCurrent()

            advanceTimeBy(200)

            assertEquals(listOf(resize(80, 24), resize(60, 30)), sockets.sent)
        }

    @Test
    fun the_bridge_ending_the_view_keeps_its_reason() =
        runTest {
            val (sockets, link) = attached()
            val events = mutableListOf<TerminalEvent>()
            backgroundScope.launch { link.attach().collect { events += it } }
            runCurrent()

            sockets.push(TerminalFrame.Text("""{"type":"ended","reason":"The pane closed."}"""))
            sockets.drop()
            runCurrent()

            assertEquals(TerminalStatus.Ended("The pane closed."), link.status.value)
            assertEquals(listOf<TerminalEvent>(TerminalEvent.Ended("The pane closed.")), events)
        }

    @Test
    fun pane_output_is_filtered_before_the_emulator_sees_it() =
        runTest {
            val (sockets, link) = attached()
            val written = StringBuilder()
            backgroundScope.launch {
                link.attach().collect { if (it is TerminalEvent.Output) written.append(String(it.bytes)) }
            }
            runCurrent()

            sockets.push(TerminalFrame.Binary("ok \u001b]52;c;eA==\u0007done".toByteArray()))
            runCurrent()

            assertEquals("ok done", written.toString())
        }
}
