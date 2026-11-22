package ai.tatawur.rafiq.sessions

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.client.ApprovalCard
import ai.tatawur.rafiq.client.ConnectionState
import ai.tatawur.rafiq.client.SessionListItem
import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.Session
import ai.tatawur.rafiq.ui.components.AgentTag
import ai.tatawur.rafiq.ui.components.ConnectionBanner
import ai.tatawur.rafiq.ui.components.FeedbackBar
import ai.tatawur.rafiq.ui.components.StatusBadge
import ai.tatawur.rafiq.ui.components.StatusDot
import ai.tatawur.rafiq.ui.components.sessionStatusLabel
import ai.tatawur.rafiq.ui.theme.RafiqTheme
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * One session as a card. A row waiting on you gets the needs-you border, the approval's
 * description, a status line, and Deny and Approve for its oldest approval on their own line.
 *
 * @param fullSizeActions makes Deny and Approve share the row's width at full height, for the
 *   half-folded bottom pane.
 */
@Composable
fun SessionRow(
    item: SessionListItem,
    answering: Set<String>,
    actions: SessionListActions,
    modifier: Modifier = Modifier,
    fullSizeActions: Boolean = false,
) {
    val spacing = RafiqTheme.spacing
    val colors = RafiqTheme.colors
    val session = item.session
    val finished = session.status == AgentState.DONE && !item.needsYou
    Surface(
        onClick = { actions.onOpen(session.id) },
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (finished) colors.ground else colors.surface,
        border = BorderStroke(spacing.hairline, if (item.needsYou) colors.needsYou else colors.border),
    ) {
        Column(modifier = Modifier.padding(spacing.medium), verticalArrangement = Arrangement.spacedBy(spacing.small)) {
            RowHeading(item, finished)
            val approval = item.openApprovals.firstOrNull()
            if (approval != null) {
                RowApproval(
                    approval = approval,
                    othersWaiting = item.openApprovals.size - 1,
                    answering = approval.id in answering,
                    sessionId = session.id,
                    actions = actions,
                    fullSize = fullSizeActions,
                )
            } else {
                item.lastLine?.let(::previewLine)?.takeIf { it.isNotEmpty() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (finished) colors.textMuted else colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // A summary has a last line once the session has a message.
                StatusBadge(session.status, overrideLabel = sessionStatusLabel(session.status, item.lastLine != null))
            }
        }
    }
}

/** The title line, with status dot and agent tag, and the project path under it. */
@Composable
private fun RowHeading(
    item: SessionListItem,
    finished: Boolean,
) {
    val colors = RafiqTheme.colors
    val session = item.session
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RafiqTheme.spacing.small),
    ) {
        if (!item.needsYou) StatusDot(session.status)
        Text(
            text = session.title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            color = if (finished) colors.textSecondary else colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        AgentTag(session.agent)
    }
    Text(
        text = session.projectPath,
        style = MaterialTheme.typography.labelSmall,
        color = colors.textMuted,
        maxLines = 1,
        overflow = TextOverflow.StartEllipsis,
    )
}

@Composable
private fun RowApproval(
    approval: ApprovalCard,
    othersWaiting: Int,
    answering: Boolean,
    sessionId: String,
    actions: SessionListActions,
    fullSize: Boolean,
) {
    val spacing = RafiqTheme.spacing
    val choices = InlineChoices.of(approval)
    Text(
        text = approval.description,
        style = MaterialTheme.typography.bodyMedium,
        color = RafiqTheme.colors.text,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
    // The status gets its own line so "Waiting on you" is never squeezed by the buttons.
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
        StatusBadge(AgentState.WAITING_ON_YOU)
        if (othersWaiting > 0) {
            Text(
                text = stringResource(R.string.row_more_approvals, othersWaiting),
                style = MaterialTheme.typography.bodySmall,
                color = RafiqTheme.colors.textMuted,
                maxLines = 1,
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.small, Alignment.End),
    ) {
        val buttonModifier =
            if (fullSize) {
                Modifier.weight(1f).heightIn(min = spacing.largeTouchTarget)
            } else {
                Modifier.heightIn(min = spacing.minTouchTarget)
            }
        if (!choices.canAnswerInline) {
            TextButton(onClick = { actions.onOpen(sessionId) }, modifier = buttonModifier) {
                Text(stringResource(R.string.row_review))
            }
        }
        choices.deny?.let { deny ->
            OutlinedButton(
                onClick = { actions.onApprove(approval.id, deny.id) },
                enabled = !answering,
                modifier = buttonModifier,
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(spacing.hairline, RafiqTheme.colors.borderStrong),
            ) { Text(stringResource(R.string.row_deny), color = RafiqTheme.colors.text) }
        }
        choices.approve?.let { approve ->
            Button(
                onClick = { actions.onApprove(approval.id, approve.id) },
                enabled = !answering,
                modifier = buttonModifier,
                shape = MaterialTheme.shapes.small,
            ) { Text(stringResource(R.string.row_approve)) }
        }
    }
}
