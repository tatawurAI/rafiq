package ai.tatawur.rafiq.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What an [Event] reports. On the wire it is a JSON object whose `type` field names the kind.
 *
 * Every kind this build knows is a [Known] subtype. Anything it cannot decode becomes [Unknown], so a
 * newer bridge never crashes an older app. New kinds are added, never renamed or removed, without an ADR.
 */
@Serializable(with = EventPayloadSerializer::class)
sealed interface EventPayload {
    /** The payload kinds this build understands, each with a stable wire name. */
    @Serializable
    sealed interface Known : EventPayload

    /**
     * A payload this build cannot decode: its type is unknown, absent or not a string, or it is a
     * known type with a missing or malformed field. The app shows it as a plain message.
     *
     * @property raw the JSON object exactly as received, including its `type` field. It is what
     *   gets re-encoded, so storing and forwarding the payload loses nothing.
     */
    data class Unknown(
        val raw: JsonObject,
    ) : EventPayload {
        /** The `type` field as received, or null when it was absent or not a string. */
        val type: String?
            get() = (raw["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    /** A session was created or one of its fields changed. Carries the whole session. */
    @Serializable
    @SerialName("session_updated")
    data class SessionUpdated(
        @SerialName("session") val session: Session,
    ) : Known

    /** A thread was created or one of its fields changed. Carries the whole thread. */
    @Serializable
    @SerialName("thread_updated")
    data class ThreadUpdated(
        @SerialName("thread") val thread: AgentThread,
    ) : Known

    /**
     * A complete message, or the first snapshot of one that is still streaming.
     *
     * The same [id] can arrive more than once, and the later one replaces the earlier. A streaming
     * message starts with [isStreaming] set, grows by [MessageDelta]s, and ends with this payload sent
     * again under the same id with the whole content and [isStreaming] cleared. A thread's plan is a
     * message with the id `plan:<thread id>`, sent again in full whenever the plan changes.
     */
    @Serializable
    @SerialName("message")
    data class Message(
        @SerialName("id") val id: String,
        @SerialName("thread_id") val threadId: String,
        @SerialName("role") val role: MessageRole = MessageRole.UNKNOWN,
        @SerialName("content") val content: List<ContentBlock> = emptyList(),
        @SerialName("streaming") val isStreaming: Boolean = false,
    ) : Known

    /**
     * Text appended to a streaming message. The app concatenates deltas in [Event.seq] order onto the
     * message's first snapshot, until the final [Message] with the same id replaces the result.
     */
    @Serializable
    @SerialName("message_delta")
    data class MessageDelta(
        @SerialName("message_id") val messageId: String,
        @SerialName("thread_id") val threadId: String,
        @SerialName("text") val text: String,
    ) : Known

    /**
     * An action the agent took or wants to take, such as editing a file or running a command.
     * The same [id] is sent again as the call's status and output change.
     */
    @Serializable
    @SerialName("tool_call")
    data class ToolCall(
        @SerialName("id") val id: String,
        @SerialName("thread_id") val threadId: String,
        @SerialName("kind") val kind: ToolCallKind = ToolCallKind.OTHER,
        @SerialName("input_summary") val inputSummary: String,
        @SerialName("output") val output: String? = null,
        @SerialName("status") val status: ToolCallStatus = ToolCallStatus.UNKNOWN,
    ) : Known

    /**
     * The agent is waiting for you to allow or deny a tool call. The app answers with one
     * option's [ApprovalOption.id].
     *
     * @property expiresAtMs when the request lapses, in milliseconds since the Unix epoch, or null if it does not.
     * @property detail the full text you are approving and the rule "allow for this session" adds. Null from an
     *   agent that gives neither, in which case the app shows [description].
     */
    @Serializable
    @SerialName("approval_request")
    data class ApprovalRequest(
        @SerialName("id") val id: String,
        @SerialName("tool_call_id") val toolCallId: String,
        @SerialName("description") val description: String,
        @SerialName("options") val options: List<ApprovalOption>,
        @SerialName("expires_at_ms") val expiresAtMs: Long? = null,
        @SerialName("detail") val detail: ApprovalDetail? = null,
    ) : Known

    /**
     * An approval is no longer waiting on you. The app removes its card when this arrives, whoever
     * answered it: you on this phone, you on another device, a cancel, or the agent. The bridge sends
     * exactly one per [ApprovalRequest].
     *
     * @property id the [ApprovalRequest.id] it resolves.
     * @property optionId the option chosen, set when [outcome] is [ApprovalOutcome.SELECTED] and null otherwise.
     */
    @Serializable
    @SerialName("approval_resolved")
    data class ApprovalResolved(
        @SerialName("id") val id: String,
        @SerialName("outcome") val outcome: ApprovalOutcome = ApprovalOutcome.UNKNOWN,
        @SerialName("option_id") val optionId: String? = null,
    ) : Known

    /**
     * A session or thread changed state. This drives badges, colours and notifications.
     *
     * @property threadId the thread that changed, or null when the change is for the whole session.
     */
    @Serializable
    @SerialName("status_changed")
    data class StatusChanged(
        @SerialName("thread_id") val threadId: String? = null,
        @SerialName("state") val state: AgentState = AgentState.UNKNOWN,
        @SerialName("detail") val detail: String? = null,
    ) : Known

    /**
     * The agent needs you to sign in to a provider before it can continue.
     *
     * @property code the code to enter, for a device-code flow.
     * @property expiresAtMs when the sign-in lapses, in milliseconds since the Unix epoch.
     */
    @Serializable
    @SerialName("auth_required")
    data class AuthRequired(
        @SerialName("id") val id: String,
        @SerialName("provider") val provider: String,
        @SerialName("kind") val kind: AuthKind = AuthKind.UNKNOWN,
        @SerialName("url") val url: String,
        @SerialName("code") val code: String? = null,
        @SerialName("expires_at_ms") val expiresAtMs: Long? = null,
    ) : Known

    /** A process in the session started listening on a port that your phone can open. */
    @Serializable
    @SerialName("preview_available")
    data class PreviewAvailable(
        @SerialName("id") val id: String,
        @SerialName("url") val url: String,
        @SerialName("label") val label: String,
        @SerialName("port") val port: Int,
    ) : Known

    /**
     * A file in the session's project changed.
     *
     * @property path relative to the session's project root.
     * @property diffRef an opaque reference the app passes back to fetch the diff.
     * @property gitStatus the two-letter `git status --porcelain` code, when the project is a git repository.
     */
    @Serializable
    @SerialName("file_changed")
    data class FileChanged(
        @SerialName("path") val path: String,
        @SerialName("change_type") val changeType: FileChangeKind = FileChangeKind.UNKNOWN,
        @SerialName("diff_ref") val diffRef: String? = null,
        @SerialName("git_status") val gitStatus: String? = null,
    ) : Known

    /**
     * What a thread's current turn is doing, for the progress line under the last message. Sent when any
     * field changes. A turn that ends sends [TurnPhase.IDLE].
     *
     * @property currentStep a short label for the tool step in flight, or null when none is.
     * @property startedAtMs when the turn started, in milliseconds since the Unix epoch, so the app can show
     *   the elapsed time without a tick from the bridge. Null when no turn is running.
     */
    @Serializable
    @SerialName("turn_progress")
    data class TurnProgress(
        @SerialName("thread_id") val threadId: String,
        @SerialName("phase") val phase: TurnPhase = TurnPhase.UNKNOWN,
        @SerialName("current_step") val currentStep: String? = null,
        @SerialName("started_at_ms") val startedAtMs: Long? = null,
    ) : Known

    /**
     * What happened to a prompt you sent, after the bridge accepted it. Matched to the prompt by [clientId],
     * the id the app put in its [ai.tatawur.rafiq.protocol.api.PromptRequest]. Sent again as the state moves on.
     *
     * @property code a stable name for why it was not delivered, one of
     *   [ai.tatawur.rafiq.protocol.api.SendRefusal]. Null unless [state] is [DeliveryState.NOT_DELIVERED].
     * @property message the reason in plain words, for the composer.
     */
    @Serializable
    @SerialName("prompt_delivery")
    data class PromptDelivery(
        @SerialName("client_id") val clientId: String,
        @SerialName("thread_id") val threadId: String,
        @SerialName("state") val state: DeliveryState = DeliveryState.UNKNOWN,
        @SerialName("code") val code: String? = null,
        @SerialName("message") val message: String? = null,
    ) : Known

    /**
     * The bridge found that Chat may be missing part of a turn, for example because the joined reply does not
     * match the agent's own record. The app shows a note that points to Terminal.
     */
    @Serializable
    @SerialName("chat_incomplete")
    data class ChatIncomplete(
        @SerialName("thread_id") val threadId: String,
        @SerialName("reason") val reason: String,
    ) : Known
}

internal object EventPayloadSerializer :
    FallbackSerializer<EventPayload, EventPayload.Known>(
        serialName = "ai.tatawur.rafiq.protocol.EventPayload",
        knownSerializer = EventPayload.Known.serializer(),
    ) {
    override fun asKnown(value: EventPayload): EventPayload.Known? = value as? EventPayload.Known

    override fun unknown(raw: JsonObject): EventPayload = EventPayload.Unknown(raw)

    override fun rawOf(value: EventPayload): JsonObject =
        when (value) {
            is EventPayload.Unknown -> value.raw
            is EventPayload.Known -> error("${value::class.simpleName} is a known payload.")
        }
}
