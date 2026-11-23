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
import ai.tatawur.rafiq.terminal.BiometricCheck
import ai.tatawur.rafiq.terminal.TerminalAccess
import ai.tatawur.rafiq.terminal.TerminalChoice
import ai.tatawur.rafiq.terminal.TerminalRoute
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

/**
 * The session view, wired to its [SessionViewModel]. A session your server serves as a terminal
 * gets the Chat and Terminal toggle, when [terminal] is given, and a session in tmux gets the hook
 * setup through [onOpenHooks].
 *
 * @param approvals the optional biometric check before answering an approval, or null for none.
 */
@Composable
fun SessionRoute(
    viewModel: SessionViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onPairAgain: () -> Unit = {},
    terminal: TerminalAccess? = null,
    approvals: ApprovalGate? = null,
    onOpenHooks: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val dictation = rememberDictation(viewModel::appendDictation)
    val approve = rememberGatedApprove(approvals, viewModel::approve)
    // Each time the screen shows, so a hook setup you just finished hides the offer.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.checkHooks() }
    val inTmux = state.inTmux
    val actions =
        remember(viewModel, onBack, onPairAgain, approve, inTmux, onOpenHooks) {
            SessionActions(
                onBack = onBack,
                onSelectThread = viewModel::selectThread,
                onDraftChange = viewModel::updateDraft,
                onSend = viewModel::send,
                onQuickReply = viewModel::quickReply,
                onCancel = viewModel::cancel,
                onApprove = approve,
                onJump = viewModel::jumpTo,
                onScrolledToKey = viewModel::scrolledToKey,
                onDismissFeedback = viewModel::dismissFeedback,
                onPairAgain = onPairAgain,
                onOpenHooks = if (inTmux) onOpenHooks else null,
                onDismissHooksOffer = viewModel::dismissHooksOffer,
                onDismissRefusal = viewModel::dismissRefusal,
            )
        }
    val hinge = LocalHinge.current
    if (terminal == null) {
        SessionScreen(state, actions, dictation, modifier, hinge = hinge)
        return
    }
    SessionOrTerminal(terminal, state, actions) { viewActions, showTerminal ->
        if (showTerminal) {
            TerminalRoute(terminal.port, state.screen.sessionId, hinge, viewModel::terminalHistory) { window, onPick ->
                SessionHeader(state, viewActions, window?.let { windowLabel(it) }, onPick)
            }
        } else {
            SessionScreen(state, viewActions, dictation, modifier, hinge = hinge)
        }
    }
}

/**
 * Chooses between Chat and Terminal. Only a session your server serves as a terminal offers the
 * toggle, and the cover screen always shows Chat. The window's height decides, not the layout's, so the keyboard never
 * counts as a cover screen. With the Terminal lock on, Terminal opens only after a strong biometric
 * check, and the check lasts until you leave Terminal or the app goes to the background.
 */
@Composable
private fun SessionOrTerminal(
    terminal: TerminalAccess,
    state: SessionUiState,
    actions: SessionActions,
    content: @Composable (SessionActions, showTerminal: Boolean) -> Unit,
) {
    val context = LocalContext.current
    // Until the setting has loaded, treat the lock as on.
    val lockOn by terminal.lock.collectAsStateWithLifecycle(initialValue = true)
    val cover = LocalConfiguration.current.screenHeightDp.dp < RafiqTheme.spacing.compactHeight
    var choice by remember(state.screen.sessionId) { mutableStateOf(TerminalChoice()) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { choice = choice.leftScreen() }
    LaunchedEffect(cover) { if (cover) choice = choice.leftScreen() }
    // Listed but not allowed in Terminal by your server: no toggle at all.
    val capable = state.screen.session?.let { it.terminal && it.terminalAllowed } == true
    val showTerminal = choice.shows(capable = capable, cover = cover, lockOn = lockOn)
    val title = stringResource(R.string.terminal_unlock_title)
    val subtitle = stringResource(R.string.terminal_unlock_subtitle)
    val cancel = stringResource(R.string.terminal_unlock_cancel)
    val toggle =
        ViewToggle(terminal = showTerminal) { wantsTerminal ->
            when {
                !wantsTerminal -> {
                    choice = choice.chat()
                }

                lockOn -> {
                    BiometricCheck.ask(context, title, subtitle, cancel) { passed ->
                        if (passed) choice = choice.passedCheck()
                    }
                }

                else -> {
                    choice = choice.terminal(lockOn = false)
                }
            }
        }
    content(if (cover || !capable) actions else actions.copy(viewToggle = toggle), showTerminal)
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
@OptIn(ExperimentalLayoutApi::class)
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
    val screenActions = remember(actions, scroll) { actions.scrollingOnSend(scroll) }
    // The bottom pane drops the pinned approval while you type, so the timeline's card then keeps
    // its own buttons.
    val answeredBelow = !WindowInsets.isImeVisible
    FoldSplit(
        hinge = hinge,
        modifier = modifier,
        fitBottomToKeyboard = true,
        single = { SingleColumnSession(state, screenActions, dictation, scroll) },
        top = {
            Column(modifier = Modifier.fillMaxSize()) {
                TimelinePane(state, screenActions, compact = false, scroll, pinnedAnsweredBelow = answeredBelow) {}
            }
        },
        bottom = { SessionBottomPane(state, screenActions, dictation) },
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
            TimelinePane(
                state,
                actions,
                compact,
                scroll,
                pinnedAnsweredBelow = false,
                onPinnedCardOnScreen = { pinnedCardOnScreen = it },
            )
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

internal fun previewState(): SessionUiState {
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

/** Sending a message or a quick prompt always takes the timeline to its newest item. */
private fun SessionActions.scrollingOnSend(scroll: TimelineScroll): SessionActions =
    copy(
        onSend = {
            scroll.sent()
            onSend()
        },
        onQuickReply = { reply ->
            if (reply is QuickReply.Prompt) scroll.sent()
            onQuickReply(reply)
        },
    )
