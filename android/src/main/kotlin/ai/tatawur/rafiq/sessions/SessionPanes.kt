package ai.tatawur.rafiq.sessions

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.client.ApprovalCard
import ai.tatawur.rafiq.client.ConnectionState
import ai.tatawur.rafiq.client.SessionScreenState
import ai.tatawur.rafiq.client.ThreadTab
import ai.tatawur.rafiq.client.TimelineItem
import ai.tatawur.rafiq.client.terminal.MirroredWindow
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
import ai.tatawur.rafiq.ui.components.sessionStatusLabel
import ai.tatawur.rafiq.ui.fold.FoldSplit
import ai.tatawur.rafiq.ui.fold.Hinge
import ai.tatawur.rafiq.ui.fold.LocalHinge
import ai.tatawur.rafiq.ui.theme.RafiqTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

/**
 * Below the hinge: the pinned approval at full size, scrolling if it runs long, then the quick
 * replies when nothing waits, then the composer on the bottom edge.
 *
 * While you type it holds only the feedback line and the composer, at their natural height, so
 * [FoldSplit] can give the pane exactly that much above the keyboard.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SessionBottomPane(
    state: SessionUiState,
    actions: SessionActions,
    dictation: DictationControl?,
) {
    val spacing = RafiqTheme.spacing
    val imeVisible = WindowInsets.isImeVisible
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(if (imeVisible) Modifier else Modifier.fillMaxHeight())
                .padding(horizontal = spacing.medium, vertical = spacing.small),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        FeedbackBar(state.feedback, actions.onDismissFeedback)
        if (!imeVisible) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                val pinned = state.pinned
                if (pinned != null) {
                    PinnedApprovalFull(
                        approval = pinned,
                        answering = pinned.id in state.answering,
                        onAnswer = { optionId -> actions.onApprove(pinned.id, optionId) },
                        onJump = pinned.threadId?.let { { actions.onJump(pinned) } },
                    )
                }
            }
        }
        SessionComposer(state, actions, dictation, showQuickReplies = !imeVisible)
    }
}

/** The header, tabs, connection banner and timeline, taking whatever height is left. */
@Composable
internal fun ColumnScope.TimelinePane(
    state: SessionUiState,
    actions: SessionActions,
    compact: Boolean,
    scroll: TimelineScroll,
    pinnedAnsweredBelow: Boolean,
    onPinnedCardOnScreen: (Boolean) -> Unit,
) {
    SessionHeader(state, actions)
    if (state.screen.threads.size > 1) {
        ThreadTabs(state.screen.threads, state.selectedThread?.id, actions.onSelectThread)
    }
    HorizontalDivider(color = RafiqTheme.colors.border, thickness = RafiqTheme.spacing.hairline)
    ConnectionBanner(state.connection, onPairAgain = actions.onPairAgain)
    val openHooks = actions.onOpenHooks
    if (state.offerHooks && openHooks != null) HooksOffer(onReview = openHooks, onDismiss = actions.onDismissHooksOffer)
    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        val threadId = state.selectedThread?.id
        if (!state.isLoaded) {
            LaunchedEffect(Unit) { onPinnedCardOnScreen(false) }
            CenteredNote(stringResource(R.string.session_loading))
        } else if (state.timeline.isEmpty() && state.echoes.isEmpty() && state.progress == null) {
            LaunchedEffect(Unit) { onPinnedCardOnScreen(false) }
            CenteredNote(stringResource(R.string.thread_empty))
        } else {
            // A list per thread, so each tab opens at its latest item.
            key(threadId) { Timeline(state, actions, compact, scroll, pinnedAnsweredBelow, onPinnedCardOnScreen) }
        }
    }
}

/**
 * The composer for this session. Quick replies show only while no approval waits, so "Yes, go
 * ahead" can never be mistaken for an answer to it.
 */
@Composable
internal fun SessionComposer(
    state: SessionUiState,
    actions: SessionActions,
    dictation: DictationControl?,
    showQuickReplies: Boolean,
) {
    state.refusal?.let { RefusalLine(it, actions.onDismissRefusal, actions.onOpenHooks) }
    Composer(
        state =
            ComposerState(
                draft = state.draft,
                target =
                    state.screen.session
                        ?.title
                        .orEmpty(),
                canPrompt = state.canPrompt,
                canSend = state.canSend,
                canStop = state.canStopMain,
            ),
        onDraftChange = actions.onDraftChange,
        onSend = actions.onSend,
        quickReplies = defaultQuickReplies(),
        onQuickReply = actions.onQuickReply,
        dictation = dictation,
        showQuickReplies = showQuickReplies && state.screen.openApprovals.isEmpty(),
    )
}

