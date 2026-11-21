package ai.tatawur.rafiq.ui.fold

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FoldSplitTest {
    // Z Flip main screen: 2520 px tall, the fold reported as a line at mid-height.
    @Test
    fun the_band_is_centred_on_a_hinge_reported_as_a_line_and_the_panes_take_the_rest() {
        val split = splitAtHinge(0, 2520, Hinge(1260, 1260), minBandPx = 48, minPanePx = 360)

        assertEquals(PaneSplit(topPx = 1236, bandPx = 48, bottomPx = 1236), split)
    }

    @Test
    fun window_coordinates_are_shifted_into_the_container_and_a_thick_hinge_sets_the_band() {
        val split = splitAtHinge(100, 2420, Hinge(1250, 1310), minBandPx = 48, minPanePx = 360)

        assertEquals(PaneSplit(topPx = 1150, bandPx = 60, bottomPx = 1210), split)
    }

    @Test
    fun a_hinge_that_leaves_no_room_for_a_pane_keeps_one_column() {
        assertNull(splitAtHinge(0, 2520, Hinge(200, 200), minBandPx = 48, minPanePx = 360))
        assertNull(splitAtHinge(0, 1000, Hinge(1800, 1800), minBandPx = 48, minPanePx = 360))
    }
}
