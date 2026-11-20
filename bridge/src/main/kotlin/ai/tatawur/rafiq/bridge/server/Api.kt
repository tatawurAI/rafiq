package ai.tatawur.rafiq.bridge.server

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
