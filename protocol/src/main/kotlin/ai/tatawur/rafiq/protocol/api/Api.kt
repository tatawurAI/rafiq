package ai.tatawur.rafiq.protocol.api

import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.Session
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Body of `POST /sessions`. */
@Serializable
data class CreateSessionRequest(
    @SerialName("agent") val agent: String,
    @SerialName("project_path") val projectPath: String,
    @SerialName("title") val title: String? = null,
)

/**
 * Body of `POST /sessions/{id}/prompt`. A null [threadId] means the session's main thread.
 *
 * @property clientId an id the app makes up for this prompt, so it can match later
 *   [ai.tatawur.rafiq.protocol.EventPayload.PromptDelivery] events to it. Null from an app that does not track
 *   delivery.
 */
@Serializable
data class PromptRequest(
    @SerialName("thread_id") val threadId: String? = null,
    @SerialName("text") val text: String,
    @SerialName("client_id") val clientId: String? = null,
)

/**
 * Error codes for a prompt the bridge will not type into a tmux pane. They come back as the
 * [ApiErrorDetail.code] of a 409 answer to `POST /sessions/{id}/prompt`, or as the code of a
 * [ai.tatawur.rafiq.protocol.EventPayload.PromptDelivery] that was not delivered. The message beside the code is
 * for a person.
 */
object SendRefusal {
    /** A permission dialog or a question is open at the desk. */
    const val AGENT_WAITING = "agent_waiting"

    /** There is half-typed text in the terminal's input box. */
    const val INPUT_NOT_EMPTY = "input_not_empty"

    /** The pane no longer runs the agent. */
    const val AGENT_NOT_RUNNING = "agent_not_running"

    /** The bridge cannot tell whether it is safe to type, so it does not. */
    const val STATE_UNKNOWN = "state_unknown"

    /** The project has no Rafiq hooks, so replies and delivery cannot be seen. */
    const val HOOKS_NOT_INSTALLED = "hooks_not_installed"

    /** Three messages are already waiting for the agent to be free. */
    const val QUEUE_FULL = "queue_full"

    /** The phone that sent it was revoked before the agent was free, so the waiting prompt was dropped. */
    const val DEVICE_REVOKED = "device_revoked"

    /** The prompt waited ten minutes for the agent to be free and was dropped. */
    const val QUEUE_EXPIRED = "queue_expired"

    /** The text was sent but no sign that it landed came back in time. */
    const val NOT_CONFIRMED = "not_confirmed"
}

/**
 * Body of `GET /sessions/{id}/hooks`: what installing the reader's hooks for the session's project would do.
 * Nothing is written by this call.
 *
 * @property projectPath the project folder, as an absolute path on your server.
 * @property targetFile the file that would be written, as an absolute path.
 * @property fileText the whole file exactly as it would be after the install, or after the remove when
 *   [installed] is true. Shown to you as is.
 * @property previewHash a hash of [fileText] and of the file as it is now. The install and remove calls send it
 *   back, and the bridge refuses if either changed since this preview.
 * @property events the hook events that would be registered.
 * @property sends what the hook program sends, and where, in plain words.
 * @property installed true when the bridge's hooks are already in the file.
 * @property gitExcludeOffered true when the project is a git repository whose `.git/info/exclude` does not list
 *   the file yet.
 */
@Serializable
data class HookSetupPreview(
    @SerialName("project_path") val projectPath: String,
    @SerialName("target_file") val targetFile: String,
    @SerialName("file_text") val fileText: String,
    @SerialName("preview_hash") val previewHash: String,
    @SerialName("events") val events: List<String> = emptyList(),
    @SerialName("sends") val sends: String,
    @SerialName("installed") val installed: Boolean = false,
    @SerialName("git_exclude_offered") val gitExcludeOffered: Boolean = false,
)

/**
 * Body of `POST /sessions/{id}/hooks` (install) and `DELETE /sessions/{id}/hooks` (remove). Sent only after you
 * tap to agree. A stale [previewHash] gets 409 with the code `hooks_file_changed`.
 *
 * @property addGitExclude also list the file in `.git/info/exclude`. Ignored by remove.
 */
@Serializable
data class HookSetupRequest(
    @SerialName("preview_hash") val previewHash: String,
    @SerialName("add_git_exclude") val addGitExclude: Boolean = false,
)

/**
 * Body of `GET /terminal/history?session=<id>&window=<index>&lines=<n>`: read-only scrollback of the pane the
 * phone is showing. The bridge caps `lines` at [MAX_HISTORY_LINES]. Reading it never changes the window, its
 * size or what the desk sees.
 *
 * @property text the lines, oldest first, joined with `\n`, with colour escapes kept.
 * @property lineCount the number of lines in [text].
 * @property cols the pane's width when it was read.
 * @property alternateScreen true when the pane's program is on the alternate screen, which has no history.
 *   The app then sends scroll keys to the program instead of showing an overlay.
 */
@Serializable
data class TerminalHistory(
    @SerialName("text") val text: String,
    @SerialName("line_count") val lineCount: Int,
    @SerialName("cols") val cols: Int,
    @SerialName("alternate_screen") val alternateScreen: Boolean = false,
)

/** The most lines `GET /terminal/history` returns. */
const val MAX_HISTORY_LINES = 5000

/** Body of `POST /approvals/{id}`. */
@Serializable
data class ApprovalAnswer(
    @SerialName("option_id") val optionId: String,
)

/** Every error response has this shape. Branch on [ApiErrorDetail.code], not on the message. */
@Serializable
data class ApiError(
    @SerialName("error") val error: ApiErrorDetail,
)

/**
 * @property code a stable machine-readable name, for example `session_not_found`.
 * @property message a sentence for a person.
 */
@Serializable
data class ApiErrorDetail(
    @SerialName("code") val code: String,
    @SerialName("message") val message: String,
)

/** Body of the 202 answer to a prompt, cancel or approval: the bridge has handed it to the agent. */
@Serializable
data class Accepted(
    @SerialName("status") val status: String = "accepted",
)

/** Body of `GET /health`. */
@Serializable
data class Health(
    @SerialName("status") val status: String,
    @SerialName("version") val version: String,
)

/**
 * One entry of `GET /sessions/summaries`: a session with what the session list shows, so the list
 * needs no socket per session.
 *
 * @property lastSeq the session's newest event `seq`, or 0 when the log is empty.
 * @property lastLine the start of the newest agent or user message text, for the row's preview line.
 * @property openApprovals the approvals still waiting on you, oldest first.
 */
@Serializable
data class SessionSummary(
    @SerialName("session") val session: Session,
    @SerialName("last_seq") val lastSeq: Long = 0,
    @SerialName("last_line") val lastLine: String? = null,
    @SerialName("open_approvals") val openApprovals: List<EventPayload.ApprovalRequest> = emptyList(),
)

/** One entry of `GET /agents`: an agent the bridge can start a session with. */
@Serializable
data class AgentInfo(
    @SerialName("id") val id: String,
)
