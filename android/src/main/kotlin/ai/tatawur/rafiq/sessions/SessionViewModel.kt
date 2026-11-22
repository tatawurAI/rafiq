package ai.tatawur.rafiq.sessions

import ai.tatawur.rafiq.client.ApprovalCard
import ai.tatawur.rafiq.client.CommandResult
import ai.tatawur.rafiq.client.ConnectionState
import ai.tatawur.rafiq.client.SessionRepository
import ai.tatawur.rafiq.client.SessionScreenState
import ai.tatawur.rafiq.client.ThreadTab
import ai.tatawur.rafiq.client.TimelineItem
import ai.tatawur.rafiq.protocol.AgentState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Everything the session view shows.
 *
 * @property selectedThread the open tab: the one you picked while it exists, otherwise the main thread.
 * @property timeline the selected thread's items, oldest first.
 * @property pinned the newest open approval, pinned above the composer.
 * @property sending true while a prompt is on its way. The draft stays until the bridge takes it.
 * @property scrollToKey a timeline item the list should bring into view once, after "Jump to it".
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
) {
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

/** State holder for one session's view. It reads only from [repository]. */
@Suppress("TooManyFunctions") // One function per action on the screen; splitting would split its state.
class SessionViewModel(
    private val repository: SessionRepository,
    private val sessionId: String,
) : ViewModel() {
    private data class Local(
        val selectedThreadId: String? = null,
        val draft: String = "",
        val sending: Boolean = false,
        val answering: Set<String> = emptySet(),
        val feedback: Feedback? = null,
        val scrollToKey: String? = null,
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
        local.update { it.copy(draft = text) }
    }

    /** Adds dictated text to the end of the draft. */
    fun appendDictation(text: String) {
        val spoken = text.trim()
        if (spoken.isEmpty()) return
        local.update { it.copy(draft = if (it.draft.isBlank()) spoken else "${it.draft.trimEnd()} $spoken") }
    }

    /**
     * Sends the draft. It is cleared only once the bridge takes it. Your message then arrives back as
     * an event, so nothing is echoed locally.
     */
    fun send() {
        val text = local.value.draft.trim()
        if (text.isEmpty()) return
        prompt(text, clearDraft = true)
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

    private fun prompt(
        text: String,
        clearDraft: Boolean,
    ) {
        if (local.value.sending) return
        local.update { it.copy(sending = true) }
        viewModelScope.launch {
            val result = repository.prompt(sessionId, text)
            local.update {
                it.copy(
                    sending = false,
                    draft = if (clearDraft && result is CommandResult.Ok) "" else it.draft,
                    feedback = result.feedback(),
                )
            }
        }
    }

    private companion object {
        // Keeps the session's socket open across a configuration change such as unfolding.
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
