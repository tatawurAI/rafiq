package ai.tatawur.rafiq.bridge.push

import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.Event
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.ThreadKind
import ai.tatawur.rafiq.protocol.api.PushKind

/** What one event means for pushes. */
sealed interface PushDecision {
    /** Push [kind] now. */
    data class Send(
        val kind: PushKind,
        val sessionId: String,
        val approvalId: String? = null,
    ) : PushDecision

    /** The main thread's turn ended. Push [PushKind.TURN_DONE] if it is still over after the debounce. */
    data class TurnEnded(
        val sessionId: String,
    ) : PushDecision

    /** Work started again, or an approval opened: drop a pending [TurnEnded] for the session. */
    data class TurnResumed(
        val sessionId: String,
    ) : PushDecision
}

/**
 * Turns the event log's live events into push decisions. Plain state, no I/O, one caller at a time.
 *
 * Only what this object saw happen counts. A push needs a change it watched: an approval it saw open, a thread it
 * saw working that then stopped or failed. Events a restart writes about the past (the errors and cancelled
 * approvals of `recoverAfterRestart`, a tmux session settling) arrive with no earlier state to change from, so they
 * never push.
 */
class PushTriggers {
    private val sessions = HashMap<String, SessionState>()

    /** Tells the triggers which thread is [sessionId]'s main thread, for a session that began before they did. */
    fun knowMainThread(
        sessionId: String,
        threadId: String,
    ) {
        state(sessionId).mainThreadId = threadId
    }

    /** True when the triggers know nothing of [sessionId] yet, so its main thread should be looked up first. */
    fun isNew(sessionId: String): Boolean = sessionId !in sessions

    /** The decisions [event] leads to, in order. */
    fun onEvent(event: Event): List<PushDecision> {
        val session = state(event.sessionId)
        return when (val payload = event.payload) {
            is EventPayload.ApprovalRequest -> {
                session.openApprovals += payload.id
                listOf(
                    PushDecision.TurnResumed(event.sessionId),
                    PushDecision.Send(PushKind.APPROVAL, event.sessionId, payload.id),
                )
            }

            is EventPayload.ApprovalResolved -> {
                if (session.openApprovals.remove(payload.id)) {
                    listOf(PushDecision.Send(PushKind.APPROVAL_CLEARED, event.sessionId, payload.id))
                } else {
                    emptyList()
                }
            }

            is EventPayload.StatusChanged -> {
                stateChanged(event.sessionId, session, payload.threadId ?: SESSION_KEY, payload.state)
            }

            is EventPayload.ThreadUpdated -> {
                if (payload.thread.kind == ThreadKind.MAIN) session.mainThreadId = payload.thread.id
                stateChanged(event.sessionId, session, payload.thread.id, payload.thread.status)
            }

            is EventPayload.SessionUpdated -> {
                stateChanged(event.sessionId, session, SESSION_KEY, payload.session.status)
            }

            else -> {
                emptyList()
            }
        }
    }

    /** True while a [PushDecision.TurnEnded] for [sessionId] still holds: the main thread stopped and nothing waits. */
    fun turnStillOver(sessionId: String): Boolean {
        val session = sessions[sessionId] ?: return false
        val main = session.mainThreadId?.let(session.states::get)
        return main in TURN_OVER && session.openApprovals.isEmpty()
    }

    private fun stateChanged(
        sessionId: String,
        session: SessionState,
        key: String,
        state: AgentState,
    ): List<PushDecision> {
        val before = session.states.put(key, state)
        if (before == state) return emptyList()
        val decisions = mutableListOf<PushDecision>()
        if (state == AgentState.WORKING) {
            // A new turn: a later error deserves its own push.
            session.errorPushed = false
            if (key == session.mainThreadId) decisions += PushDecision.TurnResumed(sessionId)
        }
        // One error push per session until work starts again, though the thread and the session both report it.
        if (state == AgentState.ERROR && before != null && !session.errorPushed) {
            session.errorPushed = true
            decisions += PushDecision.Send(PushKind.ERROR, sessionId)
        }
        val turnEnded = key == session.mainThreadId && before == AgentState.WORKING && state in TURN_OVER
        if (turnEnded && session.openApprovals.isEmpty()) decisions += PushDecision.TurnEnded(sessionId)
        return decisions
    }

    private fun state(sessionId: String): SessionState = sessions.getOrPut(sessionId) { SessionState() }

    private class SessionState {
        var mainThreadId: String? = null

        /** The last state seen live, per thread id, and under [SESSION_KEY] for the session itself. */
        val states = HashMap<String, AgentState>()
        val openApprovals = LinkedHashSet<String>()
        var errorPushed = false
    }

    private companion object {
        /** Thread ids are the agent's, so the session's own state is kept under a key no thread id can be. */
        const val SESSION_KEY = "\u0000session"

        /** A turn that ended normally leaves the main thread on your move, or done. */
        val TURN_OVER = setOf(AgentState.WAITING_ON_YOU, AgentState.DONE)
    }
}
