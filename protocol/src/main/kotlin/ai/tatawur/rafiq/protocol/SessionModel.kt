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
 * @property terminal true when the session runs in a tmux pane on your server.
 * @property terminalAllowed false when the session is listed but the bridge will not open it in Terminal, so the
 *   app hides the Terminal toggle. Only meaningful when [terminal] is true.
 * @property permissionMode the agent's own permission mode, as the agent names it, or null when not known.
 *   The app shows it as a label. For a session the bridge started over ACP it is the id of the current mode,
 *   one of [availableModes].
 * @property availableModes the modes the agent offers for this session, as the agent advertised them. Empty when
 *   the agent offers none or the mode cannot be changed from the app, for example in a tmux session. The app
 *   shows only these, so nothing is hard-coded per agent.
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
    @SerialName("terminal") val terminal: Boolean = false,
    @SerialName("terminal_allowed") val terminalAllowed: Boolean = true,
    @SerialName("permission_mode") val permissionMode: String? = null,
    @SerialName("available_modes") val availableModes: List<SessionMode> = emptyList(),
)

/**
 * One permission mode an agent offers, for example one that asks before every edit.
 *
 * @property id what the app sends back to choose it.
 * @property name the agent's own short name for it.
 * @property description the agent's own explanation, or null.
 */
@Serializable
data class SessionMode(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("description") val description: String? = null,
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
