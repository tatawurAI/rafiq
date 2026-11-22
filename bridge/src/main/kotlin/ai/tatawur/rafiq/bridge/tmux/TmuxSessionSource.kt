package ai.tatawur.rafiq.bridge.tmux

import ai.tatawur.rafiq.bridge.sessions.TMUX_SESSION_PREFIX
import ai.tatawur.rafiq.bridge.sessions.isTmuxSessionId
import ai.tatawur.rafiq.bridge.store.EventLog
import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.AgentThread
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.Session
import ai.tatawur.rafiq.protocol.ThreadKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Shows the allowed tmux sessions as sessions of the bridge, in the same log and lists as sessions you start
 * from the app. Id `tmux-<name>`, agent `claude-code` or `terminal`, `terminal = true`.
 *
 * A session is visible only while the gate allows its name and tmux still has it. Both are checked on every
 * poll, so a session that is killed leaves the list on the next one. Until the first poll finishes, and
 * whenever tmux cannot be asked, nothing is visible. A tmux session that is gone is also settled in the log:
 * if it was shown as working or waiting on you, it becomes done, so no later reader of the log sees a stale
 * "Waiting on you".
 */
class TmuxSessionSource(
    private val gate: TerminalGate,
    private val log: EventLog,
    private val agents: AgentDetector = AgentDetector(),
    private val clock: Clock = Clock.systemUTC(),
    private val interval: Duration = 3.seconds,
) {
    private val scan = Mutex()

    @Volatile
    private var visible: Set<String> = emptySet()

    /**
     * Called after every scan with the panes of the allowed sessions and the process table it used, so the Claude
     * reader can follow the agent panes without asking tmux again. Set once, before [start].
     */
    @Volatile
    var onScan: (suspend (panes: List<PaneInfo>, processes: Map<Int, Pair<Int, String>>) -> Unit)? = null

    /**
     * The agent's permission mode per session, from the Claude reader. Like `terminalAllowed`, it is not stored
     * in the log, so every session this source shows or announces carries it. Set once, before [start].
     */
    @Volatile
    var permissionModeOf: (sessionId: String) -> String? = { null }

    /** The `terminalAllowed` this process last announced per session, so a change is announced once. */
    private val announced = mutableMapOf<String, Boolean>()

    /** Whether clients may see the session: not a tmux session, or one this source lists right now. */
    fun isVisible(sessionId: String): Boolean = !isTmuxSessionId(sessionId) || sessionId in visible

    /**
     * [session] with `terminalAllowed` as of the last scan. The log does not store that field, because it
     * follows the bridge's settings and the agent in the pane, not the session's history.
     */
    fun present(session: Session): Session =
        if (isTmuxSessionId(session.id)) {
            withExtras(session, gate.canOpen(session.id.removePrefix(TMUX_SESSION_PREFIX)))
        } else {
            session
        }

    /**
     * [session] with the fields the log does not store: `terminal`, [terminalAllowed] and the permission mode.
     * Every `SessionUpdated` for a tmux session is built through here, so none of them resets one of these.
     */
    private fun withExtras(
        session: Session,
        terminalAllowed: Boolean,
    ): Session =
        session.copy(
            terminal = true,
            terminalAllowed = terminalAllowed,
            permissionMode = permissionModeOf(session.id) ?: session.permissionMode,
        )

    /** Polls until [scope] ends. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            while (true) {
                poll()
                delay(interval)
            }
        }
    }

    /**
     * One poll. A failed one hides the sessions until the next good poll, but keeps which ones Terminal may open,
     * so one slow `tmux` call does not take Terminal away from a phone that has it open.
     */
    @Suppress("TooGenericExceptionCaught") // A failed poll is retried on the next turn.
    suspend fun poll() {
        try {
            refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            visible = emptySet()
            logger.warn("tmux poll failed: {}", failure.toString())
        }
    }

    /** Looks at tmux once: lists new sessions, updates agents, and settles the ones that are gone. */
    suspend fun refresh() =
        scan.withLock {
            val settings = gate.settings
            if (!settings.enabled) return@withLock
            val existing = gate.server.sessions()
            val allowed = existing.filter { settings.allows(it.name) }
            val names = allowed.map { it.name }.toSet()
            val panes = if (allowed.isEmpty()) emptyList() else gate.server.panes().filter { it.sessionName in names }
            val processes = if (panes.isEmpty()) emptyMap() else AgentDetector.processTable()
            val detected = if (panes.isEmpty()) emptyMap() else agents.detect(panes, processes)
            val opens = mutableSetOf<String>()
            for (session in allowed) {
                val found = detected[session.name] ?: DetectedAgent(AgentDetector.TERMINAL, null)
                val terminalAllowed = settings.access(session.name, found.agent != AgentDetector.TERMINAL).terminal
                if (terminalAllowed) opens += session.name
                show(session.name, found, terminalAllowed)
            }
            settleGone(existing.map { it.name }.toSet())
            visible = names.map { TMUX_SESSION_PREFIX + it }.toSet()
            gate.updateOpenable(opens)
            onScan?.invoke(panes, processes)
        }

    private suspend fun show(
        name: String,
        found: DetectedAgent,
        terminalAllowed: Boolean,
    ) {
        val id = TMUX_SESSION_PREFIX + name
        val stored = log.session(id)
        if (stored == null) {
            val now = clock.millis()
            val session =
                Session(
                    id = id,
                    agent = found.agent,
                    projectPath = found.projectPath ?: "/",
                    title = name,
                    status = AgentState.DONE,
                    createdAtMs = now,
                    updatedAtMs = now,
                    terminal = true,
                    terminalAllowed = terminalAllowed,
                )
            val main = AgentThread(MAIN_THREAD, id, null, ThreadKind.MAIN, name, AgentState.DONE)
            log.createSession(session, AGENT_SESSION_REF, main)
        } else {
            // A session first listed as a shell somewhere else gets Claude's folder once Claude runs in it, so the
            // hooks and the record are looked for in the right project.
            val project =
                found.projectPath?.takeIf { found.agent == AgentDetector.CLAUDE_CODE } ?: stored.session.projectPath
            val changed = stored.session.agent != found.agent || stored.session.projectPath != project
            if (changed || announced[name] != terminalAllowed) {
                if (stored.session.agent != found.agent) log.updateAgent(id, found.agent)
                if (stored.session.projectPath != project) log.updateProject(id, project)
                val updated =
                    withExtras(stored.session.copy(agent = found.agent, projectPath = project), terminalAllowed)
                log.append(id, EventPayload.SessionUpdated(updated))
            }
        }
        announced[name] = terminalAllowed
    }

    /** A tmux session tmux no longer has cannot be working or waiting on anything. */
    private suspend fun settleGone(existingNames: Set<String>) {
        val gone =
            log.sessions().filter {
                isTmuxSessionId(
                    it.id,
                ) && it.id.removePrefix(TMUX_SESSION_PREFIX) !in existingNames
            }
        for (session in gone) {
            if (session.status.isRunning()) {
                log.append(session.id, EventPayload.StatusChanged(null, AgentState.DONE, ENDED_DETAIL))
            }
            for (thread in log.threads(session.id).filter { it.status.isRunning() }) {
                log.append(session.id, EventPayload.StatusChanged(thread.id, AgentState.DONE, ENDED_DETAIL))
            }
        }
    }

    private fun AgentState.isRunning() = this == AgentState.WORKING || this == AgentState.WAITING_ON_YOU

    private companion object {
        const val MAIN_THREAD = "main"
        const val AGENT_SESSION_REF = "tmux"
        const val ENDED_DETAIL = "The tmux session ended."
        val logger = LoggerFactory.getLogger(TmuxSessionSource::class.java)
    }
}
