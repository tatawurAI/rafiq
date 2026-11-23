// The seam between the bridge's server side and the agents it drives. An adapter turns one agent's
// own protocol into Rafiq events; everything above this file knows only sessions, threads and events.
package ai.tatawur.rafiq.bridge.agent

import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.SessionMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Path

/**
 * Starts and resumes sessions for one kind of agent.
 *
 * The bridge holds one adapter per configured agent and picks it by [agentId] when a session is
 * created. Adding an agent means adding an adapter; nothing outside this package changes.
 */
interface AgentAdapter {
    /** The id the bridge and the app use for this agent, for example `claude-code`. */
    val agentId: String

    /**
     * Starts a new agent session in [projectPath].
     *
     * @param sessionId the Rafiq session id the bridge has already assigned. The adapter keeps its own
     *   agent-side ids private and reports everything under this id.
     */
    suspend fun start(
        sessionId: String,
        projectPath: Path,
    ): AgentSession

    /**
     * Reopens a session the agent can still resume, for example after a bridge restart.
     *
     * @param agentSessionRef the value [AgentSession.agentSessionRef] returned when the session started.
     * @throws AgentUnavailableException when the agent can no longer resume it.
     */
    suspend fun resume(
        sessionId: String,
        projectPath: Path,
        agentSessionRef: String,
    ): AgentSession
}

/**
 * One live agent session.
 *
 * Thread ids are chosen by the adapter and are stable for the life of the session. The main
 * thread's id is [mainThreadId]; helper threads are announced with a
 * [EventPayload.ThreadUpdated] before any event that refers to them.
 */
interface AgentSession {
    val sessionId: String

    /** The agent's own handle for this session, stored by the bridge so [AgentAdapter.resume] can use it. */
    val agentSessionRef: String

    val mainThreadId: String

    /**
     * Everything the agent reports, in the order it happened, without `seq` or a timestamp: the event
     * log assigns those. The flow is hot, so the bridge collects it once, as soon as the session
     * exists, and keeps collecting until [close]. It completes when the agent process exits.
     */
    val updates: Flow<EventPayload>

    /**
     * Sends your message to [threadId]. Returns once the agent has accepted it, not when it finishes the turn.
     *
     * The bridge logs your message itself, so an adapter never emits a user message for a prompt on
     * [updates]. It reports the thread as working, then the agent's output.
     *
     * @throws TurnInProgressException when [threadId] has not finished its previous turn.
     */
    suspend fun prompt(
        threadId: String,
        text: String,
    )

    /** Stops the current turn on [threadId]. Cancelling a helper thread leaves the main thread running. */
    suspend fun cancel(threadId: String)

    /**
     * Answers a pending [EventPayload.ApprovalRequest].
     *
     * @param optionId the id of one of the request's options.
     */
    suspend fun respondToApproval(
        approvalId: String,
        optionId: String,
    )

    /**
     * The permission modes the agent offers for this session and the one it is in, as the agent advertised
     * them. It changes when [setMode] succeeds and when the agent switches mode on its own, for example
     * when it leaves a planning mode. An agent that offers no modes stays at [AgentModes.NONE].
     */
    val modes: StateFlow<AgentModes> get() = NO_MODES

    /**
     * Switches the session to [modeId], one of [modes]. Returns once the agent has accepted it.
     *
     * @throws IllegalArgumentException when [modeId] is not one the agent offers.
     * @throws AgentUnavailableException when the agent refuses or has gone away.
     */
    suspend fun setMode(modeId: String): Unit = throw IllegalArgumentException("This agent offers no modes.")

    /** Ends the session and stops the agent process. Safe to call more than once. */
    suspend fun close()
}

/**
 * An agent session's permission modes.
 *
 * @property currentModeId the mode the session is in, or null when the agent did not say.
 * @property available every mode the agent offers, in the agent's order. Empty when it offers none.
 */
data class AgentModes(
    val currentModeId: String?,
    val available: List<SessionMode>,
) {
    /** True when [modeId] is one of [available]. */
    fun offers(modeId: String): Boolean = available.any { it.id == modeId }

    companion object {
        val NONE = AgentModes(currentModeId = null, available = emptyList())
    }
}

private val NO_MODES: StateFlow<AgentModes> = MutableStateFlow(AgentModes.NONE).asStateFlow()

/** The agent cannot start or resume a session, for example because its adapter is not installed. */
class AgentUnavailableException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * [AgentSession.prompt] was called while [threadId] is still in a turn. Agents take one prompt per
 * turn, so the caller waits for the thread to stop working, or cancels it, and then prompts again.
 */
class TurnInProgressException(
    val threadId: String,
) : Exception("Thread $threadId is still working on the previous prompt.")
