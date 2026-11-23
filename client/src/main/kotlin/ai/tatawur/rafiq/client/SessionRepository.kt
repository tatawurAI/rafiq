package ai.tatawur.rafiq.client

import ai.tatawur.rafiq.protocol.Session
import ai.tatawur.rafiq.protocol.api.HookSetupPreview
import ai.tatawur.rafiq.protocol.api.ProjectFolderListing
import ai.tatawur.rafiq.protocol.api.ProjectsOverview
import ai.tatawur.rafiq.protocol.api.TerminalHistory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the screens use to read and steer sessions. The app's data layer implements it over Room,
 * OkHttp and the bridge's routes. Screens never talk to the network or the cache directly.
 *
 * Every flow starts with the cached state, so the screens show the last known state with no connection.
 */
@Suppress("TooManyFunctions") // The screens' one door to the bridge: each function is one route.
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

    /**
     * Starts a session. A null [title] lets the bridge name it. A null [modeId] starts it in your server's
     * default permission mode for the agent.
     */
    suspend fun createSession(
        agent: String,
        projectPath: String,
        title: String?,
        modeId: String? = null,
    ): CommandResult<Session>

    /** Switches the session's permission mode. The new mode arrives as a `session_updated` event. */
    suspend fun setMode(
        sessionId: String,
        modeId: String,
    ): CommandResult<Unit>

    /** Recent project folders on your server and the roots you may browse, for "New session". */
    suspend fun projects(): CommandResult<ProjectsOverview>

    /**
     * The folders directly inside [path] on your server. A path outside the roots fails with
     * `outside_project_roots`, and one that is not a folder comes back as [CommandResult.NotFound].
     */
    suspend fun projectFolders(path: String): CommandResult<ProjectFolderListing>

    /**
     * Sends a prompt to the session's main thread. The bridge logs it as a user message, so the
     * screen shows it when that event arrives. For a session in tmux the bridge also sends
     * `prompt_delivery` events carrying [clientId], until the agent has the prompt.
     */
    suspend fun prompt(
        sessionId: String,
        text: String,
        clientId: String? = null,
    ): CommandResult<Unit>

    /** What installing or removing the reader's hooks in the session's project would write. Writes nothing. */
    suspend fun hookSetup(sessionId: String): CommandResult<HookSetupPreview>

    /**
     * Installs the reader's hooks in the session's project, exactly as the preview with [previewHash] showed.
     * Called only after you tap to agree.
     */
    suspend fun installHooks(
        sessionId: String,
        previewHash: String,
        addGitExclude: Boolean,
    ): CommandResult<Unit>

    /** Removes the reader's hooks from the session's project, as the preview with [previewHash] showed. */
    suspend fun removeHooks(
        sessionId: String,
        previewHash: String,
    ): CommandResult<Unit>

    /**
     * Read-only scrollback of the pane in tmux window [window] of the session, at most [lines] lines.
     * Reading it never changes the window, its size or what the desk sees.
     */
    suspend fun terminalHistory(
        sessionId: String,
        window: Int?,
        lines: Int,
    ): CommandResult<TerminalHistory>

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

    /**
     * Your server accepts only its owner's Tailscale login, and this phone reached it signed in as someone
     * else. The pairing and token stay: signing the phone in to your own tailnet fixes it.
     */
    data object IdentityRefused : ConnectionState
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

    /**
     * Your server is rate limiting this kind of call (429).
     *
     * @property retryAfterMs how long your server asked the app to wait, or null when it did not say.
     */
    data class RateLimited(
        val retryAfterMs: Long?,
    ) : CommandResult<Nothing>

    /** Any other error, with the bridge's error code and message. */
    data class Failed(
        val code: String,
        val message: String,
    ) : CommandResult<Nothing>
}

/** This result without its value, for a caller that keeps only what went wrong. An unexpected Ok becomes Failed. */
internal fun CommandResult<*>.withoutValue(): CommandResult<Nothing> =
    when (this) {
        is CommandResult.Ok -> CommandResult.Failed("unexpected", "Your server answered in an unexpected way.")
        CommandResult.Busy -> CommandResult.Busy
        is CommandResult.RateLimited -> this
        CommandResult.Gone -> CommandResult.Gone
        CommandResult.NotFound -> CommandResult.NotFound
        CommandResult.Offline -> CommandResult.Offline
        CommandResult.Unauthorized -> CommandResult.Unauthorized
        is CommandResult.Failed -> this
    }
