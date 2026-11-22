package ai.tatawur.rafiq.ui.components

import ai.tatawur.rafiq.ui.theme.RafiqTheme
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Fades a sideways-scrolling row out at whichever edge has more content past it, so a cut-off chip
 * reads as "scroll for more" rather than as a clipped label. Apply it before `horizontalScroll`
 * with the same [state].
 */
@Composable
fun Modifier.horizontalFadingEdges(state: ScrollState): Modifier {
    val fade = RafiqTheme.spacing.fadingEdge
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fadePx = fade.toPx().coerceAtMost(size.width / 2)
            // The brush is an alpha mask over what was drawn, not a visible colour: DstIn keeps the
            // content where the mask is opaque and fades it where the mask is clear.
            if (state.canScrollForward) {
                drawRect(
                    brush =
                        Brush.horizontalGradient(
                            listOf(Color.Black, Color.Transparent),
                            size.width - fadePx,
                            size.width,
                        ),
                    topLeft = Offset(size.width - fadePx, 0f),
                    size = Size(fadePx, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (state.canScrollBackward) {
                drawRect(
                    brush = Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), 0f, fadePx),
                    size = Size(fadePx, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
}
