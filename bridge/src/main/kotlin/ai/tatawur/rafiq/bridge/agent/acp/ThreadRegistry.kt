package ai.tatawur.rafiq.bridge.agent.acp

import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.AgentThread
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.ThreadKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Knows which Rafiq thread each piece of ACP traffic belongs to.
 *
 * A session has one main thread, which is the ACP root session. Helper threads come from two places:
 * - A child session announced with `subagent_update`. Its thread id is the child's ACP session id,
 *   which the spec keeps unique on the connection and stable across repeated delegations.
 * - An agent without the subagents capability tags a helper's updates on the root session with
 *   `_meta.claudeCode.parentToolUseId`. Those become a thread keyed by that tool-use id.
 *
 * Every new helper is announced with [EventPayload.ThreadUpdated] before the event that needs it,
 * which is what the [ai.tatawur.rafiq.bridge.agent.AgentSession] contract requires.
 */
internal class ThreadRegistry(
    private val rafiqSessionId: String,
) {
    /** The ACP root session id, once `session/new`, `session/load` or `session/resume` has answered. */
    var rootAcpSessionId: String? = null

    private val helpers = mutableMapOf<String, AgentThread>()
    private val threadByChildSession = mutableMapOf<String, String>()
    private val childSessionByThread = mutableMapOf<String, String>()
    private val cancellable = mutableSetOf<String>()

    /** Where a thread's turns go on the ACP side. */
    sealed interface Target {
        /** The main thread: the root session takes prompts and cancels. */
        data class Root(
            val acpSessionId: String,
        ) : Target

        /** A child session. Clients may never prompt one, and may cancel it only when it allows that. */
        data class Child(
            val acpSessionId: String,
            val canCancel: Boolean,
        ) : Target

        /** A helper grouped from `_meta` tags. It has no ACP session of its own. */
        data object Grouped : Target
    }

    /** Returns where [threadId]'s turns go, or null when the thread is unknown. */
    fun target(threadId: String): Target? {
        val root = rootAcpSessionId
        val child = childSessionByThread[threadId]
        return when {
            threadId == MAIN_THREAD_ID && root != null -> Target.Root(root)
            child != null -> Target.Child(child, threadId in cancellable)
            threadId in helpers -> Target.Grouped
            else -> null
        }
    }

    /**
     * The thread for an update that arrived on [acpSessionId].
     *
     * @param parentToolUseId the `_meta.claudeCode.parentToolUseId` tag, or null when there is none.
     * @param parentToolCall looks up the tool call that started a grouped helper, for its thread and title.
     */
    fun threadFor(
        acpSessionId: String,
        parentToolUseId: String?,
        parentToolCall: (String) -> ToolCallTracker.Origin?,
        out: MutableList<EventPayload>,
    ): String =
        when {
            acpSessionId != rootAcpSessionId -> {
                threadByChildSession[acpSessionId] ?: announceUnannounced(acpSessionId, out)
            }

            parentToolUseId == null -> {
                MAIN_THREAD_ID
            }

            else -> {
                groupedThread(parentToolUseId, parentToolCall, out)
            }
        }

    private fun groupedThread(
        toolUseId: String,
        parentToolCall: (String) -> ToolCallTracker.Origin?,
        out: MutableList<EventPayload>,
    ): String {
        val existing = helpers[toolUseId]
        if (existing != null) {
            // A helper launched in the background keeps working after its launching tool call completed.
            if (existing.status != AgentState.WORKING) put(existing.copy(status = AgentState.WORKING), out)
        } else {
            val origin = parentToolCall(toolUseId)
            val thread =
                AgentThread(
                    id = toolUseId,
                    sessionId = rafiqSessionId,
                    parentThreadId = origin?.threadId ?: MAIN_THREAD_ID,
                    kind = ThreadKind.SUBAGENT,
                    title = origin?.title ?: FALLBACK_TITLE,
                    status = AgentState.WORKING,
                )
            put(thread, out)
        }
        return toolUseId
    }

    /**
     * Applies a `subagent_update` received on [parentAcpSessionId]. Every field except `sessionId` is a
     * patch: absent leaves the stored value, null clears it.
     */
    fun applySubagentUpdate(
        parentAcpSessionId: String,
        update: JsonObject,
        out: MutableList<EventPayload>,
    ): String? {
        val childSessionId = update.string("sessionId") ?: return null
        val threadId = threadByChildSession[childSessionId] ?: childSessionId
        val parentThreadId =
            if (parentAcpSessionId == rootAcpSessionId) MAIN_THREAD_ID else threadByChildSession[parentAcpSessionId]
        val previous =
            helpers[threadId] ?: AgentThread(
                id = threadId,
                sessionId = rafiqSessionId,
                parentThreadId = parentThreadId ?: MAIN_THREAD_ID,
                kind = ThreadKind.SUBAGENT,
                title = FALLBACK_TITLE,
                status = AgentState.UNKNOWN,
            )
        threadByChildSession[childSessionId] = threadId
        childSessionByThread[threadId] = childSessionId
        if ("capabilities" in update) {
            val cancel = (update["capabilities"] as? JsonObject)?.get("cancel")
            if (cancel is JsonObject) cancellable += threadId else cancellable -= threadId
        }
        val next =
            previous.copy(
                title = patchedTitle(update, previous.title),
                status = if ("state" in update) subagentState(update["state"]) else previous.status,
            )
        if (next != helpers[threadId]) put(next, out)
        return threadId
    }

    /**
     * Marks every grouped helper that is still working as done. Without child sessions a helper's work
     * shows only inside the turn that started it, so the end of the turn is the end of its work.
     */
    fun finishGroupedAtTurnEnd(out: MutableList<EventPayload>) {
        helpers.values
            .filter { it.id !in childSessionByThread && it.status == AgentState.WORKING }
            .forEach { put(it.copy(status = AgentState.DONE), out) }
    }

    /** Marks a grouped helper finished when the tool call that started it ends. */
    fun finishGrouped(
        toolUseId: String,
        state: AgentState,
        out: MutableList<EventPayload>,
    ) {
        val thread = helpers[toolUseId]?.takeIf { it.id !in childSessionByThread } ?: return
        if (thread.status != state) put(thread.copy(status = state), out)
    }

    private fun announceUnannounced(
        acpSessionId: String,
        out: MutableList<EventPayload>,
    ): String {
        // The spec makes the agent announce a child first. If one slips through, show its work under
        // the main thread's helper list rather than mixing it into the main conversation.
        val thread =
            AgentThread(
                id = acpSessionId,
                sessionId = rafiqSessionId,
                parentThreadId = MAIN_THREAD_ID,
                kind = ThreadKind.SUBAGENT,
                title = FALLBACK_TITLE,
                status = AgentState.UNKNOWN,
            )
        threadByChildSession[acpSessionId] = acpSessionId
        childSessionByThread[acpSessionId] = acpSessionId
        put(thread, out)
        return acpSessionId
    }

    private fun put(
        thread: AgentThread,
        out: MutableList<EventPayload>,
    ) {
        helpers[thread.id] = thread
        out += EventPayload.ThreadUpdated(thread)
    }

    private fun patchedTitle(
        update: JsonObject,
        current: String,
    ): String {
        // The title names the helper. Without one, its description is the next best label.
        val title = update["title"]
        val description = update.string("description")
        return when {
            title == null -> if (current == FALLBACK_TITLE) description ?: current else current
            title is JsonPrimitive && title.isString -> title.content
            else -> description ?: FALLBACK_TITLE
        }
    }
}

/** The id of every session's main thread. Helpers use agent-side ids, which never take this value. */
internal const val MAIN_THREAD_ID = "main"

/** The title of a helper the agent did not name. */
internal const val FALLBACK_TITLE = "Helper agent"
