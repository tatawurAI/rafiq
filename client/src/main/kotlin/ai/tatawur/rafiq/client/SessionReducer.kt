@file:Suppress("TooManyFunctions") // One small step per event kind, each kept next to the rule it applies.

package ai.tatawur.rafiq.client

import ai.tatawur.rafiq.protocol.AgentThread
import ai.tatawur.rafiq.protocol.ContentBlock
import ai.tatawur.rafiq.protocol.Event
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.FileChangeKind
import ai.tatawur.rafiq.protocol.MessageRole
import ai.tatawur.rafiq.protocol.ThreadKind

/** What happened when one event met a [SessionScreenState]. */
sealed interface Reduction {
    /** The state after the event. Unchanged unless the event was [Applied]. */
    val state: SessionScreenState

    /** The event was the next one in the log and is now part of [state]. */
    data class Applied(
        override val state: SessionScreenState,
    ) : Reduction

    /**
     * The event was already applied (`seq` at or below [SessionScreenState.lastSeq]) or belongs to
     * another session. Replays overlap, so this is normal.
     */
    data class Ignored(
        override val state: SessionScreenState,
    ) : Reduction

    /**
     * The event skips ahead of the next expected `seq`, so events are missing. Nothing was applied. The
     * caller reconnects with `after = state.lastSeq` and the bridge replays what was missed.
     */
    data class Gap(
        override val state: SessionScreenState,
        val receivedSeq: Long,
    ) : Reduction
}

/**
 * Folds a session's events into what the session view shows. Pure and total: it never throws, and an
 * event it cannot place becomes a notice or changes nothing.
 *
 * Events must arrive in `seq` order. A replay that repeats events changes nothing, and a gap is reported
 * as [Reduction.Gap] instead of being applied, so the state never holds a hole.
 */
object SessionReducer {
    /** Applies [event] to [state] if it is the next one for that session. */
    fun reduce(
        state: SessionScreenState,
        event: Event,
    ): Reduction =
        when {
            event.sessionId != state.sessionId || event.seq <= state.lastSeq -> Reduction.Ignored(state)
            event.seq > state.lastSeq + 1 -> Reduction.Gap(state, event.seq)
            else -> Reduction.Applied(state.apply(event).copy(lastSeq = event.seq))
        }

    /** Builds a session's state from its stored events, oldest first, stopping at the first gap. */
    fun fold(
        sessionId: String,
        events: Iterable<Event>,
    ): SessionScreenState = events.fold(SessionScreenState(sessionId)) { state, event -> reduce(state, event).state }
}

// Where items that belong on the main thread wait until the main thread's own event arrives. The
// bridge sends the main thread right after the session, so this is only for odd logs.
private const val NO_THREAD = ""

private fun SessionScreenState.apply(event: Event): SessionScreenState =
    when (val payload = event.payload) {
        is EventPayload.SessionUpdated -> copy(session = payload.session)
        is EventPayload.ThreadUpdated -> upsertThread(payload.thread)
        is EventPayload.Message -> upsertMessage(payload, event.seq)
        is EventPayload.MessageDelta -> appendDelta(payload, event.seq)
        is EventPayload.ToolCall -> upsertTool(payload, event.seq)
        is EventPayload.ApprovalRequest -> openApproval(payload)
        is EventPayload.ApprovalResolved -> resolveApproval(payload.id)
        is EventPayload.StatusChanged -> changeStatus(payload)
        else -> putNotice(noticeFor(payload, event.seq))
    }

/**
 * The notice line for an event that is not part of a conversation. [TimelineItem.Notice.text] carries
 * only the event's own words (provider and code, label, path, or type), and the screen adds the
 * sentence around them in the user's language.
 */
