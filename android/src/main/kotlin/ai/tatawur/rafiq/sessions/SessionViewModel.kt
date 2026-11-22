package ai.tatawur.rafiq.sessions

import ai.tatawur.rafiq.client.ApprovalCard
import ai.tatawur.rafiq.client.CommandResult
import ai.tatawur.rafiq.client.ConnectionState
import ai.tatawur.rafiq.client.PromptEcho
import ai.tatawur.rafiq.client.SentPrompt
import ai.tatawur.rafiq.client.SessionRepository
import ai.tatawur.rafiq.client.SessionScreenState
import ai.tatawur.rafiq.client.ThreadTab
import ai.tatawur.rafiq.client.TimelineItem
import ai.tatawur.rafiq.client.promptEchoes
import ai.tatawur.rafiq.client.terminal.Scrollback
import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.api.SendRefusal
import ai.tatawur.rafiq.protocol.api.TerminalHistory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Everything the session view shows.
 *
 * @property selectedThread the open tab: the one you picked while it exists, otherwise the main thread.
 * @property timeline the selected thread's items, oldest first.
 * @property pinned the newest open approval, pinned above the composer.
 * @property sending true while a prompt is on its way. The draft stays until the bridge takes it.
 * @property scrollToKey a timeline item the list should bring into view once, after "Jump to it".
 * @property echoes prompts you sent to a session in tmux that the agent does not have yet, oldest first.
 * @property refusal why your server would not type the last prompt, shown in the composer. The draft stays.
 * @property offerHooks true when Chat for this session needs the reader's hooks and you have not set them
 *   up, so the screen offers the hook setup.
 */
data class SessionUiState(
    val screen: SessionScreenState,
    val isLoaded: Boolean = false,
    val selectedThread: ThreadTab? = null,
    val timeline: List<TimelineItem> = emptyList(),
    val pinned: ApprovalCard? = null,
    val draft: String = "",
    val sending: Boolean = false,
    val answering: Set<String> = emptySet(),
    val feedback: Feedback? = null,
    val connection: ConnectionState = ConnectionState.Connecting,
    val scrollToKey: String? = null,
    val echoes: List<PromptEcho> = emptyList(),
    val refusal: ComposerRefusal? = null,
    val offerHooks: Boolean = false,
) {
    /** The selected thread's running turn, for the progress line, or null when none runs. */
    val progress: EventPayload.TurnProgress?
        get() = selectedThread?.let { screen.progress[it.id] }

    /** True for a session in tmux, which has Chat through hooks and a send guard on your server. */
    val inTmux: Boolean
        get() = screen.session?.terminal == true

    /** Prompts and quick replies are accepted only while the main thread is not working. */
    val canPrompt: Boolean
        get() = screen.canPrompt && !sending

    val canSend: Boolean
        get() = canPrompt && draft.isNotBlank()

    /** Whether the main thread has any message yet. A session without one has not run a turn. */
    val mainHasMessages: Boolean
        get() =
            screen.mainThread
                ?.let { screen.timelines[it.id] }
                .orEmpty()
                .any { it is TimelineItem.Message }

    /** The main thread can be stopped, for the "Stop" quick reply. */
    val canStopMain: Boolean
        get() = screen.mainThread?.status == AgentState.WORKING
}

/** What a quick-reply chip does. */
sealed interface QuickReply {
    /** Sends [text] as a prompt, as if you had typed it. */
    data class Prompt(
        val text: String,
    ) : QuickReply

    /** Stops the main thread's running turn. */
    data object Stop : QuickReply
}

/**
 * Your server would not type a prompt into the pane. [code] is one of [SendRefusal], and [message] the
 * server's own words, shown when this build has no copy of its own for [code].
 */
data class ComposerRefusal(
    val code: String,
    val message: String,
)

/**
 * State holder for one session's view. It reads only from [repository].
 *
 * @param newClientId makes the id each prompt carries, so its delivery events can be matched to it.
 */
