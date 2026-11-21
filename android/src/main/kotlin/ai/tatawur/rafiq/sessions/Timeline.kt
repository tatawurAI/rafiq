package ai.tatawur.rafiq.sessions

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.client.ApprovalCard
import ai.tatawur.rafiq.client.ConnectionState
import ai.tatawur.rafiq.client.SessionScreenState
import ai.tatawur.rafiq.client.ThreadTab
import ai.tatawur.rafiq.client.TimelineItem
import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.ContentBlock
import ai.tatawur.rafiq.protocol.MessageRole
import ai.tatawur.rafiq.protocol.Session
import ai.tatawur.rafiq.protocol.ThreadKind
import ai.tatawur.rafiq.protocol.ToolCallKind
import ai.tatawur.rafiq.protocol.ToolCallStatus
import ai.tatawur.rafiq.ui.components.ConnectionBanner
import ai.tatawur.rafiq.ui.components.FeedbackBar
import ai.tatawur.rafiq.ui.components.StatusDot
import ai.tatawur.rafiq.ui.components.statusLabel
import ai.tatawur.rafiq.ui.theme.RafiqTheme
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

/**
 * The selected thread's items. It follows new content while you are at the bottom and stays put
 * once you scroll up, with a button to jump back down. Items are keyed by id, so a message that is
 * replaced or grows keeps its place.
 */
@Composable
internal fun Timeline(
    state: SessionUiState,
    actions: SessionActions,
    compact: Boolean,
) {
    val spacing = RafiqTheme.spacing
    val items = state.timeline
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = items.lastIndex.coerceAtLeast(0))
    var follow by rememberSaveable { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    FollowWhileAtBottom(listState) { follow = it }

    LaunchedEffect(items.size, items.lastOrNull()) {
        if (follow && state.scrollToKey == null) listState.scrollToEnd(items.lastIndex)
    }
    LaunchedEffect(state.scrollToKey) {
        val target = state.scrollToKey ?: return@LaunchedEffect
        val index =
            items.indexOfFirst {
                it.key == target || (it as? TimelineItem.Tool)?.approval?.toolCallId == target
            }
        if (index >= 0) {
            follow = false
            listState.animateScrollToItem(index)
        }
        actions.onScrolledToKey()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    horizontal = spacing.medium,
                    vertical = if (compact) spacing.small else spacing.medium,
                ),
            verticalArrangement = Arrangement.spacedBy(spacing.smallMedium),
        ) {
            items(items, key = { it.key }, contentType = { it::class }) { item ->
                TimelineEntry(item, state.answering, actions.onApprove)
            }
        }
        if (!follow) {
            JumpToLatestButton(
                onClick = {
                    follow = true
                    scope.launch { listState.scrollToEnd(items.lastIndex) }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun TimelineEntry(
    item: TimelineItem,
    answering: Set<String>,
    onApprove: (approvalId: String, optionId: String) -> Unit,
) {
    when (item) {
        is TimelineItem.Message -> {
            MessageItem(item)
        }

        is TimelineItem.Tool -> {
            var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
            ToolCard(
                tool = item,
                expanded = expanded,
                onToggle = { expanded = !expanded },
                answering = item.approval?.id in answering,
                onAnswer = { optionId -> item.approval?.let { onApprove(it.id, optionId) } },
            )
        }

        is TimelineItem.Notice -> {
            NoticeLine(item)
        }
    }
}

@Composable
private fun JumpToLatestButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = RafiqTheme.spacing
    SmallFloatingActionButton(
        onClick = onClick,
        modifier = modifier.padding(spacing.small).heightIn(min = spacing.minTouchTarget),
        containerColor = RafiqTheme.colors.border,
        contentColor = RafiqTheme.colors.text,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_arrow_down),
            contentDescription = stringResource(R.string.jump_to_latest),
            modifier = Modifier.size(spacing.icon),
        )
    }
}

/**
 * Decides whether the list follows new content. Only your own scrolling changes it: when a drag
 * and its fling settle, the list follows again if they ended at the bottom. Scrolls the screen
 * makes itself never turn following off.
 */
@Composable
private fun FollowWhileAtBottom(
    listState: LazyListState,
    onFollowChange: (Boolean) -> Unit,
) {
    var dragged by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions
            .filterIsInstance<DragInteraction.Start>()
            .collect { dragged = true }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { inProgress -> !inProgress && dragged }
            .collect {
                dragged = false
                onFollowChange(!listState.canScrollForward)
            }
    }
}

private suspend fun LazyListState.scrollToEnd(lastIndex: Int) {
    if (lastIndex < 0) return
    // An offset past the item's height clamps to the end, so a tall last message shows its bottom.
    scrollToItem(lastIndex, Int.MAX_VALUE)
}
