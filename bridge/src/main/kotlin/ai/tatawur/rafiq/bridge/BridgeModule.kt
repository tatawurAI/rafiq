package ai.tatawur.rafiq.bridge

import ai.tatawur.rafiq.bridge.server.EventStreamer
import ai.tatawur.rafiq.bridge.server.devRoutes
import ai.tatawur.rafiq.bridge.server.eventsRoute
import ai.tatawur.rafiq.bridge.server.installErrorShape
import ai.tatawur.rafiq.bridge.server.sessionRoutes
import ai.tatawur.rafiq.bridge.sessions.SessionManager
import ai.tatawur.rafiq.bridge.store.EventLog
import ai.tatawur.rafiq.protocol.protocolJson
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Installs the bridge's plugins and routes. Kept separate from [main] so tests can run the same
 * application in Ktor's test host without opening a port.
 *
 * @param version the bridge version, reported by `GET /health`.
 * @param devPage whether to serve the development page at `/dev`. When false the route does not exist.
 */
fun Application.bridgeModule(
    version: String,
    log: EventLog,
    sessions: SessionManager,
    devPage: Boolean = false,
) {
    install(ContentNegotiation) {
        json(protocolJson)
    }
    install(WebSockets) {
        pingPeriodMillis = PING_PERIOD_MS
        timeoutMillis = PING_TIMEOUT_MS
    }
    installErrorShape()
    monitor.subscribe(ApplicationStopping) { runBlocking { sessions.shutdown() } }
    routing {
        sessionRoutes(sessions)
        eventsRoute(log, EventStreamer(log))
        if (devPage) devRoutes(sessions)
        // Unauthenticated on purpose: it reveals only liveness and version, and it is what you
        // check first when the phone cannot connect.
        get("/health") {
            call.respond(Health(status = "ok", version = version))
        }
    }
}

private const val PING_PERIOD_MS = 20_000L
private const val PING_TIMEOUT_MS = 30_000L

/** Body of `GET /health`. */
@Serializable
data class Health(
    @SerialName("status") val status: String,
    @SerialName("version") val version: String,
)
