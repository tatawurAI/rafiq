package ai.tatawur.rafiq.bridge.tmux

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** How often a device may read scrollback, and how many reads run at once. */
class HistoryLimitsTest {
    @Test
    fun a_device_gets_its_burst_then_waits_for_a_token_while_another_device_is_not_held_up() {
        var now = 0L
        val limits = HistoryLimits(burst = 2, perSecond = 0.5, maxAtOnce = 10) { now }

        repeat(2) { assertNotNull(limits.tryStart("phone")).close() }
        assertNull(limits.tryStart("phone"), "a third read in the same moment was allowed")
        assertNotNull(limits.tryStart("tablet")).close()

        now += 2_000_000_000L
        assertNotNull(limits.tryStart("phone"), "a token did not come back after 2 s")
    }

    @Test
    fun only_so_many_reads_run_at_once_and_a_finished_read_frees_its_place() {
        val limits = HistoryLimits(burst = 10, perSecond = 1.0, maxAtOnce = 2) { 0L }
        val first = assertNotNull(limits.tryStart("phone"))
        assertNotNull(limits.tryStart("tablet"))

        assertNull(limits.tryStart("laptop"), "a third read ran at once")

        first.close()
        first.close()
        assertNotNull(limits.tryStart("laptop"), "a finished read did not free its place")
        assertNull(limits.tryStart("laptop"), "closing one read twice freed two places")
    }
}
