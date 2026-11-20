// An agent message chunk's messageId is marked unstable in the SDK. It is what tells two back-to-back
// messages apart, so it is read when the agent sends it.
@file:OptIn(UnstableApi::class)

package ai.tatawur.rafiq.bridge.agent.acp

import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.ApprovalOption
import ai.tatawur.rafiq.protocol.ContentBlock
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.MessageRole
import ai.tatawur.rafiq.protocol.ToolCallStatus
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.PlanEntryStatus
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path
import com.agentclientprotocol.model.ContentBlock as AcpContentBlock

/**
 * Maps one ACP session's traffic onto Rafiq events. Pure state, no I/O, so tests can drive it directly.
 *
 * What maps to what:
 * - `agent_message_chunk` streams an agent message, see [MessageStreams].
 * - `tool_call` and `tool_call_update` become [EventPayload.ToolCall], see [ToolCallTracker].
 * - `plan` becomes one agent message per thread with the id `plan:<thread id>`, a checklist that is
 *   sent again in full whenever the plan changes. A plan is how you see what the agent intends, so it
 *   is shown rather than dropped, and the stable id lets a reader replace the old checklist.
 * - `subagent_update` announces or patches a helper thread, see [ThreadRegistry].
 * - `session_message` and `session_message_chunk` on a child session become messages on the helper's
 *   thread. A message the helper received (its instructions from the parent) has the user role, since
 *   the parent is the helper's user. One the helper sent (its report) has the agent role. The root
 *   session's copies of the same exchange are dropped, so each one shows once: the helper's thread
 *   already has it, and the root's side carries the agent's internal hand-off notes.
 * - `user_message_chunk` is dropped. The bridge logs your prompt itself before it reaches the adapter,
 *   so an agent's echo of it would show twice.
 * - Thoughts, commands, modes, config, session info and usage are not part of the conversation the
 *   app shows, so they map to nothing.
 * - Anything else, or a known update that fails to decode, becomes a plain system message.
 *
 * Not thread-safe. It is owned by a [SessionActor], whose single consumer calls it in the agent's wire
 * order.
 */
