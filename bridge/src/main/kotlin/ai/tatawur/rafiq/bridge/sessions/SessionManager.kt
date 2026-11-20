package ai.tatawur.rafiq.bridge.sessions

import ai.tatawur.rafiq.bridge.agent.AgentAdapter
import ai.tatawur.rafiq.bridge.agent.AgentSession
import ai.tatawur.rafiq.bridge.agent.AgentUnavailableException
import ai.tatawur.rafiq.bridge.agent.TurnInProgressException
import ai.tatawur.rafiq.bridge.store.EventLog
import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.AgentThread
import ai.tatawur.rafiq.protocol.ApprovalOutcome
import ai.tatawur.rafiq.protocol.ContentBlock
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.MessageRole
import ai.tatawur.rafiq.protocol.Session
import ai.tatawur.rafiq.protocol.ThreadKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Clock
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * Owns the sessions on your server: starts them through the right adapter, writes everything an agent
 * reports into the [EventLog], and routes your prompts, cancels and approval answers back.
 *
 * A session is live while its adapter process runs. After a bridge restart, or after the process dies,
 * the session is not live but stays in the log, and the next prompt resumes it through
 * [AgentAdapter.resume].
 *
 * @param scope owns the work that collects each live session's updates. Cancel it to stop them.
 * @param ioDispatcher runs the file system checks on project folders.
 */
