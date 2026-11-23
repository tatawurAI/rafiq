package ai.tatawur.rafiq.bridge

import ai.tatawur.rafiq.bridge.hook.HookSetup
import ai.tatawur.rafiq.bridge.projects.ProjectCatalog
import ai.tatawur.rafiq.bridge.push.PushRegistry
import ai.tatawur.rafiq.bridge.server.AuditLog
import ai.tatawur.rafiq.bridge.server.BridgeSecurity
import ai.tatawur.rafiq.bridge.server.ConnectionRegistry
import ai.tatawur.rafiq.bridge.server.DevicePrincipalKey
import ai.tatawur.rafiq.bridge.server.DeviceService
import ai.tatawur.rafiq.bridge.server.EventStreamer
import ai.tatawur.rafiq.bridge.server.InMemoryConnectionRegistry
import ai.tatawur.rafiq.bridge.server.LoggingAuditLog
import ai.tatawur.rafiq.bridge.server.devRoutes
import ai.tatawur.rafiq.bridge.server.deviceRoutes
import ai.tatawur.rafiq.bridge.server.eventsRoute
import ai.tatawur.rafiq.bridge.server.hookRoutes
import ai.tatawur.rafiq.bridge.server.installErrorShape
import ai.tatawur.rafiq.bridge.server.installSecurity
import ai.tatawur.rafiq.bridge.server.projectRoutes
import ai.tatawur.rafiq.bridge.server.pushRoutes
import ai.tatawur.rafiq.bridge.server.sessionRoutes
import ai.tatawur.rafiq.bridge.sessions.SessionManager
import ai.tatawur.rafiq.bridge.store.EventLog
import ai.tatawur.rafiq.bridge.tmux.TerminalService
import ai.tatawur.rafiq.protocol.api.Health
import ai.tatawur.rafiq.protocol.protocolJson
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.coroutines.runBlocking

/**
 * Installs the bridge's plugins and routes. Kept separate from [main] so tests can run the same
 * application in Ktor's test host without opening a port.
 *
 * @param version the bridge version, reported by `GET /health`.
 * @param security the host, origin and device token checks every request passes first.
 * @param devPage whether to serve the development page at `/dev`. When false the route does not exist.
 * @param terminal the Terminal view and the tmux session source, or null when Terminal is off. With null,
 *   `WS /terminal` does not exist and no tmux session is listed.
 * @param devices the per-device tokens, which add `POST /pair` and `DELETE /devices/me`. Null keeps neither.
 * @param connections where every `/events` and `/terminal` socket registers, so revoking a device closes its
 *   sockets at once.
 * @param hooks the hook installer for tmux sessions, which adds `/sessions/{id}/hooks`. Null keeps it out.
 * @param projects the project picker's catalog, which adds `/projects`. Null keeps it out.
 * @param push the per-device push registrations, which add `/devices/me/push`. Null keeps them out.
 * @param audit where each Chat prompt and approval answer from a phone is recorded.
 * @param deviceOf names the device a call came from, for the connection registry and the per-device terminal
 *   limit. By default it is the device the security plugin authenticated the call as.
 */
fun Application.bridgeModule(
    version: String,
    log: EventLog,
    sessions: SessionManager,
    security: BridgeSecurity,
    devPage: Boolean = false,
    terminal: TerminalService? = null,
    connections: ConnectionRegistry = InMemoryConnectionRegistry(),
    devices: DeviceService? = null,
    hooks: HookSetup? = null,
    projects: ProjectCatalog? = null,
    push: PushRegistry? = null,
    audit: AuditLog = LoggingAuditLog(),
    deviceOf: (ApplicationCall) -> String = { it.attributes.getOrNull(DevicePrincipalKey)?.deviceId ?: UNKNOWN_DEVICE },
) {
    install(ContentNegotiation) {
        json(protocolJson)
    }
    install(WebSockets) {
        pingPeriodMillis = PING_PERIOD_MS
        timeoutMillis = PING_TIMEOUT_MS
        // Clients send nothing the bridge reads, so a small frame is plenty and a big one is an attack.
        maxFrameSize = MAX_FRAME_BYTES
    }
    install(RequestBodyLimit) {
        bodyLimit { MAX_BODY_BYTES }
    }
    installErrorShape()
    installSecurity(security)
    monitor.subscribe(ApplicationStopping) { runBlocking { sessions.shutdown() } }
    routing {
        sessionRoutes(sessions, audit, deviceOf)
        eventsRoute(log, EventStreamer(log), sessions::isVisible, connections, deviceOf)
        terminal?.routes(this, connections, deviceOf, audit)
        devices?.let { deviceRoutes(it) }
        hooks?.let { hookRoutes(it, sessions, deviceOf, audit) }
        projects?.let { projectRoutes(it) }
        push?.let { pushRoutes(it, deviceOf) }
        if (devPage) devRoutes(sessions)
        // Open to any caller on purpose, though the host and origin checks still apply: it reveals only
        // liveness and version, and it is what you check first when the phone cannot connect.
        get("/health") {
            call.respond(Health(status = "ok", version = version))
        }
    }
}

/** For a call the security plugin did not name a device for. Every route that matters has one. */
private const val UNKNOWN_DEVICE = "unknown"

private const val MAX_FRAME_BYTES = 64L * 1024
private const val MAX_BODY_BYTES = 1024L * 1024
private const val PING_PERIOD_MS = 20_000L
private const val PING_TIMEOUT_MS = 30_000L
