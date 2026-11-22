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
import androidx.compose.runtime.rememberUpdatedState
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance

/**
 * The selected thread's items. It follows new content while you are at the bottom and stays put
 * once you scroll up, with a button to jump back down. Items are keyed by id, so a message that is
 * replaced or grows keeps its place.
 *
 * @param scroll the thread's scroll position, held by the screen.
 * @param pinnedAnsweredBelow true when the pinned approval's options show below the hinge, so its
 *   card here shows the request without them.
 * @param onPinnedCardOnScreen told whether the tool card that carries the pinned approval is
 *   mostly on screen, so the screen can hide the pinned copy.
 */
@Composable
internal fun Timeline(
    state: SessionUiState,
    actions: SessionActions,
    compact: Boolean,
    scroll: TimelineScroll,
    pinnedAnsweredBelow: Boolean,
    onPinnedCardOnScreen: (Boolean) -> Unit = {},
) {
    val spacing = RafiqTheme.spacing
    val items = state.timeline
    val listState = scroll.listState(items.lastIndex)
    val lastIndex by rememberUpdatedState(items.lastIndex)
    FollowWhileAtBottom(listState, scroll::userSettled)
    ReportPinnedCard(listState, items, state.pinned?.id, onPinnedCardOnScreen)

    // Runs each time Chat is shown again, for example after Terminal or another screen.
    LaunchedEffect(Unit) { scroll.returned() }
    LaunchedEffect(items.size, items.lastOrNull()) {
        if (state.scrollToKey == null) scroll.contentChanged(items.lastOrNull()?.key)
    }
    LaunchedEffect(scroll.pendingScrollToEnd) {
        if (scroll.pendingScrollToEnd) {
            listState.scrollToEnd(lastIndex)
            scroll.scrolledToEnd()
        }
    }
    LaunchedEffect(state.scrollToKey) {
        val target = state.scrollToKey ?: return@LaunchedEffect
        val index =
            items.indexOfFirst {
                it.key == target || (it as? TimelineItem.Tool)?.approval?.toolCallId == target
            }
        if (index >= 0) {
            scroll.jumpedAway()
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
                val answerBelow = answersBelow(pinnedAnsweredBelow, item, state.pinned?.id)
                TimelineEntry(item, state.answering, actions.onApprove, answerBelow)
            }
        }
        val rule = scroll.rule
        val toBottom = Modifier.align(Alignment.BottomCenter)
        when {
            rule.newBelow -> NewMessagesPill(onClick = scroll::jumpedToLatest, modifier = toBottom)
            !rule.following -> JumpToLatestButton(onClick = scroll::jumpedToLatest, modifier = toBottom)
        }
    }
}

@Composable
private fun TimelineEntry(
    item: TimelineItem,
    answering: Set<String>,
    onApprove: (approvalId: String, optionId: String) -> Unit,
    answerBelow: Boolean,
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
                answerBelow = answerBelow,
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

/** Shown instead of [JumpToLatestButton] when messages arrived below while you read higher up. */
@Composable
internal fun NewMessagesPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = RafiqTheme.spacing
    Surface(
        onClick = onClick,
        modifier = modifier.padding(spacing.small).heightIn(min = spacing.minTouchTarget),
        shape = RafiqTheme.shapes.pill,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = spacing.medium, vertical = spacing.smallMedium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_down),
                contentDescription = null,
                modifier = Modifier.size(spacing.icon),
            )
            Text(text = stringResource(R.string.new_messages), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Preview(name = "New messages pill", widthDp = 360)
@Composable
private fun NewMessagesPillPreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                NewMessagesPill(onClick = {})
            }
        }
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

/**
 * Tells [onChange] whether the tool card carrying approval [pinnedId] is mostly on screen: at
 * least half of it, or half the viewport for a card taller than that.
 */
@Composable
private fun ReportPinnedCard(
    listState: LazyListState,
    items: List<TimelineItem>,
    pinnedId: String?,
    onChange: (Boolean) -> Unit,
) {
    val latestOnChange by rememberUpdatedState(onChange)
    LaunchedEffect(listState, items, pinnedId) {
        val index =
            if (pinnedId ==
                null
            ) {
                -1
            } else {
                items.indexOfFirst { (it as? TimelineItem.Tool)?.approval?.id == pinnedId }
            }
        snapshotFlow {
            val layout = listState.layoutInfo
            val card = layout.visibleItemsInfo.firstOrNull { it.index == index } ?: return@snapshotFlow false
            val shown =
                minOf(card.offset + card.size, layout.viewportEndOffset) -
                    maxOf(card.offset, layout.viewportStartOffset)
            val viewport = layout.viewportEndOffset - layout.viewportStartOffset
            shown * 2 >= minOf(card.size, viewport)
        }.distinctUntilChanged()
            .collect { latestOnChange(it) }
    }
}

private suspend fun LazyListState.scrollToEnd(lastIndex: Int) {
    if (lastIndex < 0) return
    // An offset past the item's height clamps to the end, so a tall last message shows its bottom.
    scrollToItem(lastIndex, Int.MAX_VALUE)
}

/**
 * Whether [item]'s card leaves its options to the bottom pane: only the card of the pinned approval
 * [pinnedId], and only while the bottom pane shows it. Older open approvals keep their buttons.
 */
internal fun answersBelow(
    pinnedAnsweredBelow: Boolean,
    item: TimelineItem,
    pinnedId: String?,
): Boolean = pinnedAnsweredBelow && pinnedId != null && (item as? TimelineItem.Tool)?.approval?.id == pinnedId
