package ai.tatawur.rafiq.client.terminal

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The phone's queue of received pane frames is bounded, and says when it is full. */
class InboundFramesTest {
    private fun binary(size: Int) = TerminalFrame.Binary(ByteArray(size))

    @Test
    fun a_frame_past_the_count_limit_does_not_fit() {
        val queue = InboundFrames(maxFrames = 2, maxBytes = 1_000)
        assertTrue(queue.offer(binary(1)))
        assertTrue(queue.offer(binary(1)))
        assertFalse(queue.offer(binary(1)))
    }

    @Test
    fun a_frame_past_the_byte_limit_does_not_fit_until_the_view_catches_up() =
        runTest {
            val queue = InboundFrames(maxFrames = 100, maxBytes = 1_000)
            assertTrue(queue.offer(binary(600)))
            assertFalse(queue.offer(binary(600)))

            assertEquals(
                1,
                queue
                    .frames()
                    .take(1)
                    .toList()
                    .size,
            )

            assertTrue(queue.offer(binary(600)))
        }
}
