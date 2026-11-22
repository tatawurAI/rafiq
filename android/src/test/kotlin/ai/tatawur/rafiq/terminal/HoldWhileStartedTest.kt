package ai.tatawur.rafiq.terminal

import ai.tatawur.rafiq.client.terminal.CellSize
import ai.tatawur.rafiq.client.terminal.TerminalFrame
import ai.tatawur.rafiq.client.terminal.TerminalLink
import ai.tatawur.rafiq.client.terminal.TerminalPort
import ai.tatawur.rafiq.client.terminal.TerminalUplink
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The app going to the background closes Terminal's socket, and coming back opens a new one. Leaving
 * by back cancels the caller, which TerminalLinkTest covers.
 */
@OptIn(ExperimentalCoroutinesApi::class) // A test main dispatcher stands in for the UI thread.
class HoldWhileStartedTest {
    private val main = UnconfinedTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(main)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private class CountingPort : TerminalPort {
        var open = 0
        var opened = 0

        override fun open(sessionId: String): Flow<TerminalFrame> =
            flow {
                open++
                opened++
                try {
                    emit(TerminalFrame.Opened(NoUplink))
                    awaitCancellation()
                } finally {
                    open--
                }
            }
    }

    private object NoUplink : TerminalUplink {
        override fun sendBinary(bytes: ByteArray) = Unit

        override fun sendText(text: String) = Unit
    }

    private class Screen : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun the_background_closes_the_socket_and_coming_back_opens_a_new_one() =
        runTest(main) {
            val screen = Screen().apply { registry.currentState = Lifecycle.State.RESUMED }
            val port = CountingPort()
            val link = TerminalLink(port, "weather-app").apply { setViewport(CellSize(80, 24)) }
            val view = launch { holdWhileStarted(screen.lifecycle) { link.attach().collect {} } }
            assertEquals(1, port.open)

            screen.registry.currentState = Lifecycle.State.CREATED
            assertEquals(0, port.open)

            screen.registry.currentState = Lifecycle.State.RESUMED
            assertEquals(1, port.open)
            assertEquals(2, port.opened)
            view.cancel()
        }
}
