package ai.tatawur.rafiq.client.terminal

import ai.tatawur.rafiq.client.CommandResult
import ai.tatawur.rafiq.protocol.api.TerminalHistory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Scrollback never touches the desk: history is read, and a full-screen program is scrolled with keys (#41). */
class ScrollbackTest {
    private val shell =
        TerminalHistory("\u001b[32m$ ls\u001b[0m\r\nREADME.md\n\u001b]8;;https://example.ts.net\u0007link\n\n", 3, 80)
    private val fullScreen = TerminalHistory("", 0, 80, alternateScreen = true)

    private fun TestScope.scrollback(
        history: TerminalHistory,
        sent: MutableList<String>,
    ): Scrollback {
        // The bytes go through the same allow-list as typed keys, so the bridge accepts them.
        val keys = KeyUplink()
        return Scrollback(backgroundScope, { CommandResult.Ok(history) }) { bytes ->
            keys.frames(bytes).forEach { sent += String(it) }
        }
    }

    @Test
    fun a_swipe_toward_older_lines_loads_then_shows_the_history_as_plain_text() =
        runTest {
            val sent = mutableListOf<String>()
            val answer = CompletableDeferred<CommandResult<TerminalHistory>>()
            val scrollback = Scrollback(backgroundScope, { answer.await() }) { sent += String(it) }

            scrollback.gestureStarted()
            scrollback.step(older = true)
            testScheduler.runCurrent()
            assertEquals(ScrollbackState.Loading, scrollback.state.value)

            answer.complete(CommandResult.Ok(shell))
            testScheduler.runCurrent()

            assertEquals(ScrollbackState.Showing(listOf("$ ls", "README.md", "link")), scrollback.state.value)
            assertEquals(emptyList(), sent)
        }

    @Test
    fun new_output_keeps_the_overlay_open_and_offers_new_output_until_you_go_back_to_live() =
        runTest {
            // An agent's spinner redraws all the time, so output alone must never close the history.
            val scrollback = scrollback(shell, mutableListOf())
            scrollback.gestureStarted()
            scrollback.step(older = true)
            testScheduler.runCurrent()

            scrollback.onOutput()

            val shown = assertIs<ScrollbackState.Showing>(scrollback.state.value)
            assertEquals(true, shown.newOutput)

            scrollback.backToLive()
            assertEquals(ScrollbackState.Live, scrollback.state.value)
        }

    @Test
    fun on_the_alternate_screen_a_swipe_sends_page_up_and_opens_no_overlay() =
        runTest {
            val sent = mutableListOf<String>()
            val scrollback = scrollback(fullScreen, sent)

            scrollback.gestureStarted()
            repeat(Scrollback.STEPS_PER_PAGE + 1) { scrollback.step(older = true) }
            testScheduler.runCurrent()

            assertEquals(listOf("\u001b[5~", "\u001b[5~"), sent)
            assertEquals(ScrollbackState.Live, scrollback.state.value)
        }

    @Test
    fun with_mouse_mode_on_a_swipe_turns_the_wheel_and_the_wheel_passes_the_key_allow_list() =
        runTest {
            val sent = mutableListOf<String>()
            val scrollback = scrollback(fullScreen, sent)
            scrollback.onSnapshot(mouse = true)

            scrollback.gestureStarted()
            scrollback.step(older = true)
            testScheduler.runCurrent()
            scrollback.step(older = false)

            assertEquals(listOf("\u001b[<64;1;1M", "\u001b[<65;1;1M"), sent)
        }

    @Test
    fun history_that_cannot_be_read_says_so_and_keeps_the_live_screen() =
        runTest {
            val scrollback = Scrollback(backgroundScope, { CommandResult.Offline }) { }

            scrollback.gestureStarted()
            scrollback.step(older = true)
            testScheduler.runCurrent()

            assertIs<ScrollbackState.Failed>(scrollback.state.value)
        }

    @Test
    fun invisible_controls_in_the_history_show_as_escapes() {
        val lines = historyLines("rm\u202Efr.txt\r\na\u200Bb\u0007\n")

        assertEquals(listOf("rm<U+202E>fr.txt", "a<U+200B>b<U+0007>"), lines)
    }

    @Test
    fun a_rate_limited_read_keeps_loading_and_tries_once_more_after_the_wait_capped_at_five_seconds() =
        runTest {
            val answers = ArrayDeque(listOf(CommandResult.RateLimited(retryAfterMs = 60_000), CommandResult.Ok(shell)))
            val scrollback = Scrollback(backgroundScope, { answers.removeFirst() }) { }

            scrollback.gestureStarted()
            scrollback.step(older = true)
            testScheduler.advanceTimeBy(Scrollback.MAX_RETRY_WAIT.inWholeMilliseconds - 1)
            assertEquals(ScrollbackState.Loading, scrollback.state.value)

            testScheduler.advanceTimeBy(2)
            assertIs<ScrollbackState.Showing>(scrollback.state.value)
        }

    @Test
    fun a_second_rate_limited_read_says_too_many_reads() =
        runTest {
            var reads = 0
            val scrollback =
                Scrollback(backgroundScope, {
                    reads++
                    CommandResult.RateLimited(retryAfterMs = 1_000)
                }) { }

            scrollback.gestureStarted()
            scrollback.step(older = true)
            testScheduler.advanceTimeBy(1_001)

            assertEquals(ScrollbackState.Failed(CommandResult.RateLimited(1_000)), scrollback.state.value)
            assertEquals(2, reads)
        }
}