private fun noticeFor(
    payload: EventPayload,
    seq: Long,
): TimelineItem.Notice =
    when (payload) {
        is EventPayload.AuthRequired -> {
            TimelineItem.Notice(
                "auth:${payload.id}",
                seq,
                NoticeKind.AUTH_REQUIRED,
                payload.code?.let { "${payload.provider}: $it" } ?: payload.provider,
                payload.url,
            )
        }

        is EventPayload.PreviewAvailable -> {
            TimelineItem.Notice("preview:${payload.id}", seq, NoticeKind.PREVIEW, payload.label, payload.url)
        }

        is EventPayload.FileChanged -> {
            TimelineItem.Notice("file:$seq", seq, NoticeKind.FILE_CHANGED, fileText(payload), null)
        }

        is EventPayload.Unknown -> {
            TimelineItem.Notice("event:$seq", seq, NoticeKind.UNKNOWN_EVENT, payload.type ?: "unknown", null)
        }

        // The conversation kinds never get here. Should one be added without a case, show its kind.
        is EventPayload.Known -> {
            TimelineItem.Notice("event:$seq", seq, NoticeKind.UNKNOWN_EVENT, payload::class.simpleName.orEmpty(), null)
        }
    }

private fun fileText(payload: EventPayload.FileChanged): String =
    if (payload.changeType == FileChangeKind.UNKNOWN) {
        payload.path
    } else {
        "${payload.path} (${payload.changeType.name.lowercase()})"
    }

private fun SessionScreenState.upsertThread(thread: AgentThread): SessionScreenState {
    val tab =
        ThreadTab(
            id = thread.id,
            parentThreadId = thread.parentThreadId,
            kind = thread.kind,
            title = thread.title,
            status = thread.status,
            openApprovals = openApprovals.count { it.threadId == thread.id },
        )
    val index = threads.indexOfFirst { it.id == thread.id }
    val updated = if (index < 0) threads + tab else threads.replaceAt(index, tab)
    // sortedBy is stable, so the other threads keep the order they first appeared in.
    val next = copy(threads = updated.sortedBy { if (it.kind == ThreadKind.MAIN) 0 else 1 })
    return if (thread.kind == ThreadKind.MAIN) next.adoptOrphans(thread.id).mirrorMain(thread.id) else next
}

/** Moves items that arrived before the main thread onto it, in `seq` order. */
private fun SessionScreenState.adoptOrphans(mainThreadId: String): SessionScreenState {
    val orphans = timelines[NO_THREAD] ?: return this
    val merged = (timelines[mainThreadId].orEmpty() + orphans).sortedBy { it.seq }
    return copy(timelines = timelines - NO_THREAD + (mainThreadId to merged))
}

private fun SessionScreenState.upsertMessage(
    payload: EventPayload.Message,
    seq: Long,
): SessionScreenState =
    updateTimeline(payload.threadId) { items ->
        val index = items.indexOfKey<TimelineItem.Message>(payload.id)
        val current = items.getOrNull(index) as? TimelineItem.Message
        if (current == null) {
            items + TimelineItem.Message(payload.id, seq, payload.role, payload.content, payload.isStreaming)
        } else {
            items.replaceAt(
                index,
                current.copy(role = payload.role, blocks = payload.content, isStreaming = payload.isStreaming),
            )
        }
    }

private fun SessionScreenState.appendDelta(
    payload: EventPayload.MessageDelta,
    seq: Long,
): SessionScreenState =
    updateTimeline(payload.threadId) { items ->
        val index = items.indexOfKey<TimelineItem.Message>(payload.messageId)
        val current = items.getOrNull(index) as? TimelineItem.Message
        when {
            // A delta without its first snapshot: show the text rather than drop it.
            current == null -> {
                items +
                    TimelineItem.Message(
                        payload.messageId,
                        seq,
                        MessageRole.AGENT,
                        listOf(ContentBlock.Markdown(payload.text)),
                        isStreaming = true,
                    )
            }

            // The final snapshot already replaced the streamed text, so a late delta would double it.
            !current.isStreaming -> {
                items
            }

            else -> {
                items.replaceAt(index, current.copy(blocks = current.blocks.appendText(payload.text)))
            }
        }
    }

private fun List<ContentBlock>.appendText(text: String): List<ContentBlock> {
    val index = indexOfLast { it is ContentBlock.Text || it is ContentBlock.Markdown }
    return when (val block = getOrNull(index)) {
        is ContentBlock.Text -> replaceAt(index, ContentBlock.Text(block.text + text))
        is ContentBlock.Markdown -> replaceAt(index, ContentBlock.Markdown(block.markdown + text))
        else -> this + ContentBlock.Markdown(text)
    }
}

