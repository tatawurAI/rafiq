package ai.tatawur.rafiq.ui.components

import ai.tatawur.rafiq.ui.theme.RafiqTheme
import ai.tatawur.rafiq.ui.theme.plexMono
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.rememberMarkdownState

/**
 * CommonMark text in the app's type and colours.
 *
 * Parsing runs off the main thread. While a streaming message grows, the previous rendering stays
 * on screen until the new one is ready, so the bubble never collapses to empty between deltas and
 * the list around it does not jump.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = RafiqTheme.colors.text,
) {
    val type = MaterialTheme.typography
    val colors = RafiqTheme.colors
    val body = type.bodyLarge.copy(color = color)
    val code = type.bodyMedium.copy(fontFamily = plexMono, color = colors.text)
    // Inline code has no fill. A span background covers the whole line box, so on wrapped text
    // each box ran into the line below. The mono face in the accent colour sets it apart instead,
    // and without an underline it does not read as a link.
    val inlineCode = type.bodyMedium.copy(fontFamily = plexMono, color = colors.accent, lineHeight = body.lineHeight)
    val state = rememberMarkdownState(content = markdown, retainState = true)
    Markdown(
        markdownState = state,
        modifier = modifier,
        colors =
            markdownColor(
                text = color,
                codeBackground = colors.ground,
                inlineCodeBackground = Color.Transparent,
                dividerColor = colors.border,
                tableBackground = colors.surface,
            ),
        typography =
            markdownTypography(
                // Chat headings stay close to body size: a reply is not a document.
                h1 = type.titleLarge.copy(color = color),
                h2 = type.titleMedium.copy(color = color),
                h3 = type.titleMedium.copy(color = color),
                h4 = type.titleSmall.copy(color = color),
                h5 = type.titleSmall.copy(color = color),
                h6 = type.titleSmall.copy(color = color),
                text = body,
                paragraph = body,
                ordered = body,
                bullet = body,
                list = body,
                code = code,
                inlineCode = inlineCode,
                quote = type.bodyMedium.copy(color = colors.textSecondary),
                table = body,
                textLink =
                    TextLinkStyles(
                        style = SpanStyle(color = colors.accent, textDecoration = TextDecoration.Underline),
                    ),
            ),
        dimens = markdownDimens(codeBackgroundCornerSize = RafiqTheme.spacing.small),
    )
}

@Preview(name = "Markdown", widthDp = 360)
@Composable
private fun MarkdownTextPreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            MarkdownText(
                markdown =
                    """
                    ### Findings
                    The forecast client reads the API key in **two places**. See `forecast/Client.kt`. The API returns
                    `temperature_unit`, `wind_speed_unit` and `precipitation_unit` on every hourly entry.

                    - `Client.kt` holds the canonical value
                    - `Cache.kt` duplicates it

                    ```kotlin
                    val forecast = client.fetch(city = "Lisbon")
                    ```
                    """.trimIndent(),
                modifier = Modifier.padding(RafiqTheme.spacing.medium),
            )
        }
    }
}
