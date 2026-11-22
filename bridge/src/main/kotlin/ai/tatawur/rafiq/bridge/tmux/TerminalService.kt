package ai.tatawur.rafiq.bridge.tmux

import ai.tatawur.rafiq.bridge.server.ConnectionRegistry
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

    /** The name every view of this bridge process starts with: `rafiq-view-<nonce>-`. */
    val viewPrefix: String = TmuxServer.VIEW_PREFIX + UUID.randomUUID().toString().take(NONCE_LENGTH) + "-"
}

/**
 * Everything the bridge starts when Terminal is on: the `WS /terminal` route and the tmux session source.
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
    ) {
        route.terminalRoute(gate, connections, deviceOf)
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