@Composable
internal fun SessionHeader(
    state: SessionUiState,
    actions: SessionActions,
    windowLabel: String? = null,
    onWindowLabelClick: (() -> Unit)? = null,
) {
    val spacing = RafiqTheme.spacing
    val thread = state.selectedThread
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.xSmall, vertical = spacing.xSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.xSmall),
    ) {
        IconButton(onClick = actions.onBack) {
            Icon(
                painter = painterResource(R.drawable.ic_back),
                contentDescription = stringResource(R.string.back),
                modifier = Modifier.size(spacing.icon),
                tint = RafiqTheme.colors.text,
            )
        }
        HeaderTitle(state, windowLabel, Modifier.weight(1f), onWindowLabelClick)
        actions.viewToggle?.let { ChatTerminalToggle(it) }
        if (actions.viewToggle?.terminal != true && thread != null && thread.status == AgentState.WORKING) {
            IconButton(onClick = { actions.onCancel(thread.id) }) {
                Icon(
                    painter = painterResource(R.drawable.ic_stop),
                    contentDescription = stringResource(R.string.cancel_turn),
                    modifier = Modifier.size(spacing.icon),
                    tint = RafiqTheme.colors.text,
                )
            }
        }
        actions.onOpenHooks?.let { SessionMenu(it) }
    }
}

/** The header's menu, for a session in tmux: the hook setup, where you can also remove the hooks. */
@Composable
private fun SessionMenu(onOpenHooks: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                painter = painterResource(R.drawable.ic_more),
                contentDescription = stringResource(R.string.session_menu),
                modifier = Modifier.size(RafiqTheme.spacing.icon),
                tint = RafiqTheme.colors.text,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.session_menu_hooks)) },
                onClick = {
                    open = false
                    onOpenHooks()
                },
            )
        }
    }
}

/**
 * The session's title, the mirrored window when Terminal shows, and the agent, status and permission mode.
 * Tapping the window label opens the window picker.
 */
@Composable
private fun HeaderTitle(
    state: SessionUiState,
    windowLabel: String?,
    modifier: Modifier = Modifier,
    onWindowLabelClick: (() -> Unit)? = null,
) {
    val spacing = RafiqTheme.spacing
    val session = state.screen.session
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            Text(
                text = session?.title.orEmpty(),
                style = MaterialTheme.typography.titleLarge,
                color = RafiqTheme.colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // Terminal mirrors one window, but the grant covers the whole session: say which.
            windowLabel?.let { MirroredWindowLabel(it, onWindowLabelClick) }
        }
        if (session != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.small),
            ) {
                StatusDot(session.status)
                val subtitle =
                    stringResource(
                        R.string.session_subtitle,
                        session.agent,
                        stringResource(sessionStatusLabel(session.status, state.mainHasMessages)),
                    )
                Text(
                    text =
                        session.permissionMode?.let { mode ->
                            stringResource(
                                R.string.session_subtitle,
                                subtitle,
                                stringResource(R.string.permission_mode, mode),
                            )
                        } ?: subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = RafiqTheme.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MirroredWindowLabel(
    label: String,
    onClick: (() -> Unit)?,
) {
    val picker = stringResource(R.string.terminal_windows_pick)
    Text(
        text = label,
        modifier =
            if (onClick != null) {
                Modifier
                    .heightIn(min = RafiqTheme.spacing.minTouchTarget)
                    .clickable(role = Role.Button, onClickLabel = picker, onClick = onClick)
                    .wrapContentHeight()
            } else {
                Modifier
            },
        style = MaterialTheme.typography.labelSmall,
        color = if (onClick != null) RafiqTheme.colors.accent else RafiqTheme.colors.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** "Window 1: build", or "Window 1" for a window with no name. */
@Composable
internal fun windowLabel(window: MirroredWindow): String =
    if (window.name.isEmpty()) {
        stringResource(R.string.terminal_window_unnamed, window.index)
    } else {
        stringResource(R.string.terminal_window, window.index, window.name)
    }

@Composable
internal fun CenteredNote(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(RafiqTheme.spacing.medium), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = RafiqTheme.colors.textMuted)
    }
}
