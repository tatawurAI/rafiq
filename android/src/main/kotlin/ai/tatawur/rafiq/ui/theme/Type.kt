package ai.tatawur.rafiq.ui.theme

import ai.tatawur.rafiq.R
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

// Only the weights the app uses are bundled. A missing weight would be synthesised,
// which looks wrong, so add the file before using a new weight.
private val archivo =
    FontFamily(
        Font(R.font.archivo_semibold, FontWeight.SemiBold),
        Font(R.font.archivo_bold, FontWeight.Bold),
    )

private val plexSans =
    FontFamily(
        Font(R.font.plex_sans_regular, FontWeight.Normal),
        Font(R.font.plex_sans_medium, FontWeight.Medium),
        Font(R.font.plex_sans_semibold, FontWeight.SemiBold),
    )

/** IBM Plex Mono, for labels and code. Public so code views can use it outside the Material roles. */
val plexMono =
    FontFamily(
        Font(R.font.plex_mono_regular, FontWeight.Normal),
        Font(R.font.plex_mono_medium, FontWeight.Medium),
    )

/**
 * Material's type scale with Rafiq's families: Archivo for display, headline and title roles,
 * IBM Plex Sans for body text, IBM Plex Mono for labels. Sizes and line heights stay Material's.
 */
internal val rafiqTypography: Typography =
    Typography().run {
        Typography(
            displayLarge = displayLarge.copy(fontFamily = archivo, fontWeight = FontWeight.Bold),
            displayMedium = displayMedium.copy(fontFamily = archivo, fontWeight = FontWeight.Bold),
            displaySmall = displaySmall.copy(fontFamily = archivo, fontWeight = FontWeight.Bold),
            headlineLarge = headlineLarge.copy(fontFamily = archivo, fontWeight = FontWeight.Bold),
            headlineMedium = headlineMedium.copy(fontFamily = archivo, fontWeight = FontWeight.Bold),
            headlineSmall = headlineSmall.copy(fontFamily = archivo, fontWeight = FontWeight.SemiBold),
            titleLarge = titleLarge.copy(fontFamily = archivo, fontWeight = FontWeight.SemiBold),
            titleMedium = titleMedium.copy(fontFamily = archivo, fontWeight = FontWeight.SemiBold),
            titleSmall = titleSmall.copy(fontFamily = archivo, fontWeight = FontWeight.SemiBold),
            bodyLarge = bodyLarge.copy(fontFamily = plexSans, fontWeight = FontWeight.Normal),
            bodyMedium = bodyMedium.copy(fontFamily = plexSans, fontWeight = FontWeight.Normal),
            bodySmall = bodySmall.copy(fontFamily = plexSans, fontWeight = FontWeight.Normal),
            labelLarge = labelLarge.copy(fontFamily = plexMono, fontWeight = FontWeight.Medium),
            labelMedium = labelMedium.copy(fontFamily = plexMono, fontWeight = FontWeight.Medium),
            labelSmall = labelSmall.copy(fontFamily = plexMono, fontWeight = FontWeight.Normal),
        )
    }
