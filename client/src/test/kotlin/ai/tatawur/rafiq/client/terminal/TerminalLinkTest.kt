package ai.tatawur.rafiq.client.terminal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
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
    /** A socket the test drives: what went up, whether it is open, and a way to drop it. */
    private class FakeSocket : TerminalPort {
        val sent = mutableListOf<String>()
        var open = false
        var closes = 0
        private val down = Channel<TerminalFrame>(Channel.UNLIMITED)

        private val uplink =
            object : TerminalUplink {
                override fun sendBinary(bytes: ByteArray) {
                    sent += "bin:" + String(bytes)
                }

                override fun sendText(text: String) {
                    sent += text
                }
            }

        override fun open(sessionId: String): Flow<TerminalFrame> =
            flow {
                open = true
                try {
                    emit(TerminalFrame.Opened(uplink))
                    for (frame in down) emit(frame)
                } finally {
                    open = false
                    closes++
                }
            }

        fun push(frame: TerminalFrame) = down.trySend(frame)

        fun drop(failure: Throwable? = null) = down.close(failure)
    }

    private fun resize(
        cols: Int,
        rows: Int,
    ) = """{"type":"resize","cols":$cols,"rows":$rows}"""

    @Test
    fun the_viewport_goes_up_at_once_on_open_then_only_after_it_settles() =
        runTest {
            val socket = FakeSocket()
            val link = TerminalLink(socket, "weather-app", resizeDebounce = 150.milliseconds)
            link.setViewport(CellSize(80, 24))
            backgroundScope.launch { link.attach().collect {} }
            runCurrent()
            assertEquals(listOf(resize(80, 24)), socket.sent)

            // A fold or a keyboard animation reports several sizes in a row.
            link.setViewport(CellSize(60, 30))
            advanceTimeBy(50)
            link.setViewport(CellSize(50, 35))
            advanceTimeBy(50)
            link.setViewport(CellSize(45, 40))
            advanceTimeBy(149)
            assertEquals(listOf(resize(80, 24)), socket.sent)

            advanceTimeBy(2)
            assertEquals(listOf(resize(80, 24), resize(45, 40)), socket.sent)
        }

    @Test
    fun leaving_terminal_closes_the_socket() {
        // The toggle, back, the cover screen and the app going to the background all stop the
        // screen's collection of attach(). This is what each of them comes down to.
        runTest {
            val socket = FakeSocket()
            val link = TerminalLink(socket, "weather-app")
            val view = backgroundScope.launch { link.attach().collect {} }
            runCurrent()
            assertTrue(socket.open)
            assertEquals(TerminalStatus.Live, link.status.value)

            view.cancel()
            runCurrent()

            assertEquals(false, socket.open)
            assertEquals(1, socket.closes)
            assertEquals(TerminalStatus.Detached, link.status.value)
            link.sendKeys("ls".toByteArray())
            assertEquals(emptyList(), socket.sent)
        }
    }

    @Test
    fun a_dropped_socket_ends_the_view_and_nothing_more_goes_up() =
        runTest {
            val socket = FakeSocket()
            val link = TerminalLink(socket, "weather-app")
            val view = backgroundScope.launch { link.attach().collect {} }
            runCurrent()
            link.sendKeys("a".toByteArray())

            socket.drop(IOException("connection reset"))
            runCurrent()

            assertTrue(view.isCompleted)
            assertEquals(false, socket.open)
            assertIs<TerminalStatus.Failed>(link.status.value)
            link.sendKeys("b".toByteArray())
            link.setViewport(CellSize(70, 20))
            advanceTimeBy(1_000)
            assertEquals(listOf("bin:a"), socket.sent)
        }

    @Test
    fun the_bridge_ending_the_view_keeps_its_reason() =
        runTest {
            val socket = FakeSocket()
            val link = TerminalLink(socket, "weather-app")
            val events = mutableListOf<TerminalEvent>()
            backgroundScope.launch { link.attach().collect { events += it } }
            runCurrent()

            socket.push(TerminalFrame.Text("""{"type":"ended","reason":"The pane closed."}"""))
            socket.drop()
            runCurrent()

            assertEquals(TerminalStatus.Ended("The pane closed."), link.status.value)
            assertEquals(listOf<TerminalEvent>(TerminalEvent.Ended("The pane closed.")), events)
        }

    @Test
    fun pane_output_is_filtered_before_the_emulator_sees_it() =
        runTest {
            val socket = FakeSocket()
            val link = TerminalLink(socket, "weather-app")
            val written = StringBuilder()
            backgroundScope.launch {
                link.attach().collect { if (it is TerminalEvent.Output) written.append(String(it.bytes)) }
            }
            runCurrent()

            socket.push(TerminalFrame.Binary("ok \u001b]52;c;eA==\u0007done".toByteArray()))
            runCurrent()

            assertEquals("ok done", written.toString())
        }
}
