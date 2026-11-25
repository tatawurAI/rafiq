package ai.tatawur.rafiq.sessions

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.protocol.SessionMode
import ai.tatawur.rafiq.ui.theme.RafiqTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview

/**
 * The session's permission mode in the header, as a label you tap to switch. The menu lists the modes the
 * agent advertised, each with its own description, so nothing here knows any particular agent.
 */
@Composable
internal fun ModeSwitcher(
    currentModeId: String?,
    modes: List<SessionMode>,
    onSetMode: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val current = modes.firstOrNull { it.id == currentModeId }
    val label = current?.name ?: currentModeId ?: stringResource(R.string.mode_unknown)
    val colors = RafiqTheme.colors
    Box {
        Text(
            text = stringResource(R.string.mode_label, label),
            modifier =
                Modifier
                    .heightIn(min = RafiqTheme.spacing.minTouchTarget)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.mode_switch),
                        onClick = { open = true },
                    ).wrapContentHeight(),
            style = MaterialTheme.typography.labelSmall,
            color = colors.accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            modes.forEach { mode ->
                DropdownMenuItem(
                    text = { ModeText(mode, selected = mode.id == currentModeId) },
                    onClick = {
                        open = false
                        if (mode.id != currentModeId) onSetMode(mode.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun ModeText(
    mode: SessionMode,
    selected: Boolean,
) {
    Column {
        Text(
            text = if (selected) stringResource(R.string.mode_current, mode.name) else mode.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = RafiqTheme.colors.text,
        )
        mode.description?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = RafiqTheme.colors.textMuted)
        }
    }
}

/**
 * The mode a new session starts in. "Your server's default" leaves it to the bridge's setting for the agent.
 * The other choices are the modes the agent advertised for your last session with it, so before the first
 * session there is only the default, and you can switch from the session header once it runs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StartingModePicker(
    modes: List<SessionMode>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    val spacing = RafiqTheme.spacing
    val colors = RafiqTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        Text(
            stringResource(R.string.new_session_mode),
            style = MaterialTheme.typography.labelMedium,
            color = colors.textMuted,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
            verticalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            ModeChip(stringResource(R.string.new_session_mode_default), selected == null) { onSelect(null) }
            modes.forEach { mode -> ModeChip(mode.name, selected == mode.id) { onSelect(mode.id) } }
        }
        val help =
            when {
                selected != null -> modes.firstOrNull { it.id == selected }?.description
                modes.isEmpty() -> stringResource(R.string.new_session_mode_later)
                else -> stringResource(R.string.new_session_mode_default_help)
            }
        help?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textMuted) }
    }
}

@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = RafiqTheme.colors
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.heightIn(min = RafiqTheme.spacing.minTouchTarget),
        shape = RafiqTheme.shapes.pill,
        colors =
            FilterChipDefaults.filterChipColors(
                labelColor = colors.textSecondary,
                selectedContainerColor = colors.text,
                selectedLabelColor = colors.ground,
            ),
    )
}

private val PREVIEW_MODES =
    listOf(
        SessionMode("default", "Manual", "Always ask before making changes"),
        SessionMode("acceptEdits", "Accept edits", "Automatically accept all file edits"),
        SessionMode("plan", "Plan", "Create a plan before making changes"),
    )

@Preview(name = "Starting mode", widthDp = 399)
@Composable
private fun StartingModePickerPreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            StartingModePicker(PREVIEW_MODES, selected = "default", onSelect = {})
        }
    }
}

@Preview(name = "Starting mode, first session", widthDp = 399)
@Composable
private fun StartingModePickerFirstPreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            StartingModePicker(emptyList(), selected = null, onSelect = {})
        }
    }
}

@Preview(name = "Mode in the header", widthDp = 399)
@Composable
private fun ModeSwitcherPreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            ModeSwitcher("default", PREVIEW_MODES, onSetMode = {})
        }
    }
}
