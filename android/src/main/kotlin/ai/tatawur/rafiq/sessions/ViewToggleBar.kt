package ai.tatawur.rafiq.sessions

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.client.terminal.MirroredWindow
import ai.tatawur.rafiq.ui.theme.RafiqTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview

/** The two-segment Chat and Terminal switch in the session header. */
@Composable
internal fun ChatTerminalToggle(toggle: ViewToggle) {
    val spacing = RafiqTheme.spacing
    val colors = RafiqTheme.colors
    val description = stringResource(R.string.view_toggle)
    Row(
        modifier =
            Modifier
                .clip(RafiqTheme.shapes.pill)
                .border(spacing.hairline, colors.borderStrong, RafiqTheme.shapes.pill)
                .semantics { contentDescription = description }
                .selectableGroup(),
    ) {
        listOf(false to R.string.view_chat, true to R.string.view_terminal).forEach { (isTerminal, label) ->
            val selected = isTerminal == toggle.terminal
            Surface(
                color = if (selected) MaterialTheme.colorScheme.primary else colors.ground,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else colors.textSecondary,
                modifier =
                    Modifier
                        .heightIn(min = spacing.minTouchTarget)
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                        ) { if (!selected) toggle.onSelect(isTerminal) },
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .heightIn(
                                min = spacing.minTouchTarget,
                            ).padding(horizontal = spacing.smallMedium),
                ) {
                    Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Preview(name = "Chat and Terminal toggle")
@Composable
private fun ChatTerminalTogglePreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Row(modifier = Modifier.padding(RafiqTheme.spacing.medium)) {
                ChatTerminalToggle(ViewToggle(terminal = true) {})
            }
        }
    }
}

@Preview(name = "Terminal header, mirrored window", widthDp = 412)
@Composable
private fun TerminalHeaderPreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            SessionHeader(
                previewState(),
                SessionActions(viewToggle = ViewToggle(terminal = true) {}),
                windowLabel = windowLabel(MirroredWindow(1, "build")),
            )
        }
    }
}
