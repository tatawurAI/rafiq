package ai.tatawur.rafiq.client.sync

import ai.tatawur.rafiq.client.CommandResult
import ai.tatawur.rafiq.protocol.Session
import ai.tatawur.rafiq.protocol.api.AgentInfo
import ai.tatawur.rafiq.protocol.api.ApiError
import ai.tatawur.rafiq.protocol.api.ApprovalAnswer
import ai.tatawur.rafiq.protocol.api.CreateSessionRequest
import ai.tatawur.rafiq.protocol.api.PromptRequest
import ai.tatawur.rafiq.protocol.api.SessionSummary
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

    /** `POST /sessions`. */
    suspend fun createSession(
        agent: String,
        projectPath: String,
        title: String?,
    ): CommandResult<Session> =
        call(
            HttpMethod.POST,
            "/sessions",
            protocolJson.encodeToString(
                CreateSessionRequest.serializer(),
                CreateSessionRequest(agent, projectPath, title),
            ),
            Session.serializer(),
        )

    /** `POST /sessions/{id}/prompt`, always to the main thread. */
    suspend fun prompt(
        sessionId: String,
        text: String,
    ): CommandResult<Unit> =
        command(
            "/sessions/${segment(sessionId)}/prompt",
            protocolJson.encodeToString(PromptRequest.serializer(), PromptRequest(threadId = null, text = text)),
        )

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
    ): CommandResult<Unit> =
        when (val result = send(HttpMethod.POST, path, body)) {
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

    private companion object {
        val SUCCESS = 200..299
        const val UNAUTHORIZED = 401
        const val NOT_FOUND = 404
        const val CONFLICT = 409

        fun <T> decode(
            body: String,
            serializer: KSerializer<T>,
        ): CommandResult<T> =
            try {
                CommandResult.Ok(protocolJson.decodeFromString(serializer, body))
            } catch (_: IllegalArgumentException) {
                // SerializationException is an IllegalArgumentException.
                CommandResult.Failed("invalid_response", "The bridge sent an answer this app cannot read.")
            }

        fun failure(response: HttpResponse): CommandResult<Nothing> {
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

        inline fun <T, R> CommandResult<T>.map(transform: (T) -> R): CommandResult<R> =
            when (this) {
                is CommandResult.Ok -> CommandResult.Ok(transform(value))
                CommandResult.Busy -> CommandResult.Busy
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
