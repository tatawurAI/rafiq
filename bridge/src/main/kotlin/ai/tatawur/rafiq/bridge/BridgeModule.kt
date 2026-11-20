package ai.tatawur.rafiq.bridge

import ai.tatawur.rafiq.protocol.protocolJson
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Installs the bridge's plugins and routes. Kept separate from [main] so tests can run the same
 * application in Ktor's test host without opening a port.
 *
 * @param version the bridge version, reported by `GET /health`.
 */
fun Application.bridgeModule(version: String) {
    install(ContentNegotiation) {
        json(protocolJson)
    }
    routing {
        // Unauthenticated on purpose: it reveals only liveness and version, and it is what you
        // check first when the phone cannot connect.
        get("/health") {
            call.respond(Health(status = "ok", version = version))
        }
    }
}

/** Body of `GET /health`. */
@Serializable
data class Health(
    @SerialName("status") val status: String,
    @SerialName("version") val version: String,
)
