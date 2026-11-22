package ai.tatawur.rafiq.client

import ai.tatawur.rafiq.protocol.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the screens use to read and steer sessions. The app's data layer implements it over Room,
 * OkHttp and the bridge's routes. Screens never talk to the network or the cache directly.
 *
 * Every flow starts with the cached state, so the screens show the last known state with no connection.
 */
interface SessionRepository {
    /** The link to the paired bridge. */
    val connection: StateFlow<ConnectionState>

    /** All sessions, newest update first. Refreshed from `GET /sessions/summaries` while collected. */
    fun sessions(): Flow<List<SessionListItem>>

    /**
     * One session's state. While collected, the repository keeps that session's socket open, resumes
     * it after the cached `lastSeq` on every reconnect, and caches each event before applying it.
     */
    fun session(sessionId: String): Flow<SessionScreenState>

    /** The agents the bridge can start, for "New session". */
    suspend fun agents(): CommandResult<List<String>>

    /** Starts a session. A null [title] lets the bridge name it. */
    suspend fun createSession(
        agent: String,
        projectPath: String,
        title: String?,
    ): CommandResult<Session>

    /**
     * Sends a prompt to the session's main thread. The bridge logs it as a user message, so the
     * screen shows it when that event arrives. There is no local echo.
     */
    suspend fun prompt(
        sessionId: String,
        text: String,
    ): CommandResult<Unit>

    /** Stops the current turn on a thread. */
    suspend fun cancel(
        sessionId: String,
        threadId: String,
    ): CommandResult<Unit>

    /**
     * Answers an approval with one of its option ids. The card stays until `approval_resolved`
     * arrives, so a lost answer is never shown as answered.
     */
    suspend fun approve(
        approvalId: String,
        optionId: String,
    ): CommandResult<Unit>
}

/** The state of the app's link to the paired bridge. */
sealed interface ConnectionState {
    /** No bridge is paired yet. */
    data object NotPaired : ConnectionState

    data object Connecting : ConnectionState

    /** The socket is open and streaming. */
    data object Live : ConnectionState

    /**
     * The bridge cannot be reached. The screens show cached state.
     *
     * @property retryAtMs when the next attempt starts, in milliseconds since the Unix epoch, or null
     *   when the app waits for the network to come back.
     */
    data class Offline(
        val retryAtMs: Long?,
    ) : ConnectionState

    /** The bridge refused the device token. The user has to pair again. */
    data object Unauthorized : ConnectionState
}

/** The outcome of a call to the bridge. Screens branch on the kind, never on message text. */
sealed interface CommandResult<out T> {
    data class Ok<T>(
        val value: T,
    ) : CommandResult<T>

    /** A turn is already running (409). */
    data object Busy : CommandResult<Nothing>

    /** The approval was already answered or went away (409 `approval_gone`). */
    data object Gone : CommandResult<Nothing>

    data object NotFound : CommandResult<Nothing>

    /** No connection. Nothing was sent. */
    data object Offline : CommandResult<Nothing>

    /** The bridge refused the device token (401). */
    data object Unauthorized : CommandResult<Nothing>

    /** Any other error, with the bridge's error code and message. */
    data class Failed(
        val code: String,
        val message: String,
    ) : CommandResult<Nothing>
}
