package ai.tatawur.rafiq.bridge.sessions

import ai.tatawur.rafiq.bridge.agent.AgentAdapter
import ai.tatawur.rafiq.bridge.agent.AgentSession
import ai.tatawur.rafiq.bridge.agent.AgentUnavailableException
import ai.tatawur.rafiq.bridge.store.EventLog
import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.AgentThread
import ai.tatawur.rafiq.protocol.ContentBlock
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.MessageRole
import ai.tatawur.rafiq.protocol.Session
import ai.tatawur.rafiq.protocol.ThreadKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Owns the sessions on your server: starts them through the right adapter, writes everything an agent
 * reports into the [EventLog], and routes your prompts, cancels and approval answers back.
 *
 * A session is live while its adapter process runs. After a bridge restart, or after the process dies,
 * the session is not live but stays in the log, and the next prompt resumes it through
 * [AgentAdapter.resume].
 *
 * @param scope owns the work that collects each live session's updates. Cancel it to stop them.
 */
@Suppress("TooManyFunctions") // One operation per route plus the helpers they share; splitting would scatter one flow.
class SessionManager(
    private val log: EventLog,
    adapters: List<AgentAdapter>,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val adaptersById = adapters.associateBy(AgentAdapter::agentId)
    private val live = ConcurrentHashMap<String, LiveSession>()
    private val resumeLocks = ConcurrentHashMap<String, Mutex>()
    private val approvalSessions = ConcurrentHashMap<String, String>()

    /** The agent ids a session can be created with. */
    val agentIds: List<String> get() = adaptersById.keys.sorted()

    /**
     * Starts a session for [agent] in [projectPath] and returns it.
     *
     * @throws SessionException.Invalid when the agent is not configured or the folder does not exist.
     * @throws SessionException.Conflict when the agent cannot start.
     */
    suspend fun create(
        agent: String,
        projectPath: String,
        title: String?,
    ): Session {
        val adapter =
            adaptersById[agent]
                ?: throw SessionException.Invalid("unknown_agent", "No agent named '$agent' is configured.")
        val directory = existingDirectory(projectPath)
        val sessionId = "ses_" + UUID.randomUUID().toString().replace("-", "")
        val agentSession = unavailableAsConflict { adapter.start(sessionId, directory) }
        // Collection starts now, because the agent's updates are hot: anything it reports while the
        // rows below are written waits in the buffer and is logged after them.
        val liveSession = attach(sessionId, agentSession)
        val now = clock.millis()
        val session =
            Session(
                id = sessionId,
                agent = agent,
                projectPath = directory.toString(),
                title = title?.takeIf { it.isNotBlank() } ?: directory.fileName?.toString() ?: "Session",
                status = AgentState.DONE,
                createdAtMs = now,
                updatedAtMs = now,
            )
        @Suppress("TooGenericExceptionCaught") // Rethrown after the half-started session is cleaned up.
        try {
            log.createSession(session, agentSession.agentSessionRef)
            log.append(sessionId, EventPayload.SessionUpdated(session))
            val mainThread =
                AgentThread(
                    id = agentSession.mainThreadId,
                    sessionId = sessionId,
                    kind = ThreadKind.MAIN,
                    title = session.title,
                    status = AgentState.DONE,
                )
            log.append(sessionId, EventPayload.ThreadUpdated(mainThread))
        } catch (failure: Throwable) {
            live.remove(sessionId)
            liveSession.job.cancel()
            agentSession.close()
            throw failure
        }
        liveSession.job.start()
        return session
    }

    /** Every session, most recently updated first. */
    suspend fun sessions(): List<Session> = log.sessions()

    /** @throws SessionException.NotFound when there is no such session. */
    suspend fun threads(sessionId: String): List<AgentThread> {
        requireSession(sessionId)
        return log.threads(sessionId)
    }

    /**
     * Sends [text] to the session's main thread. [threadId] may name it, and any other thread is
     * refused. The message is logged as yours, on that thread, before the agent sees it. The adapter
     * must not echo it. A session that is not live is resumed first.
     *
     * @throws SessionException.NotFound when the session or thread does not exist.
     * @throws SessionException.Invalid when the thread is not the main thread.
     * @throws SessionException.Conflict when the agent cannot resume the session.
     */
    suspend fun prompt(
        sessionId: String,
        threadId: String?,
        text: String,
    ) {
        val target = resolvePromptTarget(sessionId, threadId, text)
        val agentSession = liveSession(sessionId).agentSession
        log.append(
            sessionId,
            EventPayload.Message(
                id = "user:" + UUID.randomUUID(),
                threadId = target.id,
                role = MessageRole.USER,
                content = listOf(ContentBlock.Text(text)),
            ),
        )
        try {
            agentSession.prompt(target.id, text)
        } catch (refused: IllegalArgumentException) {
            throw SessionException.Invalid("invalid_thread", "That thread does not accept prompts.", refused)
        }
    }

    /**
     * Stops the current turn on [threadId].
     *
     * @throws SessionException.NotFound when the session or thread does not exist.
     * @throws SessionException.Conflict when the session is not running, so there is no turn to stop.
     */
    suspend fun cancel(
        sessionId: String,
        threadId: String,
    ) {
        resolveThread(sessionId, threadId)
        val agentSession =
            live[sessionId]?.agentSession
                ?: throw SessionException.Conflict("session_not_running", "The session is not running.")
        try {
            agentSession.cancel(threadId)
        } catch (unsupported: UnsupportedOperationException) {
            throw SessionException.Conflict("not_supported", "That thread cannot be cancelled on its own.", unsupported)
        }
    }

    /**
     * Answers a pending approval request with one of its options.
     *
     * @throws SessionException.NotFound when no pending request has this id.
     * @throws SessionException.Conflict when the session is no longer running.
     */
    suspend fun respondToApproval(
        approvalId: String,
        optionId: String,
    ) {
        val sessionId =
            approvalSessions[approvalId]
                ?: throw SessionException.NotFound("approval_not_found", "No pending approval '$approvalId'.")
        val agentSession =
            live[sessionId]?.agentSession
                ?: throw SessionException.Conflict("session_not_running", "The session is not running.")
        agentSession.respondToApproval(approvalId, optionId)
        approvalSessions.remove(approvalId)
    }

    /**
     * Run once at start-up. Agent processes do not survive a bridge restart, so any session or thread
     * still marked as working or waiting becomes errored, and says why in its log.
     */
    suspend fun recoverAfterRestart() {
        for (session in log.sessions()) {
            settleRunningWork(session, AgentState.ERROR, "Interrupted by a bridge restart. Send a message to resume.")
        }
    }

    /** Stops every live session. Their logs stay, and they resume on the next prompt after a restart. */
    suspend fun shutdown() {
        val sessions = live.values.toList()
        live.clear()
        sessions.forEach {
            it.job.cancel()
            it.agentSession.close()
        }
    }

    private class LiveSession(
        val agentSession: AgentSession,
        val job: Job,
    )

    /**
     * Starts collecting [agentSession]'s updates into a buffer straight away, and returns the live
     * session whose [LiveSession.job] logs them. The job starts only when the caller says so.
     */
    private fun attach(
        sessionId: String,
        agentSession: AgentSession,
    ): LiveSession {
        val buffer = Channel<EventPayload>(Channel.UNLIMITED)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            agentSession.updates
                .catch { buffer.close(it) }
                .onCompletion { buffer.close() }
                .collect { buffer.trySend(it) }
        }
        lateinit var liveSession: LiveSession
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                val failure = drain(sessionId, buffer)
                if (live.remove(sessionId, liveSession)) markDied(sessionId, failure)
            }
        liveSession = LiveSession(agentSession, job)
        live[sessionId] = liveSession
        return liveSession
    }

    /** Logs everything in [buffer] until it closes. Returns why it stopped early, or null if the agent just ended. */
    @Suppress("TooGenericExceptionCaught") // Any failure here means the session is dead and must say so.
    private suspend fun drain(
        sessionId: String,
        buffer: Channel<EventPayload>,
    ): Throwable? =
        try {
            for (payload in buffer) record(sessionId, payload)
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failure
        }

    private suspend fun record(
        sessionId: String,
        payload: EventPayload,
    ) {
        log.append(sessionId, payload)
        if (payload is EventPayload.ApprovalRequest) approvalSessions[payload.id] = sessionId
    }

    private suspend fun markDied(
        sessionId: String,
        failure: Throwable?,
    ) {
        val stored = log.session(sessionId) ?: return
        val detail =
            if (failure ==
                null
            ) {
                "The agent process exited."
            } else {
                "The agent process failed: ${failure.message}"
            }
        settleRunningWork(stored.session, AgentState.ERROR, detail, alsoSession = true)
    }

    /** Sets the session, when [alsoSession] or it is running, and each running thread, to [state]. */
    private suspend fun settleRunningWork(
        session: Session,
        state: AgentState,
        detail: String,
        alsoSession: Boolean = false,
    ) {
        if (alsoSession || session.status.isRunning()) {
            record(session.id, EventPayload.StatusChanged(threadId = null, state = state, detail = detail))
        }
        for (thread in log.threads(session.id).filter { it.status.isRunning() }) {
            record(session.id, EventPayload.StatusChanged(threadId = thread.id, state = state, detail = detail))
        }
    }

    private suspend fun liveSession(sessionId: String): LiveSession {
        live[sessionId]?.let { return it }
        val stored = requireSession(sessionId)
        val lock = resumeLocks.computeIfAbsent(sessionId) { Mutex() }
        return lock.withLock {
            live[sessionId] ?: resume(stored.session, stored.agentSessionRef)
        }
    }

    private suspend fun resume(
        session: Session,
        agentSessionRef: String,
    ): LiveSession {
        val adapter =
            adaptersById[session.agent]
                ?: throw SessionException.Conflict("agent_unavailable", "Agent '${session.agent}' is not configured.")
        val agentSession =
            unavailableAsConflict { adapter.resume(session.id, Path.of(session.projectPath), agentSessionRef) }
        val liveSession = attach(session.id, agentSession)
        if (agentSession.agentSessionRef != agentSessionRef) {
            log.updateAgentSessionRef(session.id, agentSession.agentSessionRef)
        }
        record(session.id, EventPayload.StatusChanged(threadId = null, state = AgentState.DONE, detail = "Resumed."))
        liveSession.job.start()
        return liveSession
    }

    private suspend fun requireSession(sessionId: String) =
        log.session(sessionId) ?: throw SessionException.NotFound("session_not_found", "No session '$sessionId'.")

    /** Checks everything a prompt can be refused for before anything is logged. */
    private suspend fun resolvePromptTarget(
        sessionId: String,
        threadId: String?,
        text: String,
    ): AgentThread {
        if (text.isBlank()) throw SessionException.Invalid("empty_prompt", "The prompt text is empty.")
        val target = resolveThread(sessionId, threadId)
        if (target.kind != ThreadKind.MAIN) {
            throw SessionException.Invalid("invalid_thread", "Prompts go to the main thread, not a helper thread.")
        }
        return target
    }

    private suspend fun resolveThread(
        sessionId: String,
        threadId: String?,
    ): AgentThread {
        requireSession(sessionId)
        val threads = log.threads(sessionId)
        if (threadId == null) {
            return threads.firstOrNull { it.kind == ThreadKind.MAIN }
                ?: throw SessionException.NotFound("thread_not_found", "The session has no main thread.")
        }
        return threads.firstOrNull { it.id == threadId }
            ?: throw SessionException.NotFound("thread_not_found", "No thread '$threadId' in session '$sessionId'.")
    }

    private suspend fun <T> unavailableAsConflict(block: suspend () -> T): T =
        try {
            block()
        } catch (unavailable: AgentUnavailableException) {
            throw SessionException.Conflict(
                "agent_unavailable",
                unavailable.message ?: "The agent is unavailable.",
                unavailable,
            )
        }

    private fun existingDirectory(raw: String): Path {
        val path =
            try {
                Path.of(raw)
            } catch (invalid: InvalidPathException) {
                throw SessionException.Invalid("invalid_project_path", "project_path is not a valid path.", invalid)
            }
        if (!path.isAbsolute || !Files.isDirectory(path)) {
            throw SessionException.Invalid(
                "invalid_project_path",
                "project_path must be an absolute path to an existing folder.",
            )
        }
        return path.toRealPath()
    }
}

private fun AgentState.isRunning(): Boolean = this == AgentState.WORKING || this == AgentState.WAITING_ON_YOU