@Suppress("TooManyFunctions") // One function per action on the screen; splitting would split its state.
class SessionViewModel(
    private val repository: SessionRepository,
    private val sessionId: String,
    private val newClientId: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private data class Local(
        val selectedThreadId: String? = null,
        val draft: String = "",
        val sending: Boolean = false,
        val answering: Set<String> = emptySet(),
        val feedback: Feedback? = null,
        val scrollToKey: String? = null,
        val sent: List<SentPrompt> = emptyList(),
        val refusal: ComposerRefusal? = null,
        val hooksMissing: Boolean = false,
        val hooksDismissed: Boolean = false,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<SessionUiState> =
        combine(repository.session(sessionId), local, repository.connection) { screen, local, connection ->
            val selected =
                screen.threads.firstOrNull { it.id == local.selectedThreadId }
                    ?: screen.mainThread
                    ?: screen.threads.firstOrNull()
            val openIds = screen.openApprovals.mapTo(mutableSetOf()) { it.id }
            SessionUiState(
                screen = screen,
                isLoaded = true,
                selectedThread = selected,
                timeline = selected?.let { screen.timelines[it.id] }.orEmpty(),
                pinned = screen.openApprovals.lastOrNull(),
                draft = local.draft,
                sending = local.sending,
                answering = local.answering intersect openIds,
                feedback = local.feedback,
                connection = connection,
                scrollToKey = local.scrollToKey,
                echoes = promptEchoes(local.sent, screen.deliveries),
                refusal = local.refusal,
                offerHooks = local.hooksMissing && !local.hooksDismissed,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            SessionUiState(screen = SessionScreenState(sessionId)),
        )

    fun selectThread(threadId: String) {
        local.update { it.copy(selectedThreadId = threadId) }
    }

    fun updateDraft(text: String) {
        local.update { it.copy(draft = text, refusal = if (text.isEmpty()) it.refusal else null) }
    }

    /**
     * Asks your server whether this session's project has the reader's hooks, so Chat can offer to set
     * them up. Only a session in tmux has them. Called when the screen shows, so a setup you just finished
     * hides the offer.
     */
    fun checkHooks() {
        viewModelScope.launch {
            // The cached session may still be loading when the screen first shows.
            if (!state.first { it.screen.session != null }.inTmux) return@launch
            val result = repository.hookSetup(sessionId)
            if (result is CommandResult.Ok) local.update { it.copy(hooksMissing = !result.value.installed) }
        }
    }

    /** You chose "Not now" on the hook setup offer. It stays hidden until the screen is opened again. */
    fun dismissHooksOffer() {
        local.update { it.copy(hooksDismissed = true) }
    }

    /** Adds dictated text to the end of the draft. */
    fun appendDictation(text: String) {
        val spoken = text.trim()
        if (spoken.isEmpty()) return
        local.update { it.copy(draft = if (it.draft.isBlank()) spoken else "${it.draft.trimEnd()} $spoken") }
    }

    /**
     * Sends the draft. It is cleared only once the bridge takes it. Your message then arrives back as
     * an event. For a session in tmux the text goes exactly as typed, leading `!` and `/` included, and
     * it shows as sent or queued until the agent has it.
     */
    fun send() {
        val draft = local.value.draft
        if (draft.isBlank()) return
        prompt(if (state.value.inTmux) draft else draft.trim(), clearDraft = true)
    }

    fun quickReply(reply: QuickReply) {
        when (reply) {
            is QuickReply.Prompt -> {
                prompt(reply.text, clearDraft = false)
            }

            QuickReply.Stop -> {
                state.value.screen.mainThread
                    ?.let { cancel(it.id) }
            }
        }
    }

    fun cancel(threadId: String) {
        viewModelScope.launch {
            local.update { it.copy(feedback = repository.cancel(sessionId, threadId).feedback()) }
        }
    }

    /**
     * Answers an approval. Its buttons stay disabled until the repository drops it. They come back
     * only when the answer can be tried again: an approval that the bridge says is already answered
     * or unknown stays disabled until its `approval_resolved` event clears it.
     */
    fun approve(
        approvalId: String,
        optionId: String,
    ) {
        if (approvalId in local.value.answering) return
        local.update { it.copy(answering = it.answering + approvalId) }
        viewModelScope.launch {
            val result = repository.approve(approvalId, optionId)
            local.update {
                it.copy(
                    answering = if (result.canRetryAnswer()) it.answering - approvalId else it.answering,
                    feedback = result.feedback(),
                )
            }
        }
    }

    /** Opens the approval's thread and asks the list to scroll to its tool card. */
    fun jumpTo(approval: ApprovalCard) {
        local.update {
            it.copy(
                selectedThreadId = approval.threadId ?: it.selectedThreadId,
                scrollToKey = approval.toolCallId,
            )
        }
    }

    fun scrolledToKey() {
        local.update { it.copy(scrollToKey = null) }
    }

    fun dismissFeedback() {
        local.update { it.copy(feedback = null) }
    }

    fun dismissRefusal() {
        local.update { it.copy(refusal = null) }
    }

    /** Read-only scrollback of the pane in tmux window [window], for the Terminal overlay. */
    suspend fun terminalHistory(window: Int?): CommandResult<TerminalHistory> =
        repository.terminalHistory(sessionId, window, Scrollback.HISTORY_LINES)

    private fun prompt(
        text: String,
        clearDraft: Boolean,
    ) {
        if (local.value.sending) return
        val inTmux = state.value.inTmux
        val clientId = newClientId()
        local.update { it.copy(sending = true, refusal = null) }
        viewModelScope.launch {
            val result = repository.prompt(sessionId, text, clientId)
            val refusal = result.refusal()
            local.update {
                val accepted = result is CommandResult.Ok
                it.copy(
                    sending = false,
                    draft = if (clearDraft && accepted) "" else it.draft,
                    feedback = if (refusal == null) result.feedback() else null,
                    refusal = refusal,
                    sent = if (accepted && inTmux) it.stillPending() + SentPrompt(clientId, text) else it.sent,
                    hooksMissing = it.hooksMissing || refusal?.code == SendRefusal.HOOKS_NOT_INSTALLED,
                    hooksDismissed = it.hooksDismissed && refusal?.code != SendRefusal.HOOKS_NOT_INSTALLED,
                )
            }
        }
    }

    /** The sent prompts the agent does not have yet. Delivered ones are dropped so the list stays short. */
    private fun Local.stillPending(): List<SentPrompt> {
        val waiting = promptEchoes(sent, state.value.screen.deliveries).mapTo(mutableSetOf()) { it.clientId }
        return sent.filter { it.clientId in waiting }
    }

    private companion object {
        // Keeps the session's socket open across a configuration change such as unfolding.
        const val STOP_TIMEOUT_MS = 5_000L

        val REFUSAL_CODES =
            setOf(
                SendRefusal.AGENT_WAITING,
                SendRefusal.INPUT_NOT_EMPTY,
                SendRefusal.AGENT_NOT_RUNNING,
                SendRefusal.STATE_UNKNOWN,
                SendRefusal.HOOKS_NOT_INSTALLED,
                SendRefusal.NOT_CONFIRMED,
            )

        /** The send guard's refusal, when that is why the prompt was not taken. */
        fun CommandResult<*>.refusal(): ComposerRefusal? =
            (this as? CommandResult.Failed)?.takeIf { it.code in REFUSAL_CODES }?.let {
                ComposerRefusal(
                    it.code,
                    it.message,
                )
            }
    }
}
