package ai.tatawur.rafiq.bridge.tmux.reader

import ai.tatawur.rafiq.bridge.hook.HookEnvelope
import ai.tatawur.rafiq.bridge.hook.HookReply
import ai.tatawur.rafiq.bridge.hook.HookSink
import ai.tatawur.rafiq.bridge.server.ConnectionRegistry
import ai.tatawur.rafiq.bridge.sessions.SessionException
import ai.tatawur.rafiq.bridge.sessions.TMUX_SESSION_PREFIX
import ai.tatawur.rafiq.bridge.tmux.AgentDetector
import ai.tatawur.rafiq.bridge.tmux.PaneInfo
import ai.tatawur.rafiq.protocol.Session
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * The Claude Code reader (ADR 0013, "The reader model"): observes Claude Code running in the allowed tmux
 * sessions and turns what it does into the same events an ACP session writes, so the app shows a real Chat.
 *
 * The hook channel delivers every hook call here ([HookSink]). A call is routed by its pane id to the tmux
 * session that owns the pane, and dropped when that session is not allowed, is not listed yet, or the call is
 * from another agent. The tmux source reports every scan ([onScan]), which is how the reader finds Claude panes
 * that send no hooks yet and rebuilds its pane mapping after a restart.
 *
 * @param allows the Terminal gate's allow-list, by tmux session name.
 * @param devices the ids of the paired devices, for releasing approvals when one is revoked.
 * @param hooksInstalled whether a project has the Rafiq hooks, so a prompt to a project without them is refused.
 * @param projectsDir where Claude Code keeps session records, for panes found by the scan.
 */
