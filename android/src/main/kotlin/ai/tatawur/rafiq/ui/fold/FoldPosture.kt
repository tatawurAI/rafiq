package ai.tatawur.rafiq.ui.fold

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowMetricsCalculator
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Where a half-opened, horizontal hinge crosses the window, as when a Z Flip stands in Flex Mode.
 *
 * @property topPx the hinge's top edge, in window pixels.
 * @property bottomPx the hinge's bottom edge, in window pixels. It equals [topPx] on phones that
 *   report the fold as a line.
 */
@Immutable
data class Hinge(
    val topPx: Int,
    val bottomPx: Int,
)

/**
 * A Z Flip's main screen half folded, for previews: the fold as a line at mid-height of a
 * 2520-pixel window, which is mid-height of an 840dp preview at density 3.
 */
internal val previewHalfFoldHinge = Hinge(topPx = 1260, bottomPx = 1260)

/**
 * The heights of the two panes and the band between them, in pixels, top to bottom.
 */
internal data class PaneSplit(
    val topPx: Int,
    val bandPx: Int,
    val bottomPx: Int,
)

/**
 * Splits a container of [containerHeightPx] that starts at [containerTopPx] in the window at
 * [hinge]. The band is centred on the hinge and is at least [minBandPx] tall, so nothing tappable
 * sits on the crease even when the hinge is reported as a line.
 *
 * @return null when the hinge leaves less than [minPanePx] for either pane, for example when it
 *   does not cross this container at all. The caller then keeps one column.
 */
internal fun splitAtHinge(
    containerTopPx: Int,
    containerHeightPx: Int,
    hinge: Hinge,
    minBandPx: Int,
    minPanePx: Int,
): PaneSplit? {
    val hingeTop = hinge.topPx - containerTopPx
    val hingeBottom = hinge.bottomPx - containerTopPx
    val bandPx = maxOf(minBandPx, hingeBottom - hingeTop)
    val topPx = (hingeTop + hingeBottom) / 2 - bandPx / 2
    val bottomPx = containerHeightPx - topPx - bandPx
    return if (topPx < minPanePx || bottomPx < minPanePx) null else PaneSplit(topPx, bandPx, bottomPx)
}

/**
 * The hinge the screens split at, or null when the phone is flat, closed, open or folded the other
 * way. [ai.tatawur.rafiq.MainActivity] provides it. Routes read it and pass it down as a parameter,
 * so the screens themselves stay previewable.
 */
val LocalHinge = compositionLocalOf<Hinge?> { null }

/**
 * Follows the window's fold state while the activity is at least started. Only a fold that is half
 * opened, horizontal and runs the full width of the window counts: a vertical fold or one that
 * crosses only part of a window in multi-window mode would split nothing useful.
 */
@Composable
fun rememberHinge(activity: Activity): Hinge? {
    val hinges =
        remember(activity) {
            WindowInfoTracker
                .getOrCreate(activity)
                .windowLayoutInfo(activity)
                .map { info ->
                    val windowWidthPx =
                        WindowMetricsCalculator
                            .getOrCreate()
                            .computeCurrentWindowMetrics(activity)
                            .bounds
                            .width()
                    info.displayFeatures
                        .filterIsInstance<FoldingFeature>()
                        .firstOrNull { fold ->
                            fold.state == FoldingFeature.State.HALF_OPENED &&
                                fold.orientation == FoldingFeature.Orientation.HORIZONTAL &&
                                fold.bounds.width() >= windowWidthPx
                        }?.let { fold -> Hinge(fold.bounds.top, fold.bounds.bottom) }
                }.distinctUntilChanged()
        }
    val hinge by hinges.collectAsStateWithLifecycle(initialValue = null)
    return hinge
}
