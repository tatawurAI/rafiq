package ai.tatawur.rafiq.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing and size tokens. Screens take sizes from here, never from literals.
 *
 * @property minTouchTarget the smallest tappable size the app allows.
 * @property composerHeight the text field and round buttons of the composer.
 * @property statusDot the coloured dot in status badges and thread tabs.
 * @property icon icons inside buttons, cards and chips.
 * @property hairline borders on cards, chips and dividers.
 * @property bubbleMaxWidthFraction how much of the width a chat bubble may take.
 * @property compactHeight below this height, for example on a cover screen (about 353dp tall on a
 *   Z Flip) or a tall screen with the keyboard up, a screen tightens its padding and collapses the
 *   "New session" button to an icon so the main content keeps its room.
 * @property largeTouchTarget full-size action buttons: the half-folded bottom pane's approval
 *   buttons and the compact pinned approval's height.
 * @property hingeBand the inert band drawn over the hinge when the phone is half folded.
 * @property tabIndicator the underline under the selected thread tab.
 * @property fadingEdge how far a sideways-scrolling row fades out at an edge with more content.
 */
@Immutable
data class RafiqSpacing(
    val xSmall: Dp = 4.dp,
    val small: Dp = 8.dp,
    val smallMedium: Dp = 12.dp,
    val medium: Dp = 16.dp,
    val large: Dp = 24.dp,
    val minTouchTarget: Dp = 44.dp,
    val composerHeight: Dp = 48.dp,
    val statusDot: Dp = 8.dp,
    val icon: Dp = 18.dp,
    val hairline: Dp = 1.dp,
    val bubbleMaxWidthFraction: Float = 0.85f,
    val compactHeight: Dp = 560.dp,
    val largeTouchTarget: Dp = 56.dp,
    val hingeBand: Dp = 16.dp,
    val tabIndicator: Dp = 2.dp,
    val fadingEdge: Dp = 32.dp,
)

/**
 * Corner shapes that Material's [Shapes] has no slot for.
 *
 * @property userBubble your own messages: rounded, with a tighter corner at the bottom right.
 * @property pill chips and round buttons.
 * @property composerField the composer's text field. A fixed radius, not a pill: a pill turns into
 *   an oval as the draft grows to several lines and clips the text at its corners.
 */
@Immutable
data class RafiqShapes(
    val userBubble: RoundedCornerShape =
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 4.dp, bottomStart = 18.dp),
    val pill: RoundedCornerShape = RoundedCornerShape(percent = 50),
    val composerField: RoundedCornerShape = RoundedCornerShape(24.dp),
)

// Small for buttons, medium for tool cards, large for session cards.
private val rafiqMaterialShapes =
    Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(14.dp),
        large = RoundedCornerShape(18.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )

private val LocalRafiqColors = staticCompositionLocalOf { darkRafiqColors }
private val LocalRafiqSpacing = staticCompositionLocalOf { RafiqSpacing() }
private val LocalRafiqShapes = staticCompositionLocalOf { RafiqShapes() }

/**
 * Rafiq's theme. The app is dark first and ships only the dark palette, so this ignores the
 * system light or dark setting.
 */
@Composable
fun RafiqTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalRafiqColors provides darkRafiqColors,
        LocalRafiqSpacing provides RafiqSpacing(),
        LocalRafiqShapes provides RafiqShapes(),
    ) {
        MaterialTheme(
            colorScheme = darkMaterialColors,
            typography = rafiqTypography,
            shapes = rafiqMaterialShapes,
            content = content,
        )
    }
}

/** Reads Rafiq's own tokens, next to [MaterialTheme] for the Material ones. */
object RafiqTheme {
    val colors: RafiqColors
        @Composable
        @ReadOnlyComposable
        get() = LocalRafiqColors.current

    val spacing: RafiqSpacing
        @Composable
        @ReadOnlyComposable
        get() = LocalRafiqSpacing.current

    val shapes: RafiqShapes
        @Composable
        @ReadOnlyComposable
        get() = LocalRafiqShapes.current
}
