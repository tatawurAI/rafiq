package ai.tatawur.rafiq.bridge.server

import ai.tatawur.rafiq.bridge.hook.HookSetup
import ai.tatawur.rafiq.bridge.sessions.SessionException
import ai.tatawur.rafiq.bridge.sessions.SessionManager
import ai.tatawur.rafiq.bridge.sessions.isTmuxSessionId
import ai.tatawur.rafiq.bridge.store.EventLog
import ai.tatawur.rafiq.bridge.store.SlowConsumerException
import ai.tatawur.rafiq.protocol.Event
import ai.tatawur.rafiq.protocol.api.Accepted
import ai.tatawur.rafiq.protocol.api.AgentInfo
import ai.tatawur.rafiq.protocol.api.ApiError
import ai.tatawur.rafiq.protocol.api.ApiErrorDetail
import ai.tatawur.rafiq.protocol.api.ApprovalAnswer
import ai.tatawur.rafiq.protocol.api.CreateSessionRequest
import ai.tatawur.rafiq.protocol.api.HookSetupRequest
import ai.tatawur.rafiq.protocol.api.PromptRequest
import ai.tatawur.rafiq.protocol.protocolJson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.CannotTransformContentToTypeException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/** Turns every failure into the one [ApiError] shape, so clients parse a single error format. */
internal fun io.ktor.server.application.Application.installErrorShape() {
    install(StatusPages) {
        exception<SessionException> { call, failure ->
            val status =
                when (failure) {
                    is SessionException.Invalid -> HttpStatusCode.BadRequest
                    is SessionException.NotFound -> HttpStatusCode.NotFound
                    is SessionException.Conflict -> HttpStatusCode.Conflict
                    is SessionException.Forbidden -> HttpStatusCode.Forbidden
                }
            call.respond(status, ApiError(ApiErrorDetail(failure.code, failure.message.orEmpty())))
        }
        exception<CannotTransformContentToTypeException> { call, _ ->
            call.respond(
                HttpStatusCode.UnsupportedMediaType,
                ApiError(ApiErrorDetail("unsupported_media_type", "Send the body as application/json.")),
            )
        }
        exception<UnsupportedMediaTypeException> { call, _ ->
            call.respond(
                HttpStatusCode.UnsupportedMediaType,
                ApiError(ApiErrorDetail("unsupported_media_type", "Send the body as application/json.")),
            )
        }
        exception<PayloadTooLargeException> { call, _ ->
            call.respond(
                HttpStatusCode.PayloadTooLarge,
                ApiError(ApiErrorDetail("payload_too_large", "The request body is too large.")),
            )
        }
        exception<BadRequestException> { call, _ -> call.respondInvalidBody() }
        exception<SerializationException> { call, _ -> call.respondInvalidBody() }
        // A cancelled call is a closed socket or a stopping bridge, not a failure.
        exception<CancellationException> { call, _ ->
            call.application.log.debug("Request cancelled: {}", call.request.local.uri)
        }
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

/** The most prompt text the bridge takes, in UTF-8 bytes. A longer prompt is answered 413. */
const val MAX_PROMPT_BYTES = 64 * 1024

/** The longest `client_id` the bridge takes. A longer one is answered 400. */
const val MAX_CLIENT_ID_CHARS = 64

/** A prompt is typed into a pane and kept in the log, so its size is bounded well below the body limit. */
private fun checkPromptSize(request: PromptRequest) {
    if (request.text.toByteArray(Charsets.UTF_8).size > MAX_PROMPT_BYTES) {
        throw PayloadTooLargeException(MAX_PROMPT_BYTES.toLong())
    }
    if ((request.clientId?.length ?: 0) > MAX_CLIENT_ID_CHARS) {
        throw SessionException.Invalid("invalid_client_id", "client_id is longer than $MAX_CLIENT_ID_CHARS characters.")
    }
}

/**
 * The session and agent routes. They stay thin: parsing here, the work in [SessionManager]. Every route
 * needs the device token (see [BridgeSecurity]). Each accepted prompt and approval answer gets an [audit] line
 * with the device it came from, which [deviceOf] names.
 */
internal fun Route.sessionRoutes(
    sessions: SessionManager,
    audit: AuditLog = LoggingAuditLog(),
    deviceOf: (ApplicationCall) -> String = { "unknown" },
) {
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
    get("/sessions/summaries") {
        call.respond(sessions.summaries())
    }
    get("/agents") {
        call.respond(sessions.agentIds.map(::AgentInfo))
    }
    get("/sessions/{id}/threads") {
        call.respond(sessions.threads(call.parameters.getOrFail("id")))
    }
    // Prompts go to the main thread. A thread_id naming a helper thread is rejected with invalid_thread.
    post("/sessions/{id}/prompt") {
        val request = call.receive<PromptRequest>()
        val sessionId = call.parameters.getOrFail("id")
        checkPromptSize(request)
        val device = deviceOf(call)
        val hash = LoggingAuditLog.sha256Hex(request.text)
        try {
            sessions.prompt(sessionId, request.threadId, request.text, request.clientId, device)
        } catch (refused: SessionException) {
            audit.chatPromptRefused(device, sessionId, refused.code, hash)
            throw refused
        }
        audit.chatPrompt(device, sessionId, hash)
        call.respond(HttpStatusCode.Accepted, Accepted())
    }
    post("/sessions/{id}/threads/{threadId}/cancel") {
        sessions.cancel(call.parameters.getOrFail("id"), call.parameters.getOrFail("threadId"))
        call.respond(HttpStatusCode.Accepted, Accepted())
    }
    post("/approvals/{id}") {
        val answer = call.receive<ApprovalAnswer>()
        val approvalId = call.parameters.getOrFail("id")
        val sessionId = sessions.respondToApproval(approvalId, answer.optionId)
        audit.approvalAnswer(deviceOf(call), sessionId, approvalId, answer.optionId)
        call.respond(HttpStatusCode.Accepted, Accepted())
    }
}

/**
 * `GET`, `POST` and `DELETE /sessions/{id}/hooks`: preview, install and remove the reader's hooks in the
 * session's project (issue #44). Only for sessions in tmux, whose agent runs in a project folder on your server.
 * The work is in [HookSetup]. Install and remove need the hash of the preview you agreed to, and each leaves an
 * [audit] line with the device, the project and the hash of the file written.
 */
internal fun Route.hookRoutes(
    hooks: HookSetup,
    sessions: SessionManager,
    deviceOf: (ApplicationCall) -> String,
    audit: AuditLog = LoggingAuditLog(),
) {
    suspend fun projectOf(call: ApplicationCall): String {
        val id = call.parameters.getOrFail("id")
        val session =
            sessions.sessions().firstOrNull { it.id == id }
                ?: throw SessionException.NotFound("session_not_found", "No session '$id'.")
        if (!isTmuxSessionId(id)) {
            throw SessionException.Conflict("hooks_unavailable", "Hooks are only for sessions in tmux.")
        }
        return session.projectPath
    }
    get("/sessions/{id}/hooks") {
        call.respond(hooks.preview(projectOf(call)))
    }
    post("/sessions/{id}/hooks") {
        val request = call.receive<HookSetupRequest>()
        val device = deviceOf(call)
        val change = hooks.install(projectOf(call), request, device)
        audit.hooksInstalled(device, change.preview.projectPath, change.writtenSha256 ?: "")
        call.respond(change.preview)
    }
    delete("/sessions/{id}/hooks") {
        val request = call.receive<HookSetupRequest>()
        val change = hooks.remove(projectOf(call), request)
        audit.hooksRemoved(deviceOf(call), change.preview.projectPath, change.writtenSha256)
        call.respond(change.preview)
    }
}

/**
 * `WS /events?session=<id>&after=<seq>`. Each text frame is one [Event] as JSON.
 *
 * A client that falls too far behind is closed with `TRY_AGAIN_LATER`. It reconnects with the last
 * `seq` it saw and replays what it missed.
 */
@Suppress("CyclomaticComplexMethod") // One handler: checks, registration, stream, watcher and cleanup.
internal fun Route.eventsRoute(
    log: EventLog,
    streamer: EventStreamer,
    isVisible: (sessionId: String) -> Boolean = { true },
    connections: ConnectionRegistry = InMemoryConnectionRegistry(),
    deviceOf: (ApplicationCall) -> String = { "unknown" },
) {
    webSocket("/events") {
        val sessionId = call.request.queryParameters["session"]
        val after = call.request.queryParameters["after"]?.let { it.toLongOrNull()?.takeIf { seq -> seq >= 0 } }
        val rejection =
            when {
                sessionId.isNullOrBlank() -> "session is required"
                call.request.queryParameters["after"] != null && after == null -> "after must be a non-negative integer"
                log.session(sessionId) == null || !isVisible(sessionId) -> "no such session"
                else -> null
            }
        if (rejection != null || sessionId == null) {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, rejection.orEmpty()))
            return@webSocket
        }
        var closeReason = CloseReason(CloseReason.Codes.NORMAL, "")
        // Revoking the device ends this socket at once. The stream below stops when the socket closes.
        val registration =
            connections.register(deviceOf(call), "events") { reason ->
                // A phone that stopped reading cannot hold the revoke up: after a second the socket is cancelled.
                val closed =
                    withTimeoutOrNull(
                        REVOKE_CLOSE_WAIT,
                    ) { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, reason)) }
                if (closed == null) cancel()
            }
        // A cancelled or dropped connection leaves this block by an exception, and must still leave the registry.
        try {
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
        } finally {
            registration.close()
        }
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

private val REVOKE_CLOSE_WAIT = 1.seconds
