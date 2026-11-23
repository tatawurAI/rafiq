package ai.tatawur.rafiq.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a tool call does. The app picks an icon and, for edits, a diff view from it. */
@Serializable
enum class ToolCallKind {
    @SerialName("edit")
    EDIT,

    @SerialName("command")
    COMMAND,

    @SerialName("search")
    SEARCH,

    @SerialName("read")
    READ,

    @SerialName("fetch")
    FETCH,

    /** Anything else, including kinds added after this build. */
    @SerialName("other")
    OTHER,
}

/** Where a tool call is in its life. */
@Serializable
enum class ToolCallStatus {
    /** Proposed, and possibly waiting on an approval. */
    @SerialName("pending")
    PENDING,

    @SerialName("in_progress")
    IN_PROGRESS,

    @SerialName("completed")
    COMPLETED,

    @SerialName("failed")
    FAILED,

    @SerialName("unknown")
    UNKNOWN,
}

/**
 * One choice offered by an [EventPayload.ApprovalRequest].
 *
 * @property id what the app sends back to choose this option.
 * @property label the agent's own wording for the option.
 */
@Serializable
data class ApprovalOption(
    @SerialName("id") val id: String,
    @SerialName("kind") val kind: ApprovalOptionKind = ApprovalOptionKind.UNKNOWN,
    @SerialName("label") val label: String,
)

/** The effect of choosing an [ApprovalOption]. The app styles and orders buttons by it. */
@Serializable
enum class ApprovalOptionKind {
    @SerialName("allow_once")
    ALLOW_ONCE,

    @SerialName("allow_always")
    ALLOW_ALWAYS,

    @SerialName("deny")
    DENY,

    /** A kind this build does not know. The app shows the label and treats it as neither allow nor deny. */
    @SerialName("unknown")
    UNKNOWN,
}

/** How an [EventPayload.ApprovalResolved] approval went away. */
@Serializable
enum class ApprovalOutcome {
    /** You chose an option. */
    @SerialName("selected")
    SELECTED,

    /** The turn or thread was cancelled, or the session ended, before anyone chose. */
    @SerialName("cancelled")
    CANCELLED,

    /** The agent stopped waiting, for example because its turn ended. */
    @SerialName("withdrawn")
    WITHDRAWN,

    /**
     * Nobody answered from a phone in time, the hook went away, or too many approvals were waiting. The agent
     * shows its own dialog at the desk instead.
     */
    @SerialName("ask_at_desk")
    ASK_AT_DESK,

    @SerialName("unknown")
    UNKNOWN,
}

/**
 * The full text behind an [EventPayload.ApprovalRequest]. The agent controls this text, so the app never
 * shortens a multi-line command to its first line.
 *
 * @property text the command or input, up to the bridge's cap.
 * @property lineCount the number of lines in the whole text, before any cut.
 * @property truncated true when the bridge cut [text] at its cap. The app shows a marker where it was cut.
 * @property sessionRule the rule that the option of kind [ApprovalOptionKind.ALLOW_ALWAYS] adds, scoped to this
 *   session only. Null when no such option is offered.
 */
@Serializable
data class ApprovalDetail(
    @SerialName("text") val text: String,
    @SerialName("line_count") val lineCount: Int,
    @SerialName("truncated") val truncated: Boolean = false,
    @SerialName("session_rule") val sessionRule: String? = null,
)

/** What a turn is doing right now, for [EventPayload.TurnProgress]. */
@Serializable
enum class TurnPhase {
    /** Busy, with no step in flight and no recent text. */
    @SerialName("working")
    WORKING,

    /** Running a tool step. */
    @SerialName("running_step")
    RUNNING_STEP,

    /** The agent is writing a reply. */
    @SerialName("writing")
    WRITING,

    /** The agent is waiting on you, for example at an approval. */
    @SerialName("waiting")
    WAITING,

    /** No turn is running. */
    @SerialName("idle")
    IDLE,

    @SerialName("unknown")
    UNKNOWN,
}

/** Where a prompt is on its way to the agent, for [EventPayload.PromptDelivery]. */
@Serializable
enum class DeliveryState {
    /** The agent is busy. The bridge holds the prompt and types it when the agent is ready. */
    @SerialName("queued")
    QUEUED,

    /** The agent received it. */
    @SerialName("delivered")
    DELIVERED,

    /** The bridge did not send it, or could not confirm that it landed. */
    @SerialName("not_delivered")
    NOT_DELIVERED,

    @SerialName("unknown")
    UNKNOWN,
}

/** How a provider sign-in works. */
@Serializable
enum class AuthKind {
    /** You open a URL on any device and enter a short code. */
    @SerialName("device_code")
    DEVICE_CODE,

    /** You sign in through the app's browser, and the app relays the final localhost redirect to the bridge. */
    @SerialName("redirect")
    REDIRECT,

    @SerialName("unknown")
    UNKNOWN,
}

/** How a file changed. */
@Serializable
enum class FileChangeKind {
    @SerialName("added")
    ADDED,

    @SerialName("modified")
    MODIFIED,

    @SerialName("deleted")
    DELETED,

    @SerialName("renamed")
    RENAMED,

    @SerialName("unknown")
    UNKNOWN,
}
