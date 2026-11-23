package ai.tatawur.rafiq.bridge.server

import ai.tatawur.rafiq.bridge.push.PushRegistry
import ai.tatawur.rafiq.protocol.api.PushRegistration
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.put

/** `PUT` and `DELETE /devices/me/push`, for the device the call authenticated as. */
internal fun Route.pushRoutes(
    registry: PushRegistry,
    deviceOf: (ApplicationCall) -> String,
) {
    put("/devices/me/push") {
        registry.register(deviceOf(call), call.receive<PushRegistration>())
        call.respond(HttpStatusCode.NoContent)
    }
    delete("/devices/me/push") {
        registry.unregister(deviceOf(call))
        call.respond(HttpStatusCode.NoContent)
    }
}
