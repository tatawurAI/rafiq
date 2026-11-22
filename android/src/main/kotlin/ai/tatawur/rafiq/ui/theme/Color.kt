package ai.tatawur.rafiq.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** The raw palette. Screens never read it directly. They use [RafiqColors] or the Material scheme. */
private object Palette {
    val ground = Color(0xFF15191B)
    val surface = Color(0xFF1E2426)
    val border = Color(0xFF2A3234)
    val borderStrong = Color(0xFF3F4A4C)
    val text = Color(0xFFF7F6F3)
    val textSecondary = Color(0xFFD8DCDA)
    val textMuted = Color(0xFFA8B0AD)
    val accent = Color(0xFF3FD9BC)
    val needsYou = Color(0xFFF2B84B)
    val deletion = Color(0xFFF2777A)
    val hinge = Color(0xFF0E1112)
}

/**
 * Colour roles that Material's [ColorScheme] has no slot for.
 *
 * @property needsYou marks anything waiting on you, such as an approval or a sign-in.
 * @property deletion marks removed lines in diffs and destructive actions.
 * @property hinge the band over the hinge when the phone is half folded, darker than the ground.
 */
@Immutable
data class RafiqColors(
    val ground: Color,
    val surface: Color,
    val border: Color,
    val borderStrong: Color,
    val text: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val needsYou: Color,
    val deletion: Color,
    val hinge: Color,
)

internal val darkRafiqColors =
    RafiqColors(
        ground = Palette.ground,
        surface = Palette.surface,
        border = Palette.border,
        borderStrong = Palette.borderStrong,
        text = Palette.text,
        textSecondary = Palette.textSecondary,
        textMuted = Palette.textMuted,
        accent = Palette.accent,
        needsYou = Palette.needsYou,
        deletion = Palette.deletion,
        hinge = Palette.hinge,
    )

// Every slot is mapped, so no Material default colour leaks into a component.
internal val darkMaterialColors: ColorScheme =
    darkColorScheme(
        primary = Palette.accent,
        onPrimary = Palette.ground,
        primaryContainer = Palette.borderStrong,
        onPrimaryContainer = Palette.text,
        secondary = Palette.accent,
        onSecondary = Palette.ground,
        secondaryContainer = Palette.border,
        onSecondaryContainer = Palette.text,
        tertiary = Palette.needsYou,
        onTertiary = Palette.ground,
        tertiaryContainer = Palette.border,
        onTertiaryContainer = Palette.needsYou,
        background = Palette.ground,
        onBackground = Palette.text,
        surface = Palette.surface,
        onSurface = Palette.text,
        surfaceVariant = Palette.border,
        onSurfaceVariant = Palette.textMuted,
        surfaceTint = Palette.accent,
        inverseSurface = Palette.text,
        inverseOnSurface = Palette.ground,
        inversePrimary = Palette.ground,
        error = Palette.deletion,
        onError = Palette.ground,
        errorContainer = Palette.border,
        onErrorContainer = Palette.deletion,
        outline = Palette.borderStrong,
        outlineVariant = Palette.border,
        scrim = Palette.ground,
        surfaceBright = Palette.border,
        surfaceDim = Palette.ground,
        surfaceContainerLowest = Palette.ground,
        surfaceContainerLow = Palette.ground,
        surfaceContainer = Palette.surface,
        surfaceContainerHigh = Palette.surface,
        surfaceContainerHighest = Palette.border,
    )
