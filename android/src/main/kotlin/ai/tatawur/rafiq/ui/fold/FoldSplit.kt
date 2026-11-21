package ai.tatawur.rafiq.ui.fold

import ai.tatawur.rafiq.ui.theme.RafiqTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Lays [top] above the hinge and [bottom] below it while the phone is half folded, with an inert
 * band on the crease. With no hinge it shows [single], exactly as the screen looks flat, open or on
 * the cover screen.
 *
 * In the split, the top pane takes the status bar inset and the bottom pane takes the navigation
 * bar and keyboard insets, so the keyboard only ever pushes the bottom pane's content up.
 * [single] handles its own insets.
 *
 * With [fitBottomToKeyboard], while the keyboard is up the bottom pane takes its content's natural
 * height above the keyboard and the top pane shrinks to what is left. A tall keyboard would
 * otherwise squash the bottom pane's content to a sliver. The panes stay where they are in the
 * composition, so a focused text field keeps its focus and the keyboard stays up.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FoldSplit(
    hinge: Hinge?,
    single: @Composable () -> Unit,
    top: @Composable () -> Unit,
    bottom: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    fitBottomToKeyboard: Boolean = false,
) {
    val fitToKeyboard = fitBottomToKeyboard && WindowInsets.isImeVisible
    // Known only after the first layout. Zero is right for the first frame wherever the split fills
    // its window, as every screen's does: the app draws edge to edge and the hinge comes in window
    // coordinates, so the split does not jump once the real position arrives.
    var containerTopPx by remember { mutableIntStateOf(0) }
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .onGloballyPositioned { containerTopPx = it.positionInWindow().y.roundToInt() },
    ) {
        val density = LocalDensity.current
        val spacing = RafiqTheme.spacing
        val split =
            hinge?.let {
                with(density) {
                    splitAtHinge(
                        containerTopPx = containerTopPx,
                        containerHeightPx = constraints.maxHeight,
                        hinge = it,
                        minBandPx = spacing.hingeBand.roundToPx(),
                        minPanePx = MIN_PANE.roundToPx(),
                    )
                }
            }
        if (split == null) single() else SplitPanes(split, fitToKeyboard, top, bottom)
    }
}

/**
 * The two panes and the band. Sized by [split], or with [fitToKeyboard] the bottom pane wraps its
 * content above the keyboard and the top pane takes the rest.
 */
@Composable
private fun SplitPanes(
    split: PaneSplit,
    fitToKeyboard: Boolean,
    top: @Composable () -> Unit,
    bottom: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    Column(modifier = Modifier.fillMaxSize()) {
        val topSize = if (fitToKeyboard) Modifier.weight(1f) else Modifier.height(with(density) { split.topPx.toDp() })
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(topSize)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                    ),
        ) { top() }
        val bandHeight = if (fitToKeyboard) RafiqTheme.spacing.hairline else with(density) { split.bandPx.toDp() }
        HingeBand(Modifier.height(bandHeight))
        val bottomSize = if (fitToKeyboard) Modifier else Modifier.height(with(density) { split.bottomPx.toDp() })
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(bottomSize)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                    ),
        ) { bottom() }
    }
}

/** The dark band over the crease, with a hairline on the fold itself. Nothing in it is tappable. */
@Composable
private fun HingeBand(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().background(RafiqTheme.colors.hinge),
        contentAlignment = Alignment.Center,
    ) {
        HorizontalDivider(color = RafiqTheme.colors.border, thickness = RafiqTheme.spacing.hairline)
    }
}

// Below this a pane cannot hold even the composer, so the screen stays in one column.
private val MIN_PANE = 120.dp
