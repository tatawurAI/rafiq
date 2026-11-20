package ai.tatawur.rafiq.ui.theme

import androidx.compose.material3.MaterialTheme
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
 */
@Immutable
data class RafiqSpacing(
    val small: Dp = 8.dp,
    val medium: Dp = 16.dp,
    val large: Dp = 24.dp,
    val minTouchTarget: Dp = 44.dp,
)

private val LocalRafiqColors = staticCompositionLocalOf { darkRafiqColors }
private val LocalRafiqSpacing = staticCompositionLocalOf { RafiqSpacing() }

/**
 * Rafiq's theme. The app is dark first and ships only the dark palette, so this ignores the
 * system light or dark setting.
 */
@Composable
fun RafiqTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalRafiqColors provides darkRafiqColors,
        LocalRafiqSpacing provides RafiqSpacing(),
    ) {
        MaterialTheme(
            colorScheme = darkMaterialColors,
            typography = rafiqTypography,
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
}