private fun SessionScreenState.upsertTool(
    payload: EventPayload.ToolCall,
    seq: Long,
): SessionScreenState {
    // An approval can name a tool call this state has not seen yet. It learns its thread now.
    val waiting = openApprovals.map { if (it.toolCallId == payload.id) it.copy(threadId = payload.threadId) else it }
    val approval = waiting.lastOrNull { it.toolCallId == payload.id }
    return updateTimeline(payload.threadId) { items ->
        val index = items.indexOfKey<TimelineItem.Tool>(payload.id)
        val current = items.getOrNull(index) as? TimelineItem.Tool
        val tool =
            TimelineItem.Tool(
                key = payload.id,
                seq = current?.seq ?: seq,
                kind = payload.kind,
                inputSummary = payload.inputSummary,
                output = payload.output,
                status = payload.status,
                approval = approval,
            )
        if (current == null) items + tool else items.replaceAt(index, tool)
    }.withApprovals(waiting)
}

private fun SessionScreenState.openApproval(payload: EventPayload.ApprovalRequest): SessionScreenState {
    val threadId =
        timelines.entries
            .firstOrNull { (_, items) -> items.indexOfKey<TimelineItem.Tool>(payload.toolCallId) >= 0 }
            ?.key
    val card = payload.toCard(sessionId, threadId)
    val index = openApprovals.indexOfFirst { it.id == card.id }
    val approvals = if (index < 0) openApprovals + card else openApprovals.replaceAt(index, card)
    return mapTools { tool -> if (tool.key == card.toolCallId) tool.copy(approval = card) else tool }
        .withApprovals(approvals)
}

private fun SessionScreenState.resolveApproval(approvalId: String): SessionScreenState =
    mapTools { tool -> if (tool.approval?.id == approvalId) tool.copy(approval = null) else tool }
        .withApprovals(openApprovals.filterNot { it.id == approvalId })

private fun SessionScreenState.changeStatus(payload: EventPayload.StatusChanged): SessionScreenState {
    val threadId = payload.threadId ?: return copy(session = session?.copy(status = payload.state))
    return copy(threads = threads.map { if (it.id == threadId) it.copy(status = payload.state) else it })
        .mirrorMain(threadId)
}

/**
 * The session's status is its main thread's status, because real agents report status per thread only.
 * The bridge applies the same rule to the session row it serves in `GET /sessions/summaries`, so the
 * header, the list and the summaries agree.
 */
private fun SessionScreenState.mirrorMain(threadId: String): SessionScreenState {
    val main = mainThread?.takeIf { it.id == threadId } ?: return this
    return copy(session = session?.copy(status = main.status))
}

private fun SessionScreenState.putNotice(notice: TimelineItem.Notice): SessionScreenState =
    updateTimeline(mainThread?.id ?: NO_THREAD) { items ->
        val index = items.indexOfKey<TimelineItem.Notice>(notice.key)
        val current = items.getOrNull(index) as? TimelineItem.Notice
        // A notice sent again, such as a sign-in with a new code, keeps its place.
        if (current == null) items + notice else items.replaceAt(index, notice.copy(seq = current.seq))
    }

/** Sets the open approvals and keeps every tab's badge count in step with them. */
private fun SessionScreenState.withApprovals(approvals: List<ApprovalCard>): SessionScreenState =
    copy(
        openApprovals = approvals,
        threads = threads.map { tab -> tab.copy(openApprovals = approvals.count { it.threadId == tab.id }) },
    )

private fun SessionScreenState.updateTimeline(
    threadId: String,
    change: (List<TimelineItem>) -> List<TimelineItem>,
): SessionScreenState = copy(timelines = timelines + (threadId to change(timelines[threadId].orEmpty())))

private fun SessionScreenState.mapTools(change: (TimelineItem.Tool) -> TimelineItem.Tool): SessionScreenState =
    copy(
        timelines =
            timelines.mapValues { (_, items) ->
                items.map { if (it is TimelineItem.Tool) change(it) else it }
            },
    )

// Keys are only unique within one item type: a tool call and a message may share an id.
private inline fun <reified T : TimelineItem> List<TimelineItem>.indexOfKey(key: String): Int =
    indexOfFirst { it is T && it.key == key }

private fun <T> List<T>.replaceAt(
    index: Int,
    value: T,
): List<T> = toMutableList().also { it[index] = value }
