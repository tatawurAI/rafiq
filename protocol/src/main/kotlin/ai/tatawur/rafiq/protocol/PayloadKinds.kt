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
