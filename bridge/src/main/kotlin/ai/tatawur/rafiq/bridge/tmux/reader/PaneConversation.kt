package ai.tatawur.rafiq.bridge.tmux.reader

import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.ContentBlock
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.MessageRole
import ai.tatawur.rafiq.protocol.ToolCallStatus
import ai.tatawur.rafiq.protocol.TurnPhase
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import java.util.UUID

/**
 * One Claude Code pane's conversation, turned into Rafiq events on one thread. Not thread-safe: the session
 * that owns it calls it from one coroutine at a time.
 *
 * Order within a turn: hooks run side by side, so the text an agent writes before a step can reach the bridge
 * just after the step's `PreToolUse`. A step's card is therefore held back for a moment ([held]) and text that
 * arrives meanwhile is written first. A card goes out when its window ends, when its result arrives, when an
 * approval for it is raised, or when the turn ends.
 */
@Suppress("TooManyFunctions") // One handler per hook event, kept together so the mapping reads top to bottom.
internal class PaneConversation(
    val paneId: String,
    var threadId: String,
    private val out: suspend (EventPayload) -> Unit,
) {
    var claudeSessionId: String? = null
    var agentPid: Long? = null
    var recordPath: Path? = null

    /** The byte offset in [recordPath] up to which the record has been read. Null before the first read. */
    var recordOffset: Long? = null
    var permissionMode: String? = null
    var lastHookAtMs: Long = 0

    /** The agent's own status from its session list, as last seen. */
    var status: AgentStatus? = null

    /** When the running turn started, or null between turns. */
    var turnStartedAtMs: Long? = null
        private set

    val inTurn: Boolean get() = turnStartedAtMs != null

    /** The text of a turn that ended with a `Stop`, kept until its reply is checked. See [StopCheck]. */
    var stopCheck: StopCheck? = null

    private val streaming = LinkedHashMap<String, StringBuilder>()
    private var lastFinalText: String? = null
    private var lastWriteAtMs: Long? = null
    private val openTools = LinkedHashMap<String, OpenTool>()
    private val held = LinkedHashSet<String>()
    private val shownTexts = ArrayDeque<String>()
    private val shownTools = ArrayDeque<String>()
    private val shownPrompts = ArrayDeque<String>()
    private var lastProgress: EventPayload.TurnProgress? = null
    private var lastState: Pair<AgentState, String?>? = null

    private class OpenTool(
        val name: String,
        val input: JsonObject,
        val agentType: String?,
    )

    /** What [StopCheck] compares when the wait after a `Stop` is over. */
    class StopCheck(
        val lastAssistantMessage: String,
        val generation: Int,
    )

    private var stopGeneration = 0

    fun startTurn(atMs: Long) {
        if (inTurn) return
        turnStartedAtMs = atMs
        lastFinalText = null
        lastWriteAtMs = null
    }

    /** Your prompt as a message. An id already written is not written again, so a backfill never doubles it. */
    suspend fun userMessage(
        text: String,
        id: String = "user:" + UUID.randomUUID(),
    ) {
        if (id in shownPrompts) return
        remember(shownPrompts, id)
        out(EventPayload.Message(id, threadId, MessageRole.USER, listOf(ContentBlock.Text(text))))
    }

    /**
     * The stable id of a prompt's message: `user:<agent session id>:<prompt id>`. The hook and the record carry
     * the same prompt id, so the live message and a backfilled one are the same message.
     */
    fun promptMessageId(promptId: String?): String? = promptId?.let { "user:${claudeSessionId.orEmpty()}:$it" }

    /** One `MessageDisplay` batch. The message ends with the batch marked final, whose delta may be empty. */
    suspend fun onDisplay(
        messageId: String,
        delta: String,
        final: Boolean,
        atMs: Long,
    ) {
        if (!inTurn && stopCheck == null) startTurn(atMs)
        lastWriteAtMs = atMs
        val id = "claude:${claudeSessionId.orEmpty()}:$messageId"
        val soFar = streaming[messageId]
        when {
            soFar == null && final -> {
                finishMessage(id, delta)
            }

            soFar == null -> {
                streaming[messageId] = StringBuilder(delta)
                out(agentMessage(id, delta, isStreaming = true))
            }

            else -> {
                soFar.append(delta)
                if (final) {
                    streaming.remove(messageId)
                    finishMessage(id, soFar.toString())
                } else if (delta.isNotEmpty()) {
                    out(EventPayload.MessageDelta(messageId = id, threadId = threadId, text = delta))
                }
            }
        }
    }

    private suspend fun finishMessage(
        id: String,
        text: String,
    ) {
        lastFinalText = text
        remember(shownTexts, normalize(text))
        out(agentMessage(id, text, isStreaming = false))
    }

    /** A step started. Its card is held until [flush] lets it out. */
    fun onPreTool(
        toolUseId: String,
        name: String,
        input: JsonObject,
        agentType: String?,
    ) {
        openTools[toolUseId] = OpenTool(name, input, agentType)
        held += toolUseId
    }

    /** Writes the held cards in order, up to and including [upTo], or all of them when it is null. */
    suspend fun flush(upTo: String? = null) {
        if (upTo != null && upTo !in held) return
        val iterator = held.iterator()
        while (iterator.hasNext()) {
            val id = iterator.next()
            iterator.remove()
            openTools[id]?.let { tool ->
                out(card(id, tool.name, tool.input, tool.agentType, ToolCallStatus.IN_PROGRESS, null))
            }
            if (id == upTo) return
        }
    }

    /** The latest step still running with this name and input, for tying an approval to its card. */
    fun matchOpenTool(
        name: String,
        input: JsonObject,
    ): String? = openTools.entries.lastOrNull { it.value.name == name && it.value.input == input }?.key

    /** A card for a request that matched no step, so the approval has a card to sit on. */
    suspend fun pendingCard(
        id: String,
        name: String,
        input: JsonObject,
    ) {
        out(card(id, name, input, null, ToolCallStatus.PENDING, null))
    }

    /** A step finished, or failed. A step the bridge never saw start gets its card from this event alone. */
    suspend fun onPostTool(
        toolUseId: String,
        name: String,
        input: JsonObject,
        agentType: String?,
        output: String?,
        failed: Boolean,
    ) {
        flush(toolUseId)
        val tool = openTools.remove(toolUseId)
        val status = if (failed) ToolCallStatus.FAILED else ToolCallStatus.COMPLETED
        out(card(toolUseId, tool?.name ?: name, tool?.input ?: input, tool?.agentType ?: agentType, status, output))
        remember(shownTools, toolUseId)
    }

    /**
     * The batch that closes a group of steps. A step that a hook or the desk refused reports no result of its
     * own, and its refusal text is only here. Returns the ids of those refused steps.
     */
    suspend fun onBatch(calls: JsonArray): List<String> {
        val refused = calls.mapNotNull { it as? JsonObject }.filter { it.str("tool_use_id") in openTools }
        for (call in refused) {
            val text = (call["tool_response"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val input = call.obj("tool_input") ?: EMPTY_OBJECT
            onPostTool(call.str("tool_use_id").orEmpty(), call.str("tool_name").orEmpty(), input, null, text, true)
        }
        return refused.mapNotNull { it.str("tool_use_id") }
    }

    /** A subagent finished. Its last text is its reply, shown under its type. */
    suspend fun onSubagentStop(
        agentType: String?,
        text: String,
    ) {
        if (text.isBlank()) return
        val label = agentType?.takeIf(String::isNotBlank) ?: "Subagent"
        out(agentMessage("claude:sub:" + UUID.randomUUID(), "**$label**\n\n$text", isStreaming = false))
    }

    /**
     * The turn ended. Every held card goes out, and a message whose final batch never came is written whole as
     * far as it got. [lastAssistantMessage] is kept for the reply check when the turn ended with a `Stop`.
     *
     * @return the generation of the stop check to schedule, or null when there is nothing to check.
     */
    suspend fun endTurn(lastAssistantMessage: String?): Int? {
        flush()
        // A step still open when the turn ends was interrupted. It must not show as the next turn's step.
        openTools.clear()
        for ((messageId, text) in streaming) {
            finishMessage("claude:${claudeSessionId.orEmpty()}:$messageId", text.toString())
        }
        streaming.clear()
        turnStartedAtMs = null
        if (lastAssistantMessage.isNullOrBlank()) return null
        stopCheck = StopCheck(lastAssistantMessage, ++stopGeneration)
        return stopGeneration
    }

    /** Whether the last reply shown matches the agent's own final text. */
    fun replyMatches(lastAssistantMessage: String): Boolean =
        normalize(lastFinalText.orEmpty()) == normalize(lastAssistantMessage) ||
            normalize(lastAssistantMessage) in shownTexts

    /** Whether this text was already shown, so a backfill does not repeat it. */
    fun wasShown(text: String): Boolean = normalize(text) in shownTexts

    /** Writes a reply taken from somewhere other than the live batches: the record, or the `Stop` event. */
    suspend fun fillReply(text: String) {
        if (wasShown(text)) return
        finishMessage("claude:fill:" + UUID.randomUUID(), text)
    }

    /**
     * Writes what the record holds that Chat has not shown yet: prompts, replies and finished steps. Steps
     * whose card is already out and texts already shown are skipped.
     */
    suspend fun backfill(entries: List<RecordEntry>) {
        val uses = entries.filterIsInstance<RecordEntry.ToolUse>().associateBy(RecordEntry.ToolUse::id)
        for (entry in entries) {
            when (entry) {
                is RecordEntry.UserPrompt -> {
                    val id = promptMessageId(entry.promptId)
                    if (id != null) {
                        userMessage(entry.text, id)
                    } else if (!inTurn) {
                        userMessage(entry.text)
                    }
                }

                is RecordEntry.AssistantText -> {
                    fillReply(entry.text)
                }

                is RecordEntry.ToolUse -> {
                    Unit
                }

                is RecordEntry.ToolResult -> {
                    backfillStep(entry, uses[entry.toolUseId])
                }
            }
        }
    }

    private suspend fun backfillStep(
        result: RecordEntry.ToolResult,
        use: RecordEntry.ToolUse?,
    ) {
        if (use == null || result.toolUseId in shownTools || result.toolUseId in openTools) return
        val status = if (result.isError) ToolCallStatus.FAILED else ToolCallStatus.COMPLETED
        out(card(use.id, use.name, use.input, null, status, result.text))
        remember(shownTools, use.id)
    }

    /** The thread's state for the badge. Written only when it changes. */
    suspend fun state(
        state: AgentState,
        detail: String?,
    ) {
        if (lastState == state to detail) return
        lastState = state to detail
        out(EventPayload.StatusChanged(threadId = threadId, state = state, detail = detail))
    }

    /** The progress line. Written only when it changes. */
    suspend fun progress(
        approvalOpen: Boolean,
        nowMs: Long,
    ) {
        val started = turnStartedAtMs
        val step =
            openTools.entries
                .lastOrNull()
                ?.value
                ?.let { summarise(it.name, it.input, it.agentType) }
        val writing = lastWriteAtMs?.let { nowMs - it < WRITING_MS } == true
        val phase =
            when {
                started == null -> TurnPhase.IDLE
                approvalOpen || status?.status == AgentStatus.WAITING -> TurnPhase.WAITING
                step != null -> TurnPhase.RUNNING_STEP
                writing -> TurnPhase.WRITING
                else -> TurnPhase.WORKING
            }
        val progress =
            EventPayload.TurnProgress(
                threadId = threadId,
                phase = phase,
                currentStep = step.takeIf { started != null },
                startedAtMs = started,
            )
        if (progress == lastProgress) return
        lastProgress = progress
        out(progress)
    }

    private fun card(
        id: String,
        name: String,
        input: JsonObject,
        agentType: String?,
        status: ToolCallStatus,
        output: String?,
    ) = EventPayload.ToolCall(
        id = id,
        threadId = threadId,
        kind = toolKind(name),
        inputSummary = summarise(name, input, agentType),
        output = output,
        status = status,
    )

    private fun agentMessage(
        id: String,
        text: String,
        isStreaming: Boolean,
    ) = EventPayload.Message(
        id = id,
        threadId = threadId,
        role = MessageRole.AGENT,
        // Agents write CommonMark, as in the ACP adapter.
        content = listOf(ContentBlock.Markdown(text)),
        isStreaming = isStreaming,
    )

    private fun remember(
        list: ArrayDeque<String>,
        value: String,
    ) {
        list.addLast(value)
        while (list.size > REMEMBERED) list.removeFirst()
    }

    companion object {
        /** A batch this recent counts as the agent writing. */
        const val WRITING_MS = 2_000L
        private const val REMEMBERED = 200

        /** Texts compare equal when they differ only in surrounding or repeated whitespace. */
        fun normalize(text: String): String = text.trim().replace(Regex("\\s+"), " ")

        /** The output of a finished step, from its hook event. */
        fun outputOf(
            name: String,
            event: JsonObject,
            failed: Boolean,
        ): String? =
            if (failed) {
                event.str("error")?.take(TOOL_OUTPUT_CHARS)
            } else {
                describeResult(name, event["tool_response"] as JsonElement?)
            }
    }
}
