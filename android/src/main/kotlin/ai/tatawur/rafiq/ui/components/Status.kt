package ai.tatawur.rafiq.ui.components

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.ui.theme.RafiqTheme
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview

/** The colour that stands for [state] in dots, badges and labels. */
@Composable
@ReadOnlyComposable
fun statusColor(state: AgentState): Color {
    val colors = RafiqTheme.colors
    return when (state) {
        AgentState.WORKING -> colors.accent
        AgentState.WAITING_ON_YOU -> colors.needsYou
        AgentState.ERROR -> colors.deletion
        AgentState.DONE, AgentState.UNKNOWN -> colors.textMuted
    }
}

/** The word for [state] in badges and subtitles. */
@StringRes
fun statusLabel(state: AgentState): Int =
    when (state) {
        AgentState.WORKING -> R.string.status_working
        AgentState.WAITING_ON_YOU -> R.string.status_waiting_on_you
        AgentState.DONE -> R.string.status_done
        AgentState.ERROR -> R.string.status_error
        AgentState.UNKNOWN -> R.string.status_unknown
    }

/**
 * A small dot for [state]. Finished states draw a hollow ring, so a done session reads as quiet
 * next to a running one even without colour.
 *
 * @param describe reads the status word to screen readers, for a dot with no label beside it.
 */
@Composable
fun StatusDot(
    state: AgentState,
    modifier: Modifier = Modifier,
    describe: Boolean = false,
) {
    val color = statusColor(state)
    val size = RafiqTheme.spacing.statusDot
    val label = stringResource(statusLabel(state))
    val dot = modifier.size(size).then(if (describe) Modifier.semantics { contentDescription = label } else Modifier)
    val hollow = state == AgentState.DONE || state == AgentState.UNKNOWN
    Box(
        modifier =
            if (hollow) {
                dot.border(RafiqTheme.spacing.hairline, color, CircleShape)
            } else {
                dot.background(color, CircleShape)
            },
    )
}

/** A dot and the status word, for row footers and the session header. */
@Composable
fun StatusBadge(
    state: AgentState,
    modifier: Modifier = Modifier,
    @StringRes overrideLabel: Int? = null,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RafiqTheme.spacing.small),
    ) {
        StatusDot(state)
        Text(
            text = stringResource(overrideLabel ?: statusLabel(state)),
            style = MaterialTheme.typography.bodySmall,
            color = statusColor(state),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The agent's id in a small outlined tag, as the bridge names it. */
@Composable
fun AgentTag(
    agent: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = agent,
        modifier =
            modifier
                .border(RafiqTheme.spacing.hairline, RafiqTheme.colors.borderStrong, MaterialTheme.shapes.extraSmall)
                .padding(horizontal = RafiqTheme.spacing.small, vertical = RafiqTheme.spacing.xSmall / 2),
        style = MaterialTheme.typography.labelSmall,
        color = RafiqTheme.colors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Preview(name = "Status badges")
@Composable
private fun StatusBadgesPreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.padding(RafiqTheme.spacing.medium),
                verticalArrangement = Arrangement.spacedBy(RafiqTheme.spacing.small),
            ) {
                AgentState.entries.forEach { StatusBadge(it) }
                AgentTag("claude-code")
            }
        }
    }
}
