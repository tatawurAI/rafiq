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

/** The session list, wired to its [SessionListViewModel]. */
@Composable
fun SessionListRoute(
    viewModel: SessionListViewModel,
    onOpen: (sessionId: String) -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions =
        remember(viewModel, onOpen, onNewSession) {
            SessionListActions(
                onOpen = onOpen,
                onNewSession = onNewSession,
                onFilter = viewModel::selectFilter,
                onApprove = viewModel::approve,
                onDismissFeedback = viewModel::dismissFeedback,
            )
        }
    SessionListScreen(state, actions, modifier)
}

/**
 * Every session on your server, newest update first, with filter chips and "New session". Rows
 * waiting on you answer their oldest approval in place. With no connection it shows the cached list
 * under a banner. The heading and chips scroll with the rows so a short cover screen keeps its
 * space for sessions.
 */
@Composable
fun SessionListScreen(
    state: SessionListUiState,
    actions: SessionListActions,
    modifier: Modifier = Modifier,
) {
    val spacing = RafiqTheme.spacing
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val direction = LocalLayoutDirection.current
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    start = insets.calculateStartPadding(direction) + spacing.medium,
                    end = insets.calculateEndPadding(direction) + spacing.medium,
                    top = insets.calculateTopPadding() + spacing.medium,
                    // Room for the floating button, so the last row can scroll clear of it.
                    bottom = insets.calculateBottomPadding() + spacing.composerHeight + spacing.large * 2,
                ),
            verticalArrangement = Arrangement.spacedBy(spacing.smallMedium),
        ) {
            item(key = "heading") {
                Text(
                    text = stringResource(R.string.sessions_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = RafiqTheme.colors.text,
                )
            }
            item(key = "connection") { ConnectionBanner(state.connection, Modifier.padding(vertical = spacing.xSmall)) }
            item(key = "filters") { FilterChips(state, actions.onFilter) }
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
                    items(state.items, key = { it.session.id }) { item ->
                        SessionRow(item, state.answering, actions)
                    }
                }
            }
        }
        ListFooter(state.feedback, actions, Modifier.align(Alignment.BottomEnd))
    }
}

/** The feedback line and "New session", floating over the bottom of the list. */
@Composable
private fun ListFooter(
    feedback: Feedback?,
    actions: SessionListActions,
    modifier: Modifier = Modifier,
) {
    val spacing = RafiqTheme.spacing
    Column(
        modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(spacing.medium),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        FeedbackBar(feedback, actions.onDismissFeedback)
        ExtendedFloatingActionButton(
            onClick = actions.onNewSession,
            containerColor = RafiqTheme.colors.accent,
            contentColor = RafiqTheme.colors.ground,
            icon = {
                Icon(
                    painterResource(R.drawable.ic_add),
                    contentDescription = null,
                    modifier = Modifier.size(spacing.icon),
                )
            },
            text = { Text(stringResource(R.string.sessions_new)) },
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterChips(
    state: SessionListUiState,
    onFilter: (SessionFilter) -> Unit,
) {
    val colors = RafiqTheme.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(RafiqTheme.spacing.small)) {
        filterChips.forEach { (filter, label) ->
            val selected = state.filter == filter
            val tint = if (filter == SessionFilter.NEEDS_YOU) colors.needsYou else colors.textSecondary
            FilterChip(
                selected = selected,
                onClick = { onFilter(filter) },
                label = { Text(stringResource(label, state.counts[filter] ?: 0)) },
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
private fun ListNote(
    @StringRes text: Int,
) {
    Text(
        text = stringResource(text),
        modifier = Modifier.padding(vertical = RafiqTheme.spacing.large),
        style = MaterialTheme.typography.bodyLarge,
        color = RafiqTheme.colors.textMuted,
    )
}

private fun previewItem(
    id: String,
    title: String,
    agent: String,
    status: AgentState,
    lastLine: String?,
    approvals: List<ApprovalCard> = emptyList(),
) = SessionListItem(
    Session(id, agent, "/srv/$title", title, status, createdAtMs = 0, updatedAtMs = 0),
    lastLine,
    approvals,
)

private val previewItems =
    listOf(
        previewItem(
            "a",
            "api-server",
            "claude-code",
            AgentState.WAITING_ON_YOU,
            null,
            listOf(previewApproval),
        ),
        previewItem(
            "w",
            "weather-app",
            "claude-code",
            AgentState.WORKING,
            "Moving the forecast parsing into its own module. Two helpers are mapping the unit fields.",
        ),
        previewItem("d", "docs-site", "codex", AgentState.DONE, "Published the getting-started page."),
    )

private fun previewListState(connection: ConnectionState = ConnectionState.Live) =
    SessionListUiState(
        isLoaded = true,
        items = previewItems,
        counts = previewItems.countsByFilter(),
        connection = connection,
    )

@Preview(name = "Sessions, tall", widthDp = 412, heightDp = 920)
@Composable
private fun SessionListTallPreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            SessionListScreen(previewListState(), SessionListActions())
        }
    }
}

@Preview(name = "Sessions, cover screen", widthDp = 360, heightDp = 400)
@Composable
private fun SessionListCoverPreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            SessionListScreen(previewListState(ConnectionState.Offline(retryAtMs = null)), SessionListActions())
        }
    }
}

@Preview(name = "Sessions, empty", widthDp = 360, heightDp = 400)
@Composable
private fun SessionListEmptyPreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            SessionListScreen(
                SessionListUiState(isLoaded = true, counts = mapOf(SessionFilter.ALL to 0)),
                SessionListActions(),
            )
        }
    }
}
