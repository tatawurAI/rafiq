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
import ai.tatawur.rafiq.ui.fold.previewHalfFoldHinge
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

/** The session view, wired to its [SessionViewModel]. */
@Composable
fun SessionRoute(
    viewModel: SessionViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onPairAgain: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val dictation = rememberDictation(viewModel::appendDictation)
    val actions =
        remember(viewModel, onBack, onPairAgain) {
            SessionActions(
                onBack = onBack,
                onSelectThread = viewModel::selectThread,
                onDraftChange = viewModel::updateDraft,
                onSend = viewModel::send,
                onQuickReply = viewModel::quickReply,
                onCancel = viewModel::cancel,
                onApprove = viewModel::approve,
                onJump = viewModel::jumpTo,
                onScrolledToKey = viewModel::scrolledToKey,
                onDismissFeedback = viewModel::dismissFeedback,
                onPairAgain = onPairAgain,
            )
        }
    SessionScreen(state, actions, dictation, modifier, hinge = LocalHinge.current)
}

/**
 * One session: a header, the thread tabs, the selected thread's timeline, then the pinned approval
 * and the composer.
 *
 * The screen is the only consumer of the window insets, keyboard included, so with the keyboard up
 * the whole column ends above it. While you type, the pinned approval and the quick replies step
 * aside so the timeline keeps its room. The pinned approval also hides while its own tool card is
 * on screen, so the same request never shows twice.
 *
 * Half folded, the conversation sits above the hinge and the controls below it, out of the crease:
 * the pinned approval with every option at full size, the quick replies when nothing waits, and the
 * composer on the bottom edge. Only the bottom pane moves up for the keyboard.
 *
 * @param hinge where the half-folded hinge crosses the window, or null to keep one column.
 */
@Composable
fun SessionScreen(
    state: SessionUiState,
    actions: SessionActions,
    dictation: DictationControl?,
    modifier: Modifier = Modifier,
    hinge: Hinge? = null,
) {
    // Held above the split: folding moves the timeline between panes, and it keeps its place.
    val scroll = rememberTimelineScroll(state.selectedThread?.id)
    FoldSplit(
        hinge = hinge,
        modifier = modifier,
        single = { SingleColumnSession(state, actions, dictation, scroll) },
        top = {
            Column(modifier = Modifier.fillMaxSize()) { TimelinePane(state, actions, compact = false, scroll) {} }
        },
        bottom = { SessionBottomPane(state, actions, dictation) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SingleColumnSession(
    state: SessionUiState,
    actions: SessionActions,
    dictation: DictationControl?,
    scroll: TimelineScroll,
) {
    val spacing = RafiqTheme.spacing
    val imeVisible = WindowInsets.isImeVisible
    var pinnedCardOnScreen by remember { mutableStateOf(false) }
    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .consumeWindowInsets(WindowInsets.safeDrawing),
    ) {
        val compact = maxHeight < spacing.compactHeight
        Column(modifier = Modifier.fillMaxSize()) {
            TimelinePane(state, actions, compact, scroll, onPinnedCardOnScreen = { pinnedCardOnScreen = it })
            HorizontalDivider(color = RafiqTheme.colors.border, thickness = spacing.hairline)
            Column(
                modifier = Modifier.padding(horizontal = spacing.medium, vertical = spacing.small),
                verticalArrangement = Arrangement.spacedBy(spacing.small),
            ) {
                FeedbackBar(state.feedback, actions.onDismissFeedback)
                val pinned = state.pinned
                if (pinned != null && !imeVisible && !pinnedCardOnScreen) {
                    PinnedApproval(
                        approval = pinned,
                        answering = pinned.id in state.answering,
                        onAnswer = { optionId -> actions.onApprove(pinned.id, optionId) },
                        onJump = pinned.threadId?.let { { actions.onJump(pinned) } },
                    )
                }
                SessionComposer(state, actions, dictation, showQuickReplies = !imeVisible)
            }
        }
    }
}

private val previewSession =
    Session(
        "s1",
        "claude-code",
        "/srv/weather-app",
        "weather-app",
        AgentState.WORKING,
        createdAtMs = 0,
        updatedAtMs = 0,
    )

private val previewThreads =
    listOf(
        ThreadTab("m", null, ThreadKind.MAIN, "Main", AgentState.WORKING, 1),
        ThreadTab("r", "m", ThreadKind.SUBAGENT, "Forecast API research", AgentState.WORKING, 0),
        ThreadTab("t", "m", ThreadKind.SUBAGENT, "Test writer", AgentState.DONE, 0),
    )

private val previewTimeline =
    listOf(
        TimelineItem.Message(
            "u1",
            1,
            MessageRole.USER,
            listOf(
                ContentBlock.Text(
                    "Move the forecast parsing into its own module. Check how the API reports units first.",
                ),
            ),
            isStreaming = false,
        ),
        TimelineItem.Message(
            "a1",
            2,
            MessageRole.AGENT,
            listOf(
                ContentBlock.Markdown(
                    "I'll start two helpers: one to map the **unit fields**, one to pin the parser in tests.",
                ),
            ),
            isStreaming = false,
        ),
        TimelineItem.Tool(
            "e1",
            3,
            ToolCallKind.EDIT,
            "Edited 3 files",
            "src/forecast/Units.kt +48",
            ToolCallStatus.COMPLETED,
            null,
        ),
        TimelineItem.Tool(
            "c1",
            4,
            ToolCallKind.COMMAND,
            "./gradlew :forecast:test",
            "38 tests passed",
            ToolCallStatus.COMPLETED,
            null,
        ),
        TimelineItem.Message(
            "a2",
            5,
            MessageRole.AGENT,
            listOf(
                ContentBlock.Markdown(
                    "Units now sit in their own module. Waiting on the test writer before I touch the parser",
                ),
            ),
            isStreaming = true,
        ),
    )

private fun previewState(): SessionUiState {
    val pinned = previewApproval.copy(threadId = "m")
    val screen =
        SessionScreenState(
            sessionId = "s1",
            session = previewSession,
            threads = previewThreads,
            timelines = mapOf("m" to previewTimeline),
            openApprovals = listOf(pinned),
        )
    return SessionUiState(
        screen = screen,
        isLoaded = true,
        selectedThread = previewThreads.first(),
        timeline = previewTimeline,
        pinned = pinned,
        draft = "",
        connection = ConnectionState.Live,
    )
}

@Preview(name = "Session, tall", widthDp = 412, heightDp = 920)
@Composable
private fun SessionScreenTallPreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            SessionScreen(previewState(), SessionActions(), DictationControl(listening = false, onToggle = {}))
        }
    }
}

@Preview(name = "Session, cover screen", widthDp = 399, heightDp = 353)
@Composable
private fun SessionScreenCoverPreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            SessionScreen(previewState(), SessionActions(), dictation = null)
        }
    }
}

@Preview(name = "Session, half folded", widthDp = 360, heightDp = 840)
@Composable
private fun SessionScreenHalfFoldedPreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            SessionScreen(previewState(), SessionActions(), dictation = null, hinge = previewHalfFoldHinge)
        }
    }
}

@Preview(name = "Session, offline and busy", widthDp = 412, heightDp = 920)
@Composable
private fun SessionScreenOfflinePreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            SessionScreen(
                previewState().copy(
                    pinned = null,
                    connection = ConnectionState.Offline(retryAtMs = null),
                    feedback = Feedback.StillWorking,
                    draft = "Then update the README",
                ),
                SessionActions(),
                dictation = null,
            )
        }
    }
}
