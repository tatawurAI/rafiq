package ai.tatawur.rafiq.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** Guard 9: a passed check lets you answer approvals for a short while only, and never across the background. */
class UnlockWindowTest {
    @Test
    fun a_passed_check_lasts_the_window_and_then_asks_again() {
        val time = TestTimeSource()
        val window = UnlockWindow(30.seconds, time)
        assertEquals(false, window.isOpen)

        window.passed()
        time += 29.seconds
        assertEquals(true, window.isOpen)

        time += 1.seconds
        assertEquals(false, window.isOpen)
    }

    @Test
    fun going_to_the_background_ends_the_window_at_once() {
        val window = UnlockWindow(30.seconds, TestTimeSource())
        window.passed()

        window.close()

        assertEquals(false, window.isOpen)
    }
}
