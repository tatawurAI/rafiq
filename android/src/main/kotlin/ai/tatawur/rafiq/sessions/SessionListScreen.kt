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
import ai.tatawur.rafiq.ui.fold.previewHalfFoldHinge
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
import androidx.compose.foundation.lazy.LazyListState
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

/** The session list, wired to its [SessionListViewModel]. */
@Composable
fun SessionListRoute(
    viewModel: SessionListViewModel,
    onOpen: (sessionId: String) -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
    onPairAgain: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    approvals: ApprovalGate? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val approve = rememberGatedApprove(approvals, viewModel::approve)
    val actions =
        remember(viewModel, onOpen, onNewSession, onPairAgain, onOpenSettings, approve) {
            SessionListActions(
                onOpen = onOpen,
                onOpenSettings = onOpenSettings,
                onNewSession = onNewSession,
                onFilter = viewModel::selectFilter,
                onApprove = approve,
                onDismissFeedback = viewModel::dismissFeedback,
                onPairAgain = onPairAgain,
            )
        }
    SessionListScreen(state, actions, modifier, hinge = LocalHinge.current)
}

/**
 * Every session on your server, newest update first, with filter chips and "New session". Rows
 * waiting on you answer their oldest approval in place. With no connection it shows the cached list
 * under a banner.
 *
 * Flat, open or on the cover screen it is one list. The heading and chips scroll with the rows so a
 * short cover screen keeps its space for sessions, and "New session" floats over the bottom,
 * shrinking to an icon on a short screen or once you scroll. Half folded, the list sits above the
 * hinge and the controls below it: the chips, the session that has waited on you longest with
 * full-size Deny and Approve, and a full-width "New session".
 *
 * @param hinge where the half-folded hinge crosses the window, or null to keep one column.
 */
@Composable
fun SessionListScreen(
    state: SessionListUiState,
    actions: SessionListActions,
    modifier: Modifier = Modifier,
    hinge: Hinge? = null,
) {
    // One list state for both layouts. Rows are keyed, so the first row you can see stays first
    // when folding adds or drops the chips row and the bottom pane's card.
    val listState = rememberLazyListState()
    FoldSplit(
        hinge = hinge,
        modifier = modifier,
        single = { SingleColumnList(state, actions, listState) },
        top = {
            val spacing = RafiqTheme.spacing
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(spacing.medium),
                verticalArrangement = Arrangement.spacedBy(spacing.smallMedium),
            ) {
                sessionListItems(state, actions, withChips = false, skipId = state.oldestWaiting?.session?.id)
            }
        },
        bottom = { ListBottomPane(state, actions) },
    )
}

@Composable
private fun SingleColumnList(
    state: SessionListUiState,
    actions: SessionListActions,
    listState: LazyListState,
) {
    val spacing = RafiqTheme.spacing
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val direction = LocalLayoutDirection.current
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val compact = maxHeight < spacing.compactHeight
        val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    start = insets.calculateStartPadding(direction) + spacing.medium,
                    end = insets.calculateEndPadding(direction) + spacing.medium,
                    top = insets.calculateTopPadding() + spacing.medium,
                    // The floating button's height, its margin and a gap, so the last row scrolls
                    // fully clear of it.
                    bottom = insets.calculateBottomPadding() + spacing.largeTouchTarget + spacing.medium * 2,
                ),
            verticalArrangement = Arrangement.spacedBy(spacing.smallMedium),
        ) {
            sessionListItems(state, actions, withChips = true, skipId = null)
        }
        ListFooter(
            feedback = state.feedback,
            actions = actions,
            expanded = !compact && atTop,
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }
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
        oldestWaiting = previewItems.oldestWaiting(),
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

@Preview(name = "Sessions, cover screen", widthDp = 399, heightDp = 353)
@Composable
private fun SessionListCoverPreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            SessionListScreen(previewListState(ConnectionState.Offline(retryAtMs = null)), SessionListActions())
        }
    }
}

@Preview(name = "Sessions, empty", widthDp = 399, heightDp = 353)
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

@Preview(name = "Sessions, half folded", widthDp = 360, heightDp = 840)
@Composable
private fun SessionListHalfFoldedPreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            SessionListScreen(previewListState(), SessionListActions(), hinge = previewHalfFoldHinge)
        }
    }
}
