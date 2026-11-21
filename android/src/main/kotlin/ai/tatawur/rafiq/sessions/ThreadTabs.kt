package ai.tatawur.rafiq.sessions

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.client.ThreadTab
import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.ThreadKind
import ai.tatawur.rafiq.ui.components.StatusDot
import ai.tatawur.rafiq.ui.theme.RafiqTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp

/**
 * The thread strip: the main thread first, then helpers in the order they appeared. Each tab has a
 * status dot, and a count badge while approvals on that thread wait on you. Long helper titles are
 * cut to [MAX_TAB_TITLE] characters so one helper cannot crowd out the rest. It scrolls sideways
 * when the threads do not fit.
 */
@Composable
fun ThreadTabs(
    threads: List<ThreadTab>,
    selectedId: String?,
    onSelect: (threadId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedIndex = threads.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    val colors = RafiqTheme.colors
    PrimaryScrollableTabRow(
        selectedTabIndex = selectedIndex,
        modifier = modifier,
        containerColor = colors.ground,
        contentColor = colors.text,
        edgePadding = RafiqTheme.spacing.small,
        indicator = {
            TabRowDefaults.PrimaryIndicator(
                modifier = Modifier.tabIndicatorOffset(selectedIndex, matchContentSize = true),
                width = Dp.Unspecified,
                height = RafiqTheme.spacing.tabIndicator,
                color = colors.accent,
            )
        },
        divider = {},
    ) {
        threads.forEach { thread ->
            val selected = thread.id == selectedId
            val waiting =
                if (thread.openApprovals > 0) {
                    pluralStringResource(R.plurals.thread_needs_you, thread.openApprovals, thread.openApprovals)
                } else {
                    null
                }
            Tab(
                selected = selected,
                onClick = { onSelect(thread.id) },
                modifier =
                    Modifier
                        .heightIn(min = RafiqTheme.spacing.minTouchTarget)
                        .widthIn(min = RafiqTheme.spacing.minTouchTarget)
                        .semantics { if (waiting != null) contentDescription = "${thread.title}, $waiting" },
                selectedContentColor = colors.text,
                unselectedContentColor = colors.textMuted,
            ) {
                ThreadTabLabel(thread, selected)
            }
        }
    }
}

/** A tab's status dot, title and waiting count. */
@Composable
private fun ThreadTabLabel(
    thread: ThreadTab,
    selected: Boolean,
) {
    val colors = RafiqTheme.colors
    Row(
        modifier = Modifier.padding(horizontal = RafiqTheme.spacing.smallMedium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RafiqTheme.spacing.small),
    ) {
        if (thread.kind != ThreadKind.MAIN) StatusDot(thread.status, describe = true)
        Text(
            text = tabTitle(thread.title),
            style =
                MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (thread.openApprovals > 0) {
            Badge(containerColor = colors.needsYou, contentColor = colors.ground) {
                Text(thread.openApprovals.toString())
            }
        }
    }
}

/** [title] cut to [MAX_TAB_TITLE] characters, ending in an ellipsis when it was longer. */
internal fun tabTitle(title: String): String =
    if (title.length <= MAX_TAB_TITLE) title else title.take(MAX_TAB_TITLE - 1).trimEnd() + "\u2026"

private const val MAX_TAB_TITLE = 18

@Preview(name = "Thread tabs", widthDp = 360)
@Composable
private fun ThreadTabsPreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            ThreadTabs(
                threads =
                    listOf(
                        ThreadTab("m", null, ThreadKind.MAIN, "Main", AgentState.WAITING_ON_YOU, 1),
                        ThreadTab("r", "m", ThreadKind.SUBAGENT, "Forecast API research", AgentState.WORKING, 0),
                        ThreadTab("t", "m", ThreadKind.SUBAGENT, "Test writer", AgentState.DONE, 0),
                    ),
                selectedId = "m",
                onSelect = {},
            )
        }
    }
}
