package ai.tatawur.rafiq.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One run of a coding agent in one project on your server. The session list shows one row per session.
 *
 * @property agent the id of the agent running the session, as the bridge names it. The app treats it as a label.
 * @property projectPath the project folder on your server, as an absolute path.
 * @property createdAtMs when the session started, in milliseconds since the Unix epoch.
 * @property updatedAtMs when any field last changed, in milliseconds since the Unix epoch.
 */
@Serializable
data class Session(
    @SerialName("id") val id: String,
    @SerialName("agent") val agent: String,
    @SerialName("project_path") val projectPath: String,
    @SerialName("title") val title: String,
    @SerialName("status") val status: AgentState = AgentState.UNKNOWN,
    @SerialName("created_at_ms") val createdAtMs: Long,
    @SerialName("updated_at_ms") val updatedAtMs: Long,
)

/**
 * A line of work inside a session: the main conversation, or one the agent started on its own.
 * The app shows each thread as a tab or drawer entry.
 *
 * @property parentThreadId the thread that started this one, or null for the main thread.
 */
@Serializable
data class AgentThread(
    @SerialName("id") val id: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("parent_thread_id") val parentThreadId: String? = null,
    @SerialName("kind") val kind: ThreadKind = ThreadKind.UNKNOWN,
    @SerialName("title") val title: String,
    @SerialName("status") val status: AgentState = AgentState.UNKNOWN,
)

/** What a session or thread is doing, from your point of view. Drives badges and notifications. */
@Serializable
enum class AgentState {
    @SerialName("working")
    WORKING,

    @SerialName("waiting_on_you")
    WAITING_ON_YOU,

    @SerialName("done")
    DONE,

    @SerialName("error")
    ERROR,

    /** A state this build does not know. Shown as neutral. */
    @SerialName("unknown")
    UNKNOWN,
}

/** How a thread came to exist. */
@Serializable
enum class ThreadKind {
    /** The conversation you started. Every session has exactly one. */
    @SerialName("main")
    MAIN,

    /** Delegated by the agent to a helper agent. */
    @SerialName("subagent")
    SUBAGENT,

    /** A parallel worker the agent runs alongside the main thread. */
    @SerialName("worker")
    WORKER,

    @SerialName("unknown")
    UNKNOWN,
}