internal class AcpEventMapper(
    rafiqSessionId: String,
    projectPath: Path,
    private val newId: () -> String,
) {
    val threads = ThreadRegistry(rafiqSessionId)
    private val tools = ToolCallTracker(projectPath)
    private val messages = MessageStreams(newId)

    /** What a permission request maps to, and the thread it belongs to. */
    data class Approval(
        val threadId: String,
        val events: List<EventPayload>,
    )

    /** Maps the `update` of a `session/update` notification that arrived on [acpSessionId]. */
    fun sessionUpdate(
        acpSessionId: String,
        update: JsonObject,
        notificationMeta: JsonElement?,
    ): List<EventPayload> {
        val out = mutableListOf<EventPayload>()
        val type = update.string("sessionUpdate")
        when {
            type in IGNORED_UPDATES -> {}

            type in SESSION_MESSAGES && acpSessionId == threads.rootAcpSessionId -> {}

            type == "subagent_update" -> {
                val threadId = threads.applySubagentUpdate(acpSessionId, update, out)
                val idle = (update["state"] as? JsonObject)?.string("state") == "idle"
                if (threadId != null && idle) messages.end(threadId, out)
            }

            else -> {
                mapOnThread(acpSessionId, type, update, notificationMeta, out)
            }
        }
        return out
    }

    private fun mapOnThread(
        acpSessionId: String,
        type: String?,
        update: JsonObject,
        notificationMeta: JsonElement?,
        out: MutableList<EventPayload>,
    ) {
        val threadId = threads.threadFor(acpSessionId, parentToolUseId(update, notificationMeta), tools::origin, out)
        try {
            mapThreadUpdate(acpSessionId, threadId, type, update, out)
        } catch (_: IllegalArgumentException) {
            // A known update with fields this build cannot read, usually from a newer agent.
            // SerializationException is an IllegalArgumentException, so this covers both.
            messages.end(threadId, out)
            out += plainMessage(newId(), threadId, "The agent sent a `$type` update that could not be read.")
        }
    }

    /**
     * A prompt was sent on [threadId]. Your text is not echoed: the bridge logs it before calling the
     * adapter. The thread is working from here until the turn ends.
     */
    fun promptSent(threadId: String): List<EventPayload> {
        val out = mutableListOf<EventPayload>()
        messages.end(threadId, out)
        out += EventPayload.StatusChanged(threadId = threadId, state = AgentState.WORKING)
        return out
    }

    /**
     * The turn on [threadId] is over.
     *
     * A turn that ends normally or is cancelled leaves the thread waiting on you, not done: a session
     * goes on for as long as you keep prompting it. Helpers launched in the background can still be
     * running, and the agent can follow up on the main thread on its own once they report back, so
     * those messages keep arriving after this. Each helper's own state comes from its thread.
     *
     * @param stopReason ACP's stop reason, or null when the turn failed.
     * @param failure why the turn failed, or null when it ended normally.
     */
    fun turnEnded(
        threadId: String,
        stopReason: String?,
        failure: String?,
    ): List<EventPayload> {
        val out = mutableListOf<EventPayload>()
        messages.endAll(out)
        if (threadId == MAIN_THREAD_ID) threads.finishGroupedAtTurnEnd(out)
        out +=
            if (failure != null) {
                EventPayload.StatusChanged(threadId = threadId, state = AgentState.ERROR, detail = failure)
            } else {
                EventPayload.StatusChanged(
                    threadId = threadId,
                    state = turnEndState(stopReason),
                    detail = stopReason?.takeIf { it != "end_turn" },
                )
            }
        return out
    }

    /**
     * Maps a `session/request_permission` request: the tool call it is about, the approval, and the
     * thread now waiting on you.
     */
    fun permissionRequested(
        acpSessionId: String,
        params: JsonObject,
        approvalId: String,
    ): Approval {
        val out = mutableListOf<EventPayload>()
        val rawToolCall = params["toolCall"] as? JsonObject ?: JsonObject(emptyMap())
        val toolCallId = rawToolCall.string("toolCallId") ?: approvalId
        val rafiqToolCallId = tools.rafiqId(acpSessionId, threads.rootAcpSessionId, toolCallId)
        val threadId =
            tools.origin(rafiqToolCallId)?.threadId
                ?: threads.threadFor(acpSessionId, parentToolUseId(rawToolCall, params["_meta"]), tools::origin, out)
        messages.end(threadId, out)
        val toolCall =
            decodeOrNull(SessionUpdate.ToolCallUpdate.serializer(), rawToolCall)
                ?.let { tools.updated(rafiqToolCallId, threadId, it) }
                ?: tools.updated(
                    rafiqToolCallId,
                    threadId,
                    SessionUpdate.ToolCallUpdate(toolCallId = ToolCallId(toolCallId)),
                )
        out += toolCall
        out +=
            EventPayload.ApprovalRequest(
                id = approvalId,
                toolCallId = rafiqToolCallId,
                description = toolCall.inputSummary,
                options = approvalOptions(params["options"]),
            )
        out += EventPayload.StatusChanged(threadId = threadId, state = AgentState.WAITING_ON_YOU)
        return Approval(threadId, out)
    }

    /** The agent process is gone. Ends the messages that were still streaming. */
    fun sessionEnded(): List<EventPayload> = mutableListOf<EventPayload>().also(messages::endAll)

    /** You chose an option, so the thread is working again. */
    fun approvalAnswered(threadId: String): List<EventPayload> =
        listOf(EventPayload.StatusChanged(threadId = threadId, state = AgentState.WORKING))

    private fun mapThreadUpdate(
        acpSessionId: String,
        threadId: String,
        type: String?,
        update: JsonObject,
        out: MutableList<EventPayload>,
    ) {
        when (type) {
            "agent_message_chunk" -> {
                val chunk = decode(SessionUpdate.AgentMessageChunk.serializer(), update)
                streamChunk(threadId, MessageRole.AGENT, "agent:${chunk.messageId?.value}", chunk.content, out)
            }

            "session_message_chunk" -> {
                val content = decode(AcpContentBlock.serializer(), update["content"] ?: JsonObject(emptyMap()))
                val role = sessionMessageRole(acpSessionId, update)
                streamChunk(threadId, role, "session:${update.string("messageId")}", content, out)
            }

            "session_message" -> {
                messages.end(threadId, out)
                val blocks =
                    (update["content"] as? JsonArray).orEmpty().map {
                        decode(
                            AcpContentBlock.serializer(),
                            it,
                        )
                    }
                val text = blocks.mapNotNull(::contentText).joinToString("")
                val id = update.string("messageId")?.let { "$threadId/$it" } ?: newId()
                out += agentMessage(id, threadId, text).copy(role = sessionMessageRole(acpSessionId, update))
            }

            "tool_call" -> {
                val call = decode(SessionUpdate.ToolCall.serializer(), update)
                messages.end(threadId, out)
                out +=
                    tools.started(
                        tools.rafiqId(acpSessionId, threads.rootAcpSessionId, call.toolCallId.value),
                        threadId,
                        call,
                    )
            }

            "tool_call_update" -> {
                toolCallUpdate(acpSessionId, threadId, decode(SessionUpdate.ToolCallUpdate.serializer(), update), out)
            }

            "plan" -> {
                messages.end(threadId, out)
                out += plan(threadId, decode(SessionUpdate.PlanUpdate.serializer(), update))
            }

            else -> {
                messages.end(threadId, out)
                out += plainMessage(newId(), threadId, unrecognised(type, update))
            }
        }
    }

    private fun streamChunk(
        threadId: String,
        role: MessageRole,
        streamKey: String,
        content: AcpContentBlock,
        out: MutableList<EventPayload>,
    ) {
        if (content !is AcpContentBlock.Image) {
            contentText(content)?.let { messages.append(threadId, role, streamKey, it, out) }
            return
        }
        messages.end(threadId, out)
        // An image is stored, replayed and sent to your phone inside an event, so a huge one is
        // replaced by a note rather than carried.
        out +=
            if (content.data.length > MAX_IMAGE_DATA_CHARS) {
                plainMessage(newId(), threadId, "The agent sent an image too large to show.")
            } else {
                val uri = "data:${content.mimeType};base64,${content.data}"
                EventPayload.Message(newId(), threadId, role, listOf(ContentBlock.Image(uri, content.mimeType)))
            }
    }

    private fun toolCallUpdate(
        acpSessionId: String,
        threadId: String,
        update: SessionUpdate.ToolCallUpdate,
        out: MutableList<EventPayload>,
    ) {
        messages.end(threadId, out)
        val id = tools.rafiqId(acpSessionId, threads.rootAcpSessionId, update.toolCallId.value)
        val call = tools.updated(id, tools.origin(id)?.threadId ?: threadId, update)
        out += call
        // A helper launched in the foreground ends with the tool call that launched it. One launched in
        // the background shows activity after this, which marks it working again.
        when (call.status) {
            ToolCallStatus.COMPLETED -> threads.finishGrouped(id, AgentState.DONE, out)
            ToolCallStatus.FAILED -> threads.finishGrouped(id, AgentState.ERROR, out)
            else -> Unit
        }
    }
}

