package ai.tatawur.rafiq.client

import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.ApprovalOption
import ai.tatawur.rafiq.protocol.ApprovalOptionKind
import ai.tatawur.rafiq.protocol.ContentBlock
import ai.tatawur.rafiq.protocol.MessageRole
import ai.tatawur.rafiq.protocol.Session
import ai.tatawur.rafiq.protocol.ThreadKind
import ai.tatawur.rafiq.protocol.ToolCallKind
import ai.tatawur.rafiq.protocol.ToolCallStatus

/**
 * One row of the session list.
 *
 * @property lastLine the preview line under the title, or null when the session has no messages yet.
 * @property openApprovals what the row's inline approve and deny buttons act on, oldest first.
 */
data class SessionListItem(
    val session: Session,
    val lastLine: String?,
    val openApprovals: List<ApprovalCard>,
) {
    /** True when the row belongs under the "needs you" filter. */
    val needsYou: Boolean
        get() = openApprovals.isNotEmpty() || session.status == AgentState.WAITING_ON_YOU
}

/**
 * An approval waiting on you, until its `approval_resolved` event arrives.
 *
 * @property threadId the thread of the tool call it belongs to, or null when that call is not known.
 * @property options in display order: allow once, allow always, deny, then unknown kinds.
 */
data class ApprovalCard(
    val id: String,
    val sessionId: String,
    val threadId: String?,
    val toolCallId: String,
    val description: String,
    val options: List<ApprovalOption>,
    val expiresAtMs: Long?,
) {
    companion object {
        private val kindOrder =
            listOf(ApprovalOptionKind.ALLOW_ONCE, ApprovalOptionKind.ALLOW_ALWAYS, ApprovalOptionKind.DENY)

        /** Sorts options into display order, keeping the agent's order within a kind. */
        fun ordered(options: List<ApprovalOption>): List<ApprovalOption> =
            options.sortedBy { option -> kindOrder.indexOf(option.kind).takeIf { it >= 0 } ?: kindOrder.size }
    }
}

/**
 * A thread as a tab in the session view.
 *
 * @property openApprovals how many approvals on this thread wait on you, for the tab's badge.
 */
data class ThreadTab(
    val id: String,
    val parentThreadId: String?,
    val kind: ThreadKind,
    val title: String,
    val status: AgentState,
    val openApprovals: Int,
)

/**
 * Everything the session view shows for one session.
 *
 * @property session null until the session's first `session_updated` event is cached.
 * @property threads the main thread first, then the others in the order they first appeared.
 * @property timelines each thread's items keyed by thread id, oldest first. Events without a thread
 *   (sign-ins, previews, file changes, unknown kinds) go on the main thread.
 * @property openApprovals every approval still waiting on you, oldest first. The view pins the newest.
 * @property lastSeq the newest event `seq` applied. The socket resumes after it.
 */
data class SessionScreenState(
    val sessionId: String,
    val session: Session? = null,
    val threads: List<ThreadTab> = emptyList(),
    val timelines: Map<String, List<TimelineItem>> = emptyMap(),
    val openApprovals: List<ApprovalCard> = emptyList(),
    val lastSeq: Long = 0,
) {
    /** The main thread, once its `thread_updated` event has arrived. Prompts go to it. */
    val mainThread: ThreadTab?
        get() = threads.firstOrNull { it.kind == ThreadKind.MAIN }

    /** False while the main thread is working: the bridge answers a prompt then with 409. */
    val canPrompt: Boolean
        get() = mainThread?.let { it.status != AgentState.WORKING } ?: false
}

/**
 * One entry in a thread's timeline. An item keeps its place when a later event with the same [key]
 * replaces it.
 *
 * @property seq the `seq` of the event that first created the item.
 */
sealed interface TimelineItem {
    val key: String
    val seq: Long

    /** A chat bubble. A repeated message id replaces the bubble's content. */
    data class Message(
        override val key: String,
        override val seq: Long,
        val role: MessageRole,
        val blocks: List<ContentBlock>,
        val isStreaming: Boolean,
    ) : TimelineItem

    /** A collapsible tool-call card. [approval] is set while an approval for this call is open. */
    data class Tool(
        override val key: String,
        override val seq: Long,
        val kind: ToolCallKind,
        val inputSummary: String,
        val output: String?,
        val status: ToolCallStatus,
        val approval: ApprovalCard?,
    ) : TimelineItem

    /**
     * Anything else worth a line: a sign-in request, a preview, a file change, or an event kind this
     * build does not know, shown as plain text.
     *
     * @property url what tapping the notice opens, when it opens something.
     */
    data class Notice(
        override val key: String,
        override val seq: Long,
        val kind: NoticeKind,
        val text: String,
        val url: String?,
    ) : TimelineItem
}

/** What a [TimelineItem.Notice] reports. */
enum class NoticeKind {
    AUTH_REQUIRED,
    PREVIEW,
    FILE_CHANGED,
    UNKNOWN_EVENT,
}