@Suppress("LongParameterList", "TooManyFunctions") // Seams for tests; one function per HookSink/TmuxChat member.
class ClaudeReader(
    private val log: ReaderLog,
    private val io: PaneIo,
    private val statuses: AgentStatusSource,
    private val allows: (tmuxSessionName: String) -> Boolean,
    private val scope: CoroutineScope,
    registry: ConnectionRegistry? = null,
    devices: suspend () -> List<String> = { emptyList() },
    private val offsets: ReaderOffsets = ReaderOffsets(null),
    private val hooksInstalled: suspend (projectPath: String) -> Boolean = { true },
    private val projectsDir: Path? = defaultProjectsDir(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timing: ReaderTiming = ReaderTiming(),
    private val present: (Session) -> Session = { it },
) : HookSink,
    TmuxChat {
    private val sessions = ConcurrentHashMap<String, TmuxChatSession>()
    private val paneOwners = ConcurrentHashMap<String, String>()
    private val missedPanes = ConcurrentHashMap<String, Long>()
    private val known = ConcurrentHashMap<String, Long>()

    /** The agent pid last proven to run inside each pane, so the process tree is read once per process. */
    private val verified = ConcurrentHashMap<String, Long>()

    @Volatile private var serverSocket: Path? = null
    private val guard = SendGuard(io, statuses)
    private val creating = Mutex()
    private val approvals =
        PhoneApprovals(log, registry, devices, onSettled = { approval ->
            sessions[approval.sessionId.removePrefix(TMUX_SESSION_PREFIX)]?.onApprovalSettled(approval.paneId)
        })
    private val context =
        ReaderContext(
            log,
            guard,
            statuses,
            approvals,
            offsets,
            hooksInstalled,
            scope,
            nowMs,
            ioDispatcher,
            timing,
            projectsDir,
            registry,
            present,
        )

    override suspend fun onEvent(envelope: HookEnvelope) {
        route(envelope)?.onEvent(envelope)
    }

    override suspend fun onPermission(envelope: HookEnvelope): HookReply? = route(envelope)?.onPermission(envelope)

    /**
     * The session a hook call belongs to, or null to drop it. A call counts only when it is from Claude Code, from
     * a pane of the bridge's own tmux server, in an allowed session, and from an agent process that runs inside
     * that pane. A pane id alone is not enough: another tmux server has panes with the same ids, and a pane id is
     * reused once its pane closes.
     */
    private suspend fun route(envelope: HookEnvelope): TmuxChatSession? =
        if (envelope.agent == CLAUDE_CODE && onThisServer(envelope.tmuxSocket)) {
            owner(envelope.pane)
                ?.takeIf { agentInPane(envelope.pane, envelope.agentPid) }
                ?.let { session(it) }
        } else {
            null
        }

    /** True when [socket] is the bridge's tmux server, or the hook did not say. */
    private suspend fun onThisServer(socket: String?): Boolean {
        if (socket == null) return true
        val ours = serverSocket ?: io.socketPath()?.let(::canonical)?.also { serverSocket = it }
        return ours != null && ours == canonical(socket)
    }

    private suspend fun agentInPane(
        paneId: String,
        agentPid: Long?,
    ): Boolean =
        agentPid == null || verified[paneId] == agentPid ||
            io.runsAgent(paneId, agentPid).also { if (it) verified[paneId] = agentPid }

    override suspend fun prompt(
        sessionId: String,
        text: String,
        clientId: String?,
        deviceId: String?,
    ) {
        val session =
            sessions[sessionId.removePrefix(TMUX_SESSION_PREFIX)]
                ?: throw SessionException.Conflict(Refusal.NOT_RUNNING.code, Refusal.NOT_RUNNING.message)
        session.prompt(text, clientId, deviceId)
    }

    override fun ownsApproval(approvalId: String): Boolean = approvals.owns(approvalId)

    override suspend fun respondToApproval(
        approvalId: String,
        optionId: String,
    ) = approvals.answer(approvalId, optionId)

    /**
     * A device was paired. Approvals already waiting register it too, so revoking it releases them. The device
     * service calls this after a pairing succeeds.
     */
    fun devicePaired(deviceId: String) = approvals.devicePaired(deviceId)

    override fun permissionMode(sessionId: String): String? =
        sessions[sessionId.removePrefix(TMUX_SESSION_PREFIX)]?.permissionMode

    /**
     * One scan of the allowed sessions by the tmux source: [panes] of those sessions and the machine's process
     * table. Claude panes the reader has not seen with this process are looked up in Claude's own session list
     * once, for their session id and record.
     */
    suspend fun onScan(
        panes: List<PaneInfo>,
        processes: Map<Int, Pair<Int, String>>,
    ) {
        val seen = panes.map(PaneInfo::paneId).toSet()
        // Pane ids are reused once a pane closes, so nothing is kept about a pane the scan no longer has.
        paneOwners.keys.retainAll(seen)
        missedPanes.keys.retainAll(seen)
        verified.keys.retainAll(seen)
        val candidates =
            panes.filter { allows(it.sessionName) }.associateWith { pane ->
                AgentDetector.claudeProcessesUnder(pane.panePid, processes).map(Int::toLong)
            }
        val claude = candidates.mapNotNull { (pane, pids) -> pids.maxOrNull()?.let { pane to it } }
        val changed = claude.any { (pane, pid) -> known[pane.paneId] != pid }
        val listed = if (changed) statuses.statuses().orEmpty() else emptyMap()
        for ((pane, pid) in claude) {
            paneOwners[pane.paneId] = pane.sessionName
            known[pane.paneId] = pid
        }
        known.keys.retainAll(claude.map { it.first.paneId }.toSet())
        val byName = claude.groupBy { it.first.sessionName }
        for (name in (byName.keys + sessions.keys).toSet()) {
            val found =
                byName[name].orEmpty().map { (pane, pid) ->
                    val status = listed[pid]
                    FoundPane(
                        paneId = pane.paneId,
                        agentPid = pid,
                        isCurrent = pane.isCurrent,
                        claudeSessionId = status?.sessionId,
                        recordPath = recordPathFor(projectsDir, status),
                        candidates = candidates[pane].orEmpty(),
                    )
                }
            if (found.isEmpty() && !sessions.containsKey(name)) continue
            session(name)?.onFound(found)
        }
    }

    /** Ends every session's Chat. Pending approvals go back to the desk. */
    suspend fun close() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }

    /** The allowed tmux session that owns [paneId], asking tmux at most once every few seconds for a new pane. */
    private suspend fun owner(paneId: String): String? {
        val known = paneOwners[paneId]
        val missedAt = missedPanes[paneId]
        return when {
            known != null -> {
                known.takeIf(allows)
            }

            missedAt != null && nowMs() - missedAt < MISS_RETRY_MS -> {
                null
            }

            else -> {
                val name =
                    io
                        .panes()
                        .firstOrNull { it.paneId == paneId }
                        ?.sessionName
                        ?.takeIf(allows)
                if (name == null) missedPanes[paneId] = nowMs() else paneOwners[paneId] = name
                name
            }
        }
    }

    /** The Chat of tmux session [name], made on first use once the tmux source has listed the session. */
    private suspend fun session(name: String): TmuxChatSession? =
        sessions[name] ?: if (!allows(name)) {
            null
        } else {
            creating.withLock {
                val sessionId = TMUX_SESSION_PREFIX + name
                sessions[name] ?: log.session(sessionId)?.let {
                    TmuxChatSession(sessionId, context).also { sessions[name] = it }
                }
            }
        }

    companion object {
        const val CLAUDE_CODE = "claude-code"
        private const val MISS_RETRY_MS = 5_000L

        private fun canonical(path: String): Path? =
            runCatching {
                Path
                    .of(
                        path,
                    ).toRealPath()
            }.getOrElse { runCatching { Path.of(path).normalize() }.getOrNull() }

        /** Claude Code's folder of session records, one folder per project. */
        fun defaultProjectsDir(): Path? = System.getProperty("user.home")?.let { Path.of(it, ".claude", "projects") }
    }
}

/** Where Claude Code keeps the record of a session: one folder per project, named after its path. */
private fun recordPathFor(
    projectsDir: Path?,
    status: AgentStatus?,
): Path? {
    val id = status?.sessionId
    val cwd = status?.cwd
    return if (id == null ||
        cwd == null
    ) {
        null
    } else {
        projectsDir?.resolve(cwd.replace(Regex("[^A-Za-z0-9]"), "-"))?.resolve("$id.jsonl")
    }
}