private fun plan(
    threadId: String,
    plan: SessionUpdate.PlanUpdate,
): EventPayload.Message {
    val checklist =
        plan.entries.joinToString("\n") { entry ->
            when (entry.status) {
                PlanEntryStatus.COMPLETED -> "- [x] ${entry.content}"
                PlanEntryStatus.IN_PROGRESS -> "- [ ] ${entry.content} (in progress)"
                PlanEntryStatus.PENDING -> "- [ ] ${entry.content}"
            }
        }
    return agentMessage("plan:$threadId", threadId, checklist)
}

private fun agentMessage(
    id: String,
    threadId: String,
    markdown: String,
) = EventPayload.Message(
    id = id,
    threadId = threadId,
    role = MessageRole.AGENT,
    content = listOf(ContentBlock.Markdown(markdown)),
)

private fun plainMessage(
    id: String,
    threadId: String,
    text: String,
) = EventPayload.Message(
    id = id,
    threadId = threadId,
    role = MessageRole.SYSTEM,
    content = listOf(ContentBlock.Text(text)),
)

private fun <T> decode(
    strategy: DeserializationStrategy<T>,
    element: JsonElement,
): T = acpReadJson.decodeFromJsonElement(strategy, element)

private fun <T> decodeOrNull(
    strategy: DeserializationStrategy<T>,
    element: JsonElement,
): T? =
    try {
        decode(strategy, element)
    } catch (_: IllegalArgumentException) {
        null
    }

/**
 * Reads ACP JSON like the SDK does, plus `coerceInputValues`, so an enum value added to ACP after this
 * SDK (a new tool kind, say) reads as the field's default instead of failing the whole update.
 */
internal val acpReadJson: Json = Json(ACPJson) { coerceInputValues = true }

/**
 * A session-directed message the helper received is from its user, the parent. One it sent, or one
 * whose sender the agent did not report, is the helper's own.
 */
private fun sessionMessageRole(
    acpSessionId: String,
    update: JsonObject,
): MessageRole {
    val sender = update.string("senderSessionId")
    return if (sender != null && sender != acpSessionId) MessageRole.USER else MessageRole.AGENT
}

/** The helper tag that agents without the subagents capability put on a helper's updates. */
internal fun parentToolUseId(
    update: JsonObject,
    notificationMeta: JsonElement?,
): String? {
    fun from(meta: JsonElement?): String? =
        ((meta as? JsonObject)?.get("claudeCode") as? JsonObject)?.string("parentToolUseId")
    return from(update["_meta"]) ?: from(notificationMeta)
}

private fun approvalOptions(raw: JsonElement?): List<ApprovalOption> =
    (raw as? JsonArray).orEmpty().mapNotNull { item ->
        val option = item as? JsonObject ?: return@mapNotNull null
        val id = option.string("optionId") ?: return@mapNotNull null
        ApprovalOption(id = id, kind = approvalOptionKind(option.string("kind")), label = option.string("name") ?: id)
    }

/** Says what arrived, and keeps any text it carries so a new kind of update still reads as something. */
private fun unrecognised(
    type: String?,
    update: JsonObject,
): String {
    val text = (update["content"] as? JsonObject)?.string("text")
    val label = type?.let { "`$it`" } ?: "untyped"
    return if (text != null) text else "The agent sent an update this bridge does not show yet: $label."
}

/** About 1.5 MB of image as base64 text. */
private const val MAX_IMAGE_DATA_CHARS = 2 * 1024 * 1024

private val SESSION_MESSAGES = setOf("session_message", "session_message_chunk")

private val IGNORED_UPDATES =
    setOf(
        "agent_thought_chunk",
        "user_message_chunk",
        "available_commands_update",
        "current_mode_update",
        "config_option_update",
        "session_info_update",
        "usage_update",
    )
