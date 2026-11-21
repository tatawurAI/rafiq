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
import ai.tatawur.rafiq.ui.fold.FoldSplit
import ai.tatawur.rafiq.ui.fold.Hinge
import ai.tatawur.rafiq.ui.fold.LocalHinge
import ai.tatawur.rafiq.ui.theme.RafiqTheme
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
 * Below the hinge: the pinned approval at full size, scrolling if it runs long, then the quick
 * replies when nothing waits, then the composer on the bottom edge.
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
        modifier = Modifier.fillMaxSize().padding(horizontal = spacing.medium, vertical = spacing.small),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        FeedbackBar(state.feedback, actions.onDismissFeedback)
        Box(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            val pinned = state.pinned
            if (pinned != null && !imeVisible) {
                PinnedApprovalFull(
                    approval = pinned,
                    answering = pinned.id in state.answering,
                    onAnswer = { optionId -> actions.onApprove(pinned.id, optionId) },
                    onJump = pinned.threadId?.let { { actions.onJump(pinned) } },
                )
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
    onPinnedCardOnScreen: (Boolean) -> Unit,
) {
    SessionHeader(state, actions)
    if (state.screen.threads.size > 1) {
        ThreadTabs(state.screen.threads, state.selectedThread?.id, actions.onSelectThread)
    }
    HorizontalDivider(color = RafiqTheme.colors.border, thickness = RafiqTheme.spacing.hairline)
    ConnectionBanner(state.connection)
    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        val threadId = state.selectedThread?.id
        if (!state.isLoaded) {
            LaunchedEffect(Unit) { onPinnedCardOnScreen(false) }
            CenteredNote(stringResource(R.string.session_loading))
        } else if (state.timeline.isEmpty()) {
            LaunchedEffect(Unit) { onPinnedCardOnScreen(false) }
            CenteredNote(stringResource(R.string.thread_empty))
        } else {
            // A list per thread, so each tab opens at its latest item.
            key(threadId) { Timeline(state, actions, compact, onPinnedCardOnScreen) }
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
) {
    val spacing = RafiqTheme.spacing
    val session = state.screen.session
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
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session?.title.orEmpty(),
                style = MaterialTheme.typography.titleLarge,
                color = RafiqTheme.colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (session != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.small),
                ) {
                    StatusDot(session.status)
                    Text(
                        text =
                            stringResource(
                                R.string.session_subtitle,
                                session.agent,
                                stringResource(statusLabel(session.status)),
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = RafiqTheme.colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (thread != null && thread.status == AgentState.WORKING) {
            IconButton(onClick = { actions.onCancel(thread.id) }) {
                Icon(
                    painter = painterResource(R.drawable.ic_stop),
                    contentDescription = stringResource(R.string.cancel_turn),
                    modifier = Modifier.size(spacing.icon),
                    tint = RafiqTheme.colors.text,
                )
            }
        }
    }
}

@Composable
internal fun CenteredNote(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(RafiqTheme.spacing.medium), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = RafiqTheme.colors.textMuted)
    }
}
