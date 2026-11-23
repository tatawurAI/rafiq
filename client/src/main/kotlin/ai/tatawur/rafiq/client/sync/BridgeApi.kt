package ai.tatawur.rafiq.client.sync

import ai.tatawur.rafiq.client.CommandResult
import ai.tatawur.rafiq.protocol.Session
import ai.tatawur.rafiq.protocol.api.AgentInfo
import ai.tatawur.rafiq.protocol.api.ApiError
import ai.tatawur.rafiq.protocol.api.ApprovalAnswer
import ai.tatawur.rafiq.protocol.api.CreateSessionRequest
import ai.tatawur.rafiq.protocol.api.HookSetupPreview
import ai.tatawur.rafiq.protocol.api.HookSetupRequest
import ai.tatawur.rafiq.protocol.api.ProjectFolderListing
import ai.tatawur.rafiq.protocol.api.ProjectsOverview
import ai.tatawur.rafiq.protocol.api.PromptRequest
import ai.tatawur.rafiq.protocol.api.PushRegistration
import ai.tatawur.rafiq.protocol.api.SessionSummary
import ai.tatawur.rafiq.protocol.api.SetModeRequest
import ai.tatawur.rafiq.protocol.api.TerminalHistory
import ai.tatawur.rafiq.protocol.protocolJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import java.io.IOException

/**
 * The bridge's REST routes, with every answer mapped to a [CommandResult]. Screens branch on the
 * result's kind, so the status codes and error codes are read here and nowhere else.
 *
 * @param credentials the paired bridge at the moment of the call, or null when none is paired.
 */
