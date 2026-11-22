package ai.tatawur.rafiq.bridge.tmux.reader

import ai.tatawur.rafiq.bridge.store.EventLog
import ai.tatawur.rafiq.bridge.tmux.PaneInfo
import ai.tatawur.rafiq.protocol.AgentThread
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.Session

/*
 * What the Claude reader needs from the rest of the bridge and from the machine, as small interfaces, so its
 * tests run on virtual time without SQLite, tmux or Claude Code.
 */

/** Where the reader writes the events of a tmux session, and how it finds the session the tmux source made. */
interface ReaderLog {
    suspend fun append(
        sessionId: String,
        payload: EventPayload,
    )

    /** The session as stored, or null before the tmux source has listed it. */
    suspend fun session(sessionId: String): Session?

    suspend fun threads(sessionId: String): List<AgentThread>
}

/** [ReaderLog] on the bridge's event log. */
class EventLogReaderLog(
    private val log: EventLog,
) : ReaderLog {
    override suspend fun append(
        sessionId: String,
        payload: EventPayload,
    ) {
        log.append(sessionId, payload)
    }

    override suspend fun session(sessionId: String): Session? = log.session(sessionId)?.session

    override suspend fun threads(sessionId: String): List<AgentThread> = log.threads(sessionId)
}

/** One live Claude Code process, as the agent's own session list reports it. */
data class AgentStatus(
    val pid: Long,
    /** [BUSY], [WAITING] or [IDLE], or what a newer agent says instead. */
    val status: String,
    /** What a waiting agent waits for, in the agent's words, such as `permission prompt`. */
    val waitingFor: String? = null,
    val sessionId: String? = null,
    val cwd: String? = null,
) {
    companion object {
        const val BUSY = "busy"
        const val WAITING = "waiting"
        const val IDLE = "idle"
    }
}

/** The agent's own status for each live process. */
fun interface AgentStatusSource {
    /** Every live agent by pid, or null when the agent could not be asked. */
    suspend fun statuses(): Map<Long, AgentStatus>?
}

/** The few tmux and process operations the reader and the send guard use on one tmux server. */
interface PaneIo {
    /** Every pane on the server. Empty when no server runs. */
    suspend fun panes(): List<PaneInfo>

    /** The pids of the agent processes running under each pane in [panes], by pane id. */
    suspend fun agentPids(panes: List<PaneInfo>): Map<String, List<Long>>

    /** The visible text of [paneId], or null when it cannot be read. The only pane text the reader reads. */
    suspend fun capture(paneId: String): String?

    /** Types [text] into [paneId] as one bracketed paste. False when tmux refused. */
    suspend fun paste(
        paneId: String,
        text: String,
    ): Boolean

    /** Presses Enter once in [paneId]. False when tmux refused. */
    suspend fun pressEnter(paneId: String): Boolean

    /** True while [paneId] exists and [agentPid] is alive and runs inside it. */
    suspend fun runsAgent(
        paneId: String,
        agentPid: Long,
    ): Boolean
}
