package ai.tatawur.rafiq.bridge.server

import ai.tatawur.rafiq.bridge.sessions.SessionException
import ai.tatawur.rafiq.bridge.sessions.SessionManager
import ai.tatawur.rafiq.bridge.store.EventLog
import ai.tatawur.rafiq.bridge.store.SlowConsumerException
import ai.tatawur.rafiq.protocol.Event
import ai.tatawur.rafiq.protocol.protocolJson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException

/** Turns every failure into the one [ApiError] shape, so clients parse a single error format. */
internal fun io.ktor.server.application.Application.installErrorShape() {
    install(StatusPages) {
        exception<SessionException> { call, failure ->
            val status =
                when (failure) {
                    is SessionException.Invalid -> HttpStatusCode.BadRequest
                    is SessionException.NotFound -> HttpStatusCode.NotFound
                    is SessionException.Conflict -> HttpStatusCode.Conflict
                }
            call.respond(status, ApiError(ApiErrorDetail(failure.code, failure.message.orEmpty())))
        }
        exception<BadRequestException> { call, _ -> call.respondInvalidBody() }
        exception<SerializationException> { call, _ -> call.respondInvalidBody() }
        // Anything unexpected, including a failing adapter, gets the standard body and no stack trace.
        exception<Throwable> { call, failure ->
            call.application.log.error("Request failed: ${call.request.local.uri}", failure)
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiError(ApiErrorDetail("internal_error", "The bridge could not complete the request.")),
            )
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respond(HttpStatusCode.NotFound, ApiError(ApiErrorDetail("not_found", "No such route.")))
        }
    }
}

private suspend fun ApplicationCall.respondInvalidBody() {
    respond(
        HttpStatusCode.BadRequest,
        ApiError(ApiErrorDetail("invalid_body", "The request body is missing or is not the expected JSON.")),
    )
}

/** The session routes. They stay thin: parsing here, the work in [SessionManager]. */
internal fun Route.sessionRoutes(sessions: SessionManager) {
    post("/sessions") {
        val request = call.receive<CreateSessionRequest>()
        call.respond(
            HttpStatusCode.Created,
            sessions.create(request.agent, request.projectPath, request.title),
        )
    }
    get("/sessions") {
        call.respond(sessions.sessions())
    }
    get("/sessions/{id}/threads") {
        call.respond(sessions.threads(call.parameters.getOrFail("id")))
    }
    // Prompts go to the main thread. A thread_id naming a helper thread is rejected with invalid_thread.
    post("/sessions/{id}/prompt") {
        val request = call.receive<PromptRequest>()
        sessions.prompt(call.parameters.getOrFail("id"), request.threadId, request.text)
        call.respond(HttpStatusCode.Accepted, Accepted())
    }
    post("/sessions/{id}/threads/{threadId}/cancel") {
        sessions.cancel(call.parameters.getOrFail("id"), call.parameters.getOrFail("threadId"))
        call.respond(HttpStatusCode.Accepted, Accepted())
    }
    post("/approvals/{id}") {
        val answer = call.receive<ApprovalAnswer>()
        sessions.respondToApproval(call.parameters.getOrFail("id"), answer.optionId)
        call.respond(HttpStatusCode.Accepted, Accepted())
    }
}

/**
 * `WS /events?session=<id>&after=<seq>`. Each text frame is one [Event] as JSON.
 *
 * A client that falls too far behind is closed with `TRY_AGAIN_LATER`. It reconnects with the last
 * `seq` it saw and replays what it missed.
 */
internal fun Route.eventsRoute(
    log: EventLog,
    streamer: EventStreamer,
) {
    webSocket("/events") {
        val sessionId = call.request.queryParameters["session"]
        val after = call.request.queryParameters["after"]?.let { it.toLongOrNull()?.takeIf { seq -> seq >= 0 } }
        val rejection =
            when {
                sessionId.isNullOrBlank() -> "session is required"
                call.request.queryParameters["after"] != null && after == null -> "after must be a non-negative integer"
                log.session(sessionId) == null -> "no such session"
                else -> null
            }
        if (rejection != null || sessionId == null) {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, rejection.orEmpty()))
            return@webSocket
        }
        var closeReason = CloseReason(CloseReason.Codes.NORMAL, "")
        val stream =
            launch {
                try {
                    streamer.stream(sessionId, after ?: 0) { event ->
                        send(Frame.Text(protocolJson.encodeToString(Event.serializer(), event)))
                    }
                } catch (_: SlowConsumerException) {
                    closeReason = CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "too slow, reconnect and replay")
                }
            }
        // The client sends nothing we act on, but we must read to notice it leaving.
        val watcher =
            launch {
                for (frame in incoming) if (frame is Frame.Text) frame.readText()
                stream.cancel()
            }
        stream.join()
        watcher.cancel()
        close(closeReason)
    }
}

/** The development page and the list of agents it offers. Installed only when the page is enabled. */
internal fun Route.devRoutes(sessions: SessionManager) {
    val page =
        checkNotNull(EventStreamer::class.java.getResource("/dev/index.html")) {
            "dev/index.html is missing from the bridge resources."
        }.readText()
    get("/dev") { call.respondText(page, ContentType.Text.Html) }
    get("/dev/agents") { call.respond(sessions.agentIds) }
}

private fun io.ktor.http.Parameters.getOrFail(name: String): String =
    this[name] ?: throw BadRequestException("Missing path parameter '$name'.")