@Suppress("TooManyFunctions") // One function per bridge route, so status codes are read in one place.
class BridgeApi(
    private val http: HttpTransport,
    private val credentials: () -> BridgeCredentials?,
) {
    /** `GET /sessions/summaries`. */
    suspend fun summaries(): CommandResult<List<SessionSummary>> =
        call(HttpMethod.GET, "/sessions/summaries", null, ListSerializer(SessionSummary.serializer()))

    /** `GET /agents`, as the agent ids. */
    suspend fun agents(): CommandResult<List<String>> =
        call(HttpMethod.GET, "/agents", null, ListSerializer(AgentInfo.serializer())).map { agents ->
            agents.map { it.id }
        }

    /** `POST /sessions`. A null [modeId] starts in the bridge's default mode for the agent. */
    suspend fun createSession(
        agent: String,
        projectPath: String,
        title: String?,
        modeId: String? = null,
    ): CommandResult<Session> =
        call(
            HttpMethod.POST,
            "/sessions",
            protocolJson.encodeToString(
                CreateSessionRequest.serializer(),
                CreateSessionRequest(agent, projectPath, title, modeId),
            ),
            Session.serializer(),
        )

    /** `POST /sessions/{id}/mode`. The change arrives as a `session_updated` event. */
    suspend fun setMode(
        sessionId: String,
        modeId: String,
    ): CommandResult<Unit> =
        command(
            "/sessions/${segment(sessionId)}/mode",
            protocolJson.encodeToString(SetModeRequest.serializer(), SetModeRequest(modeId)),
        )

    /** `GET /projects`: recent project folders and the roots you may browse. */
    suspend fun projects(): CommandResult<ProjectsOverview> =
        call(HttpMethod.GET, "/projects", null, ProjectsOverview.serializer())

    /** `GET /projects/folders`: the folders directly inside [path], which must be under a root. */
    suspend fun projectFolders(path: String): CommandResult<ProjectFolderListing> =
        call(HttpMethod.GET, "/projects/folders?path=${segment(path)}", null, ProjectFolderListing.serializer())

    /** `PUT /devices/me/push`: send this phone's pushes to [registration]. */
    suspend fun registerPush(registration: PushRegistration): CommandResult<Unit> =
        command(
            "/devices/me/push",
            protocolJson.encodeToString(PushRegistration.serializer(), registration),
            HttpMethod.PUT,
        )

    /** `DELETE /devices/me/push`: stop pushes to this phone. */
    suspend fun unregisterPush(): CommandResult<Unit> = command("/devices/me/push", "{}", HttpMethod.DELETE)

    /**
     * `POST /sessions/{id}/prompt`, always to the main thread. A session in tmux may refuse with 409 and one of
     * the [ai.tatawur.rafiq.protocol.api.SendRefusal] codes, which comes back as [CommandResult.Failed].
     */
    suspend fun prompt(
        sessionId: String,
        text: String,
        clientId: String? = null,
    ): CommandResult<Unit> =
        command(
            "/sessions/${segment(sessionId)}/prompt",
            protocolJson.encodeToString(
                PromptRequest.serializer(),
                PromptRequest(threadId = null, text = text, clientId = clientId),
            ),
        )

    /** `GET /sessions/{id}/hooks`: what installing or removing the reader's hooks would write. */
    suspend fun hookSetup(sessionId: String): CommandResult<HookSetupPreview> =
        call(HttpMethod.GET, "/sessions/${segment(sessionId)}/hooks", null, HookSetupPreview.serializer())

    /** `POST /sessions/{id}/hooks`. A stale [previewHash] fails with [HOOKS_FILE_CHANGED]. */
    suspend fun installHooks(
        sessionId: String,
        previewHash: String,
        addGitExclude: Boolean,
    ): CommandResult<Unit> =
        command(
            "/sessions/${segment(sessionId)}/hooks",
            protocolJson.encodeToString(HookSetupRequest.serializer(), HookSetupRequest(previewHash, addGitExclude)),
        )

    /** `DELETE /sessions/{id}/hooks`. A stale [previewHash] fails with [HOOKS_FILE_CHANGED]. */
    suspend fun removeHooks(
        sessionId: String,
        previewHash: String,
    ): CommandResult<Unit> =
        command(
            "/sessions/${segment(sessionId)}/hooks",
            protocolJson.encodeToString(HookSetupRequest.serializer(), HookSetupRequest(previewHash)),
            HttpMethod.DELETE,
        )

    /**
     * `GET /terminal/history`: read-only scrollback of the pane in [window] of the session, or of the window
     * the bridge shows when [window] is null.
     */
    suspend fun terminalHistory(
        sessionId: String,
        window: Int?,
        lines: Int,
    ): CommandResult<TerminalHistory> {
        val windowParam = window?.let { "&window=$it" }.orEmpty()
        return call(
            HttpMethod.GET,
            "/terminal/history?session=${segment(sessionId)}$windowParam&lines=$lines",
            null,
            TerminalHistory.serializer(),
        )
    }

    /** `POST /sessions/{id}/threads/{threadId}/cancel`. */
    suspend fun cancel(
        sessionId: String,
        threadId: String,
    ): CommandResult<Unit> = command("/sessions/${segment(sessionId)}/threads/${segment(threadId)}/cancel", "{}")

    /** `POST /approvals/{id}`. */
    suspend fun approve(
        approvalId: String,
        optionId: String,
    ): CommandResult<Unit> =
        command(
            "/approvals/${segment(approvalId)}",
            protocolJson.encodeToString(ApprovalAnswer.serializer(), ApprovalAnswer(optionId)),
        )

    private suspend fun command(
        path: String,
        body: String,
        method: HttpMethod = HttpMethod.POST,
    ): CommandResult<Unit> =
        when (val result = send(method, path, body)) {
            is Sent.Answered -> {
                if (result.response.status in SUCCESS) CommandResult.Ok(Unit) else failure(result.response)
            }

            is Sent.NotSent -> {
                result.reason
            }
        }

    private suspend fun <T> call(
        method: HttpMethod,
        path: String,
        body: String?,
        serializer: KSerializer<T>,
    ): CommandResult<T> =
        when (val result = send(method, path, body)) {
            is Sent.Answered -> {
                if (result.response.status in
                    SUCCESS
                ) {
                    decode(result.response.body, serializer)
                } else {
                    failure(result.response)
                }
            }

            is Sent.NotSent -> {
                result.reason
            }
        }

    private suspend fun send(
        method: HttpMethod,
        path: String,
        body: String?,
    ): Sent {
        val paired = credentials() ?: return Sent.NotSent(CommandResult.Unauthorized)
        return try {
            Sent.Answered(http.execute(HttpRequest(method, paired.baseUrl + path, paired.token, body)))
        } catch (_: IOException) {
            Sent.NotSent(CommandResult.Offline)
        }
    }

    private sealed interface Sent {
        data class Answered(
            val response: HttpResponse,
        ) : Sent

        data class NotSent(
            val reason: CommandResult<Nothing>,
        ) : Sent
    }

    companion object {
        /** The error code of a hook install or remove whose preview is out of date. */
        const val HOOKS_FILE_CHANGED = "hooks_file_changed"

        private val SUCCESS = 200..299
        private const val UNAUTHORIZED = 401
        private const val NOT_FOUND = 404
        private const val CONFLICT = 409
        private const val TOO_MANY_REQUESTS = 429
        private const val MILLIS_PER_SECOND = 1_000L

        private fun <T> decode(
            body: String,
            serializer: KSerializer<T>,
        ): CommandResult<T> =
            try {
                CommandResult.Ok(protocolJson.decodeFromString(serializer, body))
            } catch (_: IllegalArgumentException) {
                // SerializationException is an IllegalArgumentException.
                CommandResult.Failed("invalid_response", "The bridge sent an answer this app cannot read.")
            }

        private fun failure(response: HttpResponse): CommandResult<Nothing> {
            val error =
                try {
                    protocolJson.decodeFromString(ApiError.serializer(), response.body).error
                } catch (_: IllegalArgumentException) {
                    null
                }
            return when {
                response.status == UNAUTHORIZED -> {
                    CommandResult.Unauthorized
                }

                response.status == NOT_FOUND -> {
                    CommandResult.NotFound
                }

                response.status == TOO_MANY_REQUESTS -> {
                    CommandResult.RateLimited(response.retryAfterSeconds?.let { it * MILLIS_PER_SECOND })
                }

                response.status == CONFLICT && error?.code == "approval_gone" -> {
                    CommandResult.Gone
                }

                response.status == CONFLICT && error?.code == "turn_in_progress" -> {
                    CommandResult.Busy
                }

                else -> {
                    CommandResult.Failed(
                        error?.code ?: "http_${response.status}",
                        error?.message ?: "The bridge answered with HTTP ${response.status}.",
                    )
                }
            }
        }

        private inline fun <T, R> CommandResult<T>.map(transform: (T) -> R): CommandResult<R> =
            when (this) {
                is CommandResult.Ok -> CommandResult.Ok(transform(value))
                CommandResult.Busy -> CommandResult.Busy
                is CommandResult.RateLimited -> this
                CommandResult.Gone -> CommandResult.Gone
                CommandResult.NotFound -> CommandResult.NotFound
                CommandResult.Offline -> CommandResult.Offline
                CommandResult.Unauthorized -> CommandResult.Unauthorized
                is CommandResult.Failed -> this
            }
    }
}

/** Percent-encodes one path segment. Identifiers are opaque, so any character may need it. */
internal fun segment(value: String): String =
    buildString {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val char = (byte.toInt() and BYTE_MASK).toChar()
            if ((char.isLetterOrDigit() && char.code < ASCII_LIMIT) || char in "-._~") {
                append(char)
            } else {
                append('%').append("%02X".format(byte.toInt() and BYTE_MASK))
            }
        }
    }

private const val BYTE_MASK = 0xFF
private const val ASCII_LIMIT = 128
