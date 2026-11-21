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
import ai.tatawur.rafiq.ui.components.horizontalFadingEdges
import ai.tatawur.rafiq.ui.fold.FoldSplit
import ai.tatawur.rafiq.ui.fold.Hinge
import ai.tatawur.rafiq.ui.fold.LocalHinge
import ai.tatawur.rafiq.ui.theme.RafiqTheme
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
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
 * The heading, banner, optional chips and rows.
 *
 * @param skipId a session shown elsewhere on screen, such as the half-folded bottom pane's card.
 */
internal fun LazyListScope.sessionListItems(
    state: SessionListUiState,
    actions: SessionListActions,
    withChips: Boolean,
    skipId: String?,
) {
    item(key = "heading") {
        Text(
            text = stringResource(R.string.sessions_title),
            style = MaterialTheme.typography.headlineMedium,
            color = RafiqTheme.colors.text,
        )
    }
    item(key = "connection") {
        ConnectionBanner(state.connection, Modifier.padding(vertical = RafiqTheme.spacing.xSmall))
    }
    if (withChips) item(key = "filters") { FilterChips(state, actions.onFilter) }
    when {
        !state.isLoaded -> {
            item(key = "loading") { ListNote(R.string.sessions_loading) }
        }

        state.items.isEmpty() && state.counts[SessionFilter.ALL] == 0 -> {
            item(key = "empty") { ListNote(R.string.sessions_empty) }
        }

        state.items.isEmpty() -> {
            item(key = "empty-filtered") { ListNote(R.string.sessions_empty_filtered) }
        }

        else -> {
            items(state.items.filter { it.session.id != skipId }, key = { it.session.id }) { item ->
                SessionRow(item, state.answering, actions)
            }
        }
    }
}

/** Below the hinge: the chips, the longest-waiting session and a full-width "New session". */
@Composable
internal fun ListBottomPane(
    state: SessionListUiState,
    actions: SessionListActions,
) {
    val spacing = RafiqTheme.spacing
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = spacing.medium, vertical = spacing.small),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        FilterChips(state, actions.onFilter)
        FeedbackBar(state.feedback, actions.onDismissFeedback)
        Box(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            state.oldestWaiting?.let { SessionRow(it, state.answering, actions, fullSizeActions = true) }
        }
        Button(
            onClick = actions.onNewSession,
            modifier = Modifier.fillMaxWidth().heightIn(min = spacing.largeTouchTarget),
            shape = MaterialTheme.shapes.small,
        ) {
            Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(spacing.icon))
            Spacer(Modifier.width(spacing.small))
            Text(stringResource(R.string.sessions_new))
        }
    }
}

/**
 * The feedback line and "New session", floating over the bottom of the list.
 *
 * @param expanded shows the label next to the icon. It folds to the icon alone on a short screen
 *   and once you scroll, so it covers less of the rows.
 */
@Composable
internal fun ListFooter(
    feedback: Feedback?,
    actions: SessionListActions,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val spacing = RafiqTheme.spacing
    val label = stringResource(R.string.sessions_new)
    Column(
        modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(spacing.medium),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        FeedbackBar(feedback, actions.onDismissFeedback)
        ExtendedFloatingActionButton(
            onClick = actions.onNewSession,
            expanded = expanded,
            containerColor = RafiqTheme.colors.accent,
            contentColor = RafiqTheme.colors.ground,
            icon = {
                Icon(
                    painterResource(R.drawable.ic_add),
                    contentDescription = if (expanded) null else label,
                    modifier = Modifier.size(spacing.icon),
                )
            },
            text = { Text(label) },
        )
    }
}

private data class FilterChipSpec(
    val filter: SessionFilter,
    @param:StringRes val label: Int,
)

private val filterChips =
    listOf(
        FilterChipSpec(SessionFilter.ALL, R.string.filter_all),
        FilterChipSpec(SessionFilter.NEEDS_YOU, R.string.filter_needs_you),
        FilterChipSpec(SessionFilter.WORKING, R.string.filter_working),
        FilterChipSpec(SessionFilter.DONE, R.string.filter_done),
    )

/**
 * The filter chips in one row that scrolls sideways and never wraps. Each chip keeps Material's
 * 48dp touch area around its smaller outline, which matters on the cover screen.
 */
@Composable
internal fun FilterChips(
    state: SessionListUiState,
    onFilter: (SessionFilter) -> Unit,
) {
    val colors = RafiqTheme.colors
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier.fillMaxWidth().horizontalFadingEdges(scroll).horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(RafiqTheme.spacing.small),
    ) {
        filterChips.forEach { (filter, label) ->
            val selected = state.filter == filter
            val tint = if (filter == SessionFilter.NEEDS_YOU) colors.needsYou else colors.textSecondary
            FilterChip(
                selected = selected,
                onClick = { onFilter(filter) },
                label = { Text(stringResource(label, state.counts[filter] ?: 0), maxLines = 1) },
                modifier = Modifier.minimumInteractiveComponentSize(),
                shape = RafiqTheme.shapes.pill,
                colors =
                    FilterChipDefaults.filterChipColors(
                        labelColor = tint,
                        selectedContainerColor = colors.text,
                        selectedLabelColor = colors.ground,
                    ),
                border =
                    FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selected,
                        borderColor = if (filter == SessionFilter.NEEDS_YOU) colors.needsYou else colors.borderStrong,
                        selectedBorderColor = colors.text,
                    ),
            )
        }
    }
}

@Composable
internal fun ListNote(
    @StringRes text: Int,
) {
    Text(
        text = stringResource(text),
        modifier = Modifier.padding(vertical = RafiqTheme.spacing.large),
        style = MaterialTheme.typography.bodyLarge,
        color = RafiqTheme.colors.textMuted,
    )
}
