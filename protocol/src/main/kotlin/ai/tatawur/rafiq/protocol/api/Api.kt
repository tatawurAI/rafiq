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

/** Body of `POST /sessions/{id}/prompt`. A null [threadId] means the session's main thread. */
@Serializable
data class PromptRequest(
    @SerialName("thread_id") val threadId: String? = null,
    @SerialName("text") val text: String,
)

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
