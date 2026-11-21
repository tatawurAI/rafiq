package ai.tatawur.rafiq.sessions

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.client.NoticeKind
import ai.tatawur.rafiq.client.TimelineItem
import ai.tatawur.rafiq.protocol.ContentBlock
import ai.tatawur.rafiq.protocol.MessageRole
import ai.tatawur.rafiq.protocol.ToolCallKind
import ai.tatawur.rafiq.protocol.ToolCallStatus
import ai.tatawur.rafiq.ui.components.MarkdownText
import ai.tatawur.rafiq.ui.theme.RafiqTheme
import ai.tatawur.rafiq.ui.theme.plexMono
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview

@DrawableRes
private fun toolIcon(kind: ToolCallKind): Int =
    when (kind) {
        ToolCallKind.EDIT -> R.drawable.ic_tool_edit
        ToolCallKind.COMMAND -> R.drawable.ic_tool_command
        ToolCallKind.SEARCH -> R.drawable.ic_tool_search
        ToolCallKind.READ -> R.drawable.ic_tool_read
        ToolCallKind.FETCH -> R.drawable.ic_tool_fetch
        ToolCallKind.OTHER -> R.drawable.ic_tool_other
    }

@StringRes
private fun toolLabel(kind: ToolCallKind): Int =
    when (kind) {
        ToolCallKind.EDIT -> R.string.tool_edit
        ToolCallKind.COMMAND -> R.string.tool_command
        ToolCallKind.SEARCH -> R.string.tool_search
        ToolCallKind.READ -> R.string.tool_read
        ToolCallKind.FETCH -> R.string.tool_fetch
        ToolCallKind.OTHER -> R.string.tool_other
    }

@StringRes
private fun toolStatusLabel(status: ToolCallStatus): Int =
    when (status) {
        ToolCallStatus.PENDING -> R.string.tool_pending
        ToolCallStatus.IN_PROGRESS -> R.string.tool_in_progress
        ToolCallStatus.COMPLETED -> R.string.tool_completed
        ToolCallStatus.FAILED -> R.string.tool_failed
        ToolCallStatus.UNKNOWN -> R.string.tool_unknown
    }

/**
 * A tool call as a card: an icon for its kind, what it acts on, and its status. Tapping the header
 * shows the output. While an approval for the call is open, the card carries it inline.
 *
 * @param expanded whether the output shows. The screen keeps it, so it survives recomposition.
 */
@Composable
fun ToolCard(
    tool: TimelineItem.Tool,
    expanded: Boolean,
    onToggle: () -> Unit,
    answering: Boolean,
    onAnswer: (optionId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = RafiqTheme.spacing
    val colors = RafiqTheme.colors
    val approval = tool.approval
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colors.surface,
        border = BorderStroke(spacing.hairline, if (approval != null) colors.needsYou else colors.border),
    ) {
        Column(modifier = Modifier.animateContentSize()) {
            ToolCardHeader(tool, expanded, onToggle)
            if (expanded) {
                HorizontalDivider(color = colors.border, thickness = spacing.hairline)
                Text(
                    text = tool.output ?: stringResource(R.string.tool_no_output),
                    modifier = Modifier.padding(horizontal = spacing.smallMedium, vertical = spacing.small),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = plexMono),
                    color = colors.textSecondary,
                )
            }
            if (approval != null) {
                HorizontalDivider(color = colors.border, thickness = spacing.hairline)
                Column(
                    modifier = Modifier.padding(spacing.smallMedium),
                    verticalArrangement = Arrangement.spacedBy(spacing.small),
                ) {
                    Text(
                        text = stringResource(R.string.approval_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.needsYou,
                    )
                    Text(
                        text = approval.description,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = colors.text,
                    )
                    ApprovalOptions(approval.options, answering, onAnswer)
                }
            }
        }
    }
}

/** The tappable first line of a tool card: kind icon, summary, status and the expand chevron. */
@Composable
private fun ToolCardHeader(
    tool: TimelineItem.Tool,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val spacing = RafiqTheme.spacing
    val colors = RafiqTheme.colors
    val statusColor =
        when {
            tool.approval != null -> colors.needsYou
            tool.status == ToolCallStatus.FAILED -> colors.deletion
            tool.status == ToolCallStatus.COMPLETED || tool.status == ToolCallStatus.IN_PROGRESS -> colors.accent
            else -> colors.textMuted
        }
    val stateLabel = stringResource(if (expanded) R.string.tool_collapse else R.string.tool_expand)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = spacing.minTouchTarget)
                .clickable(role = Role.Button, onClickLabel = stateLabel, onClick = onToggle)
                .padding(horizontal = spacing.smallMedium, vertical = spacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        Icon(
            painter = painterResource(toolIcon(tool.kind)),
            contentDescription = stringResource(toolLabel(tool.kind)),
            modifier = Modifier.size(spacing.icon),
            tint = if (tool.kind == ToolCallKind.EDIT) colors.accent else colors.textMuted,
        )
        Text(
            text = tool.inputSummary,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = plexMono),
            color = colors.textSecondary,
            maxLines = if (expanded) Int.MAX_VALUE else 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(toolStatusLabel(tool.status)),
            style = MaterialTheme.typography.bodySmall,
            color = statusColor,
        )
        Icon(
            painter = painterResource(if (expanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down),
            contentDescription = null,
            modifier = Modifier.size(spacing.icon),
            tint = colors.textMuted,
        )
    }
}