@Suppress("TooManyFunctions") // One operation per route plus the helpers they share; splitting would scatter one flow.
class SessionManager(
    private val log: EventLog,
    adapters: List<AgentAdapter>,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val adaptersById = adapters.associateBy(AgentAdapter::agentId)
    private val live = ConcurrentHashMap<String, LiveSession>()
    private val resumeLocks = ConcurrentHashMap<String, Mutex>()
    private val approvals = ConcurrentHashMap<String, PendingApproval>()

    /** The most recent approvals the agent reported resolved, so a repeat answer is "gone" and not "unknown". */
    private val resolvedApprovals =
        Collections.synchronizedSet(
            Collections.newSetFromMap(
                object : LinkedHashMap<String, Boolean>() {
                    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) =
                        size > MAX_RESOLVED_APPROVALS
                },
            ),
        )
    private val promptLocks = ConcurrentHashMap<String, Mutex>()

    /** Threads (as session id to thread id) that accepted a prompt and have not reported the turn over yet. */
    private val turnsInFlight = ConcurrentHashMap.newKeySet<Pair<String, String>>()

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
        val mainThread =
            AgentThread(
                id = agentSession.mainThreadId,
                sessionId = sessionId,
                kind = ThreadKind.MAIN,
                title = session.title,
                status = AgentState.DONE,
            )
        guardingTheAttachedSession(sessionId, liveSession) {
            log.createSession(session, agentSession.agentSessionRef, mainThread)
            liveSession.job.start()
        }
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
     * refused. The message is logged as yours, on that thread, before the agent sees it, so everything
     * a prompt can be refused for is checked first, under a per-session lock. The adapter must not
     * echo it. A session that is not live is resumed first.
     *
     * @throws SessionException.NotFound when the session or thread does not exist.
     * @throws SessionException.Invalid when the thread is not the main thread.
     * @throws SessionException.Conflict when the thread is still in a turn, or the agent cannot resume.
     */
    suspend fun prompt(
        sessionId: String,
        threadId: String?,
        text: String,
    ) {
        if (text.isBlank()) throw SessionException.Invalid("empty_prompt", "The prompt text is empty.")
        // One prompt at a time per session, from the checks to the adapter call, so two racing prompts
        // cannot both pass the checks and both be logged.
        promptLocks.computeIfAbsent(sessionId) { Mutex() }.withLock {
            val target = resolvePromptTarget(sessionId, threadId)
            val turn = sessionId to target.id
            if (hasOpenApproval(sessionId, target.id, isMain = target.kind == ThreadKind.MAIN) ||
                !turnsInFlight.add(turn)
            ) {
                throw SessionException.Conflict("turn_in_progress", "That thread is still working on a turn.")
            }
            var accepted = false
            try {
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
                adapterCall(
                    { SessionException.Invalid("invalid_thread", "That thread does not accept prompts.", it) },
                ) {
                    agentSession.prompt(target.id, text)
                }
                accepted = true
            } finally {
                // Cleared when the thread reports its turn over, or here if the prompt never got in.
                if (!accepted) turnsInFlight.remove(turn)
            }
        }
    }

    /**
     * Stops the current turn on [threadId].
     *
     * @throws SessionException.NotFound when the session does not exist, or the thread is unknown to it
     *   or to the agent, which happens for a helper the agent forgot across a resume.
     * @throws SessionException.Conflict when the session is not running, or the thread cannot be stopped alone.
     */
    suspend fun cancel(
        sessionId: String,
        threadId: String,
    ) {
        resolveThread(sessionId, threadId)
        val agentSession = runningSession(sessionId)
        adapterCall({ SessionException.NotFound("thread_not_found", "The agent does not know that thread.", it) }) {
            agentSession.cancel(threadId)
        }
    }

    /**
     * Answers a pending approval request with one of its options. The request stays pending until the
     * agent reports it resolved, which is also how one it withdrew or cancelled goes away.
     *
     * @throws SessionException.NotFound when the bridge never saw a request with this id.
     * @throws SessionException.Invalid when [optionId] is not one of the request's options.
     * @throws SessionException.Conflict when the request is already resolved, or the session is no
     *   longer running.
     */
    suspend fun respondToApproval(
        approvalId: String,
        optionId: String,
    ) {
        val pending = pendingApproval(approvalId)
        if (optionId !in pending.optionIds) {
            throw SessionException.Invalid("invalid_option", "That is not one of the approval's options.")
        }
        val agentSession = runningSession(pending.sessionId)
        // The agent carries on with the turn once it has the answer. The flag goes up first, so no
        // prompt slips in between, and comes down again if the answer is refused.
        val turn = pending.threadId?.let { pending.sessionId to it }
        val raised = turn != null && turnsInFlight.add(turn)
        var delivered = false
        try {
            adapterCall(
                { SessionException.Conflict("approval_gone", "The agent is no longer waiting on that approval.", it) },
            ) {
                agentSession.respondToApproval(approvalId, optionId)
            }
            delivered = true
        } finally {
            if (raised && !delivered) turnsInFlight.remove(turn)
        }
    }

    private fun pendingApproval(approvalId: String): PendingApproval =
        approvals[approvalId]
            ?: if (approvalId in resolvedApprovals) {
                throw SessionException.Conflict("approval_gone", "That approval is already resolved.")
            } else {
                throw SessionException.NotFound("approval_not_found", "No pending approval '$approvalId'.")
            }

    /**
     * Run once at start-up. Agent processes do not survive a bridge restart, so any session or thread
     * still marked as working, or as waiting on an open approval, becomes errored and says why in its
     * log. Approvals still open are cancelled, so a client that replays the log sees no card it can no
     * longer answer. A session idle after a finished turn is left as it is.
     */
    suspend fun recoverAfterRestart() {
        for (session in log.sessions()) {
            interrupt(session, "Interrupted by a bridge restart. Send a message to resume.")
        }
    }

    /**
     * Ends the work a stopped agent left behind. Open approvals are cancelled. A thread waiting on you
     * with no open approval finished its turn and is only idle, so it stays. One that was working, or
     * waiting on an approval, is errored.
     */
    private suspend fun interrupt(
        session: Session,
        detail: String,
    ) {
        val interrupted = if (cancelOpenApprovals(session.id).isEmpty()) WORKING_ONLY else RUNNING_STATES
        settleRunningWork(session, AgentState.ERROR, detail, settling = interrupted)
    }

    /**
     * Stops every live session, all at once, and gives up on the slow ones after [SHUTDOWN_BUDGET].
     * Their logs stay, and they resume on the next prompt after a restart.
     */
    suspend fun shutdown() {
        val sessions = live.entries.map { it.key to it.value }
        live.clear()
        approvals.clear()
        turnsInFlight.clear()
        val finished =
            withTimeoutOrNull(SHUTDOWN_BUDGET) {
                coroutineScope {
                    sessions
                        .map { (sessionId, liveSession) ->
                            async {
                                // Joined, so nothing is logged for the session after this point.
                                liveSession.job.cancelAndJoin()
                                liveSession.stop()
                                log.session(sessionId)?.let {
                                    interrupt(it.session, "Interrupted by a bridge shutdown. Send a message to resume.")
                                }
                            }
                        }.awaitAll()
                }
            }
        if (finished == null) logger.warn("Some agent sessions did not stop within {}.", SHUTDOWN_BUDGET)
    }

    private class PendingApproval(
        val sessionId: String,
        val threadId: String?,
        val optionIds: Set<String>,
    )

    private class LiveSession(
        val agentSession: AgentSession,
        private val collector: Job,
        private val buffer: Channel<EventPayload>,
    ) {
        lateinit var job: Job

        /** Which thread each tool call belongs to, so an approval can be tied to its thread. */
        val toolThreads = ConcurrentHashMap<String, String>()

        /** Stops collecting and ends the agent session. Safe to call more than once. */
        suspend fun stop() {
            collector.cancel()
            buffer.cancel()
            agentSession.close()
        }
    }

    /**
     * Starts collecting [agentSession]'s updates into a buffer straight away, and returns the live
     * session whose [LiveSession.job] logs them. The job starts only when the caller says so.
     */
    private fun attach(
        sessionId: String,
        agentSession: AgentSession,
    ): LiveSession {
        val buffer = Channel<EventPayload>(Channel.UNLIMITED)
        val collector =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                agentSession.updates
                    .catch { buffer.close(it) }
                    .onCompletion { buffer.close() }
                    .collect { buffer.trySend(it) }
            }
        val liveSession = LiveSession(agentSession, collector, buffer)
        liveSession.job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val failure = drain(sessionId, liveSession, buffer)
                    if (live.remove(sessionId, liveSession)) {
                        forget(sessionId)
                        // The adapter reports the exit itself, with the code. The bridge only has to
                        // stop showing threads as working, and writes a session-level error only when
                        // its own logging failed.
                        if (failure != null) markDied(sessionId, failure) else settleThreadsAfterExit(sessionId)
                    }
                } finally {
                    withContext(NonCancellable) { liveSession.stop() }
                }
            }
        live[sessionId] = liveSession
        return liveSession
    }

    /** Logs everything in [buffer] until it closes. Returns why it stopped early, or null if the agent just ended. */
    @Suppress("TooGenericExceptionCaught") // Any failure here means the session is dead and must say so.
    private suspend fun drain(
        sessionId: String,
        liveSession: LiveSession,
        buffer: Channel<EventPayload>,
    ): Throwable? =
        try {
            for (payload in buffer) record(sessionId, liveSession, payload)
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failure
        }

    private suspend fun record(
        sessionId: String,
        liveSession: LiveSession?,
        payload: EventPayload,
    ) {
        // Bookkeeping first, so anyone who sees the event in the log also sees its effect here.
        when (payload) {
            is EventPayload.StatusChanged -> {
                payload.threadId?.let { trackTurn(sessionId, it, payload.state) }
            }

            is EventPayload.ThreadUpdated -> {
                trackTurn(sessionId, payload.thread.id, payload.thread.status)
            }

            is EventPayload.ToolCall -> {
                liveSession?.toolThreads?.put(payload.id, payload.threadId)
            }

            is EventPayload.ApprovalResolved -> {
                approvals.remove(payload.id)
                resolvedApprovals.add(payload.id)
            }

            is EventPayload.ApprovalRequest -> {
                approvals[payload.id] =
                    PendingApproval(
                        sessionId = sessionId,
                        threadId = liveSession?.toolThreads?.get(payload.toolCallId),
                        optionIds = payload.options.map { it.id }.toSet(),
                    )
            }

            else -> {
                Unit
            }
        }
        log.append(sessionId, payload)
    }

    /**
     * A turn is in flight from an accepted prompt until the thread reports something other than working.
     * Waiting on you ends the turn too, unless an approval is still open, which [hasOpenApproval] covers.
     */
    private fun trackTurn(
        sessionId: String,
        threadId: String,
        state: AgentState,
    ) {
        if (state == AgentState.WORKING) {
            turnsInFlight.add(sessionId to threadId)
        } else {
            turnsInFlight.remove(sessionId to threadId)
        }
    }

    /** An approval whose thread is unknown counts for the main thread only. */
    private fun hasOpenApproval(
        sessionId: String,
        threadId: String,
        isMain: Boolean,
    ): Boolean =
        approvals.values.any {
            it.sessionId == sessionId && (it.threadId == threadId || (it.threadId == null && isMain))
        }

    /** Writes the error status for a session whose logging failed. The failure's detail goes to the server log only. */
    @Suppress("TooGenericExceptionCaught") // The log may be what failed. Nothing more can be done but say so.
    private suspend fun markDied(
        sessionId: String,
        failure: Throwable,
    ) {
        logger.error("Logging for session {} failed, so the session is stopped.", sessionId, failure)
        try {
            val stored = log.session(sessionId) ?: return
            settleRunningWork(
                stored.session,
                AgentState.ERROR,
                "The bridge lost track of this session.",
                alsoSession = true,
            )
        } catch (secondary: Exception) {
            logger.error("Could not record the error status for session {}.", sessionId, secondary)
        }
    }

    /** Threads still working when the agent ends become done, or errored if the adapter marked the session so. */
    @Suppress("TooGenericExceptionCaught") // The log may be what failed, and there is nobody left to tell.
    private suspend fun settleThreadsAfterExit(sessionId: String) {
        try {
            val session = log.session(sessionId)?.session ?: return
            val state = if (session.status == AgentState.ERROR) AgentState.ERROR else AgentState.DONE
            for (thread in log.threads(sessionId).filter { it.status.isRunning() }) {
                record(sessionId, null, EventPayload.StatusChanged(thread.id, state, "The agent exited."))
            }
        } catch (failure: Exception) {
            logger.error("Could not settle the threads of session {}.", sessionId, failure)
        }
    }

    /** Sets the session, when [alsoSession] or its status is in [settling], and each such thread, to [state]. */
    private suspend fun settleRunningWork(
        session: Session,
        state: AgentState,
        detail: String,
        alsoSession: Boolean = false,
        settling: Set<AgentState> = RUNNING_STATES,
    ) {
        if (alsoSession || session.status in settling) {
            record(session.id, null, EventPayload.StatusChanged(threadId = null, state = state, detail = detail))
        }
        for (thread in log.threads(session.id).filter { it.status in settling }) {
            record(session.id, null, EventPayload.StatusChanged(threadId = thread.id, state = state, detail = detail))
        }
    }

    /**
     * Runs a call into the agent and turns its refusals into errors a client can act on. What an
     * [IllegalArgumentException] means depends on the call, so the caller says.
     */
    private suspend fun adapterCall(
        onIllegalArgument: (IllegalArgumentException) -> SessionException,
        call: suspend () -> Unit,
    ) {
        val refusal =
            try {
                call()
                return
            } catch (refused: IllegalArgumentException) {
                onIllegalArgument(refused)
            } catch (busy: TurnInProgressException) {
                SessionException.Conflict("turn_in_progress", "That thread is still working on a turn.", busy)
            } catch (unsupported: UnsupportedOperationException) {
                SessionException.Conflict("not_supported", "That thread cannot be cancelled on its own.", unsupported)
            } catch (closed: IllegalStateException) {
                SessionException.Conflict("session_not_running", "The session is not running.", closed)
            }
        throw refusal
    }

    /** Closes every approval request the log still shows as open, exactly once, as cancelled. */
    private suspend fun cancelOpenApprovals(sessionId: String): List<String> {
        val open = log.unresolvedApprovals(sessionId)
        for (approvalId in open) {
            record(sessionId, null, EventPayload.ApprovalResolved(approvalId, ApprovalOutcome.CANCELLED))
        }
        return open
    }

    private fun forget(sessionId: String) {
        approvals.values.removeIf { it.sessionId == sessionId }
        turnsInFlight.removeIf { it.first == sessionId }
        // A holder or waiter may still be using the lock. It is dropped only when nobody is.
        promptLocks.computeIfPresent(sessionId) { _, lock -> lock.takeIf { it.isLocked } }
    }

    private fun runningSession(sessionId: String): AgentSession =
        live[sessionId]?.agentSession
            ?: throw SessionException.Conflict("session_not_running", "The session is not running.")

    private suspend fun liveSession(sessionId: String): LiveSession {
        live[sessionId]?.let { return it }
        val stored = requireSession(sessionId)
        val lock = resumeLocks.computeIfAbsent(sessionId) { Mutex() }
        try {
            return lock.withLock {
                live[sessionId] ?: resume(stored.session, stored.agentSessionRef)
            }
        } finally {
            resumeLocks.remove(sessionId, lock)
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
        guardingTheAttachedSession(session.id, liveSession) {
            if (agentSession.agentSessionRef != agentSessionRef) {
                log.updateAgentSessionRef(session.id, agentSession.agentSessionRef)
            }
            val mainThread = log.threads(session.id).firstOrNull { it.kind == ThreadKind.MAIN }
            record(
                session.id,
                null,
                EventPayload.StatusChanged(threadId = mainThread?.id, state = AgentState.DONE, detail = "Resumed."),
            )
            liveSession.job.start()
        }
        return liveSession
    }

    /** Runs [block], and if it fails ends the attached session so no process or buffer is left behind. */
    @Suppress("TooGenericExceptionCaught") // Rethrown after the half-started session is cleaned up.
    private suspend fun guardingTheAttachedSession(
        sessionId: String,
        liveSession: LiveSession,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                live.remove(sessionId, liveSession)
                liveSession.job.cancel()
                liveSession.stop()
            }
            throw failure
        }
    }

    private suspend fun requireSession(sessionId: String) =
        log.session(sessionId) ?: throw SessionException.NotFound("session_not_found", "No session '$sessionId'.")

    /** Checks the target of a prompt before anything is logged. The turn check is in [prompt], under its lock. */
    private suspend fun resolvePromptTarget(
        sessionId: String,
        threadId: String?,
    ): AgentThread {
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

    private suspend fun existingDirectory(raw: String): Path {
        val path =
            try {
                Path.of(raw)
            } catch (invalid: InvalidPathException) {
                throw SessionException.Invalid("invalid_project_path", "project_path is not a valid path.", invalid)
            }
        val real =
            withContext(ioDispatcher) {
                if (path.isAbsolute &&
                    Files.isDirectory(path)
                ) {
                    path.toRealPath()
                } else {
                    null
                }
            }
        return real
            ?: throw SessionException.Invalid(
                "invalid_project_path",
                "project_path must be an absolute path to an existing folder.",
            )
    }

    private companion object {
        val SHUTDOWN_BUDGET = 10.seconds
        const val MAX_RESOLVED_APPROVALS = 1000
        val WORKING_ONLY = setOf(AgentState.WORKING)
        val RUNNING_STATES = setOf(AgentState.WORKING, AgentState.WAITING_ON_YOU)
        val logger = LoggerFactory.getLogger(SessionManager::class.java)
    }
}

private fun AgentState.isRunning(): Boolean = this == AgentState.WORKING || this == AgentState.WAITING_ON_YOU
