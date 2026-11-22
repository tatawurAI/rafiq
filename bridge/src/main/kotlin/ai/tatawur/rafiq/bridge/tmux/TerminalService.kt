package ai.tatawur.rafiq.bridge.tmux

import ai.tatawur.rafiq.bridge.server.AuditLog
import ai.tatawur.rafiq.bridge.server.ConnectionRegistry
import ai.tatawur.rafiq.bridge.sessions.TMUX_SESSION_PREFIX
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import org.slf4j.LoggerFactory
import java.util.UUID

/** What the terminal view needs from the gate: its settings, the tmux server it may use and the socket limits. */
class TerminalGate(
    val settings: TerminalSettings,
    val server: TmuxServer = TmuxServer(settings.socket),
) {
    val slots = TerminalSlots(settings.limits)

    /** How often each device may read scrollback, and how many reads run at once. */
    val historyLimits = HistoryLimits()

    /** The sizes sessions had before a view took them, so the last view to leave gives them back. */
    val sessionSizes = SessionSizes()

    /** The sessions Terminal may open, as of the tmux source's last scan. Empty until the first one. */
    @Volatile
    private var openable: Set<String> = emptySet()

    /**
     * The tmux session name a request may open in Terminal, from its `session` parameter (`tmux-<name>`), or
     * null. Both terminal routes ask here, so a session is reachable by one rule: listed, Terminal on, and an
     * agent detected in it or its name in `RAFIQ_TERMINAL_SHELLS` (see [TerminalSettings.access]).
     */
    fun allowedName(sessionParameter: String?): String? =
        sessionParameter
            ?.takeIf { it.startsWith(TMUX_SESSION_PREFIX) }
            ?.removePrefix(TMUX_SESSION_PREFIX)
            ?.takeIf { settings.allows(it) && it in openable }

    /** Whether Terminal may open the tmux session called [name], as of the last scan. */
    fun canOpen(name: String): Boolean = name in openable

    /** Called by the tmux source after each scan, or with nothing when a scan fails. */
    fun updateOpenable(names: Set<String>) {
        openable = names
    }

    /** The name every view of this bridge process starts with: `rafiq-view-<nonce>-`. */
    val viewPrefix: String = TmuxServer.VIEW_PREFIX + UUID.randomUUID().toString().take(NONCE_LENGTH) + "-"
}

/**
 * Everything the bridge starts when Terminal is on: the `WS /terminal` and `GET /terminal/history` routes and
 * the tmux session source.
 * With Terminal off the bridge builds none of it, so no tmux command ever runs.
 */
class TerminalService(
    val gate: TerminalGate,
    val source: TmuxSessionSource,
) {
    fun routes(
        route: Route,
        connections: ConnectionRegistry,
        deviceOf: (ApplicationCall) -> String,
        audit: AuditLog,
    ) {
        route.terminalRoute(gate, connections, deviceOf)
        route.terminalHistoryRoute(gate, deviceOf, audit)
    }

    /**
     * Run once before the bridge serves. Checks tmux is new enough and ends view sessions an earlier bridge
     * left behind, on this bridge's own tmux server only.
     */
    suspend fun prepare() {
        val version = gate.server.version()
        val (major, minor) = TmuxServer.MINIMUM_VERSION
        when {
            version == null -> {
                log.warn("Terminal is on but tmux could not be run. Terminal and tmux sessions will not work.")
            }

            !TmuxServer.isSupported(version) -> {
                log.warn(
                    "Terminal needs tmux {}.{} or newer, found {}.{}.",
                    major,
                    minor,
                    version.first,
                    version.second,
                )
            }
        }
        @Suppress("TooGenericExceptionCaught") // Nothing to clean up is a fine answer.
        try {
            gate.server.killLeftoverViews()
        } catch (failure: Exception) {
            log.warn("Could not look for leftover view sessions: {}", failure.toString())
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(TerminalService::class.java)
    }
}

private const val NONCE_LENGTH = 6
