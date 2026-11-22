package ai.tatawur.rafiq.bridge.tmux

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** tmux could not be run, or it failed in a way that is not "no server is running". */
class TmuxException(
    message: String,
) : Exception(message)

/** One tmux session, by the id tmux gave it and the name you gave it. */
data class TmuxSession(
    val id: String,
    val name: String,
    /** How many clients are attached to it right now. */
    val attached: Int = 0,
)

/** One pane of a tmux session. */
data class PaneInfo(
    val sessionName: String,
    val paneId: String,
    val panePid: Int,
    val path: String,
    /** True for the pane the session shows by default: the active pane of its active window. */
    val isCurrent: Boolean,
)

/**
 * The tmux server the bridge talks to: the default server, or the one named by [socket] (`tmux -L`).
 *
 * Every tmux process the bridge starts goes through here, so the socket choice is made in one place. The
 * `TMUX` variable is removed from their environment. It would otherwise point tmux at the server the bridge
 * itself happens to run inside, whatever [socket] says.
 *
 * @param binary the tmux program. Tests point it at a script to see which commands run.
 */
class TmuxServer(
    val socket: String? = null,
    private val binary: String = "tmux",
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** The command line for tmux with [args], socket included. */
    fun commandLine(vararg args: String): List<String> =
        listOf(binary) + (socket?.let { listOf("-L", it) } ?: emptyList()) + args

    /** Starts a tmux process that stays open, such as a control-mode client. */
    fun start(vararg args: String): Process =
        ProcessBuilder(commandLine(*args))
            .redirectErrorStream(true)
            .also(::cleanEnvironment)
            .start()

    /** The tmux version as (major, minor), or null when tmux is missing or prints something unexpected. */
    suspend fun version(): Pair<Int, Int>? {
        val text = run("-V")?.takeIf { it.exit == 0 }?.out ?: return null
        return VERSION.find(text)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
    }

    /**
     * Every session on the server. Empty when there is no server running, which is what a server with no
     * sessions left looks like.
     *
     * @throws TmuxException when tmux cannot be run or fails for another reason.
     */
    suspend fun sessions(): List<TmuxSession> =
        listing("list-sessions", "-F", "#{session_id}\t#{session_attached}\t#{session_name}")
            .mapNotNull { line ->
                val parts = line.split('\t', limit = SESSION_FIELDS)
                if (parts.size == SESSION_FIELDS && parts[0].startsWith("$")) {
                    TmuxSession(parts[0], parts[2], parts[ATTACHED].toIntOrNull() ?: 0)
                } else {
                    null
                }
            }

    /** The session whose name is exactly [name]. A name that merely starts with it does not count. */
    suspend fun session(name: String): TmuxSession? = sessions().firstOrNull { it.name == name }

    /** Every pane of every session. Empty when there is no server running. @throws TmuxException as [sessions]. */
    suspend fun panes(): List<PaneInfo> =
        listing(
            "list-panes",
            "-a",
            "-F",
            "#{session_name}\t#{pane_id}\t#{pane_pid}\t#{window_active}\t#{pane_active}\t#{pane_current_path}",
        ).mapNotNull { line ->
            val parts = line.split('\t', limit = PANE_FIELDS)
            val pid = parts.getOrNull(PID)?.toIntOrNull()
            if (parts.size == PANE_FIELDS && pid != null) {
                PaneInfo(parts[0], parts[1], pid, parts[PATH], parts[WINDOW_ACTIVE] == "1" && parts[PANE_ACTIVE] == "1")
            } else {
                null
            }
        }

    /**
     * Ends the view sessions an earlier bridge left behind. A leftover has a name this bridge gives its views
     * (`rafiq-view-` and an id) and no client attached. A view another bridge is using has a client, and a
     * session of your own with a similar name does not match the pattern, so neither is touched. Only this
     * server is looked at.
     */
    suspend fun killLeftoverViews() {
        for (session in sessions().filter { it.attached == 0 && LEFTOVER_VIEW.matches(it.name) }) {
            run("kill-session", "-t", session.id)
        }
    }

    /** Runs one tmux command. Null when the program could not be started or was killed for hanging. */
    private suspend fun run(vararg args: String): ProcessResult? =
        withContext(io) { runCapturing(commandLine(*args), EXEC_TIMEOUT_SECONDS) }

    private suspend fun listing(vararg args: String): List<String> {
        val result = run(*args) ?: throw TmuxException("tmux could not be run.")
        if (result.exit != 0) {
            if (NO_SERVER.any { it in result.err }) return emptyList()
            throw TmuxException("tmux ${args.first()} failed: ${result.err.trim()}")
        }
        return result.out.lines().filter(String::isNotBlank)
    }

    companion object {
        /** Name prefix of the sessions the bridge makes to view a pane. Never listed, never opened. */
        const val VIEW_PREFIX = "rafiq-view-"

        /** The oldest tmux the terminal view works with, for `pause-after`. */
        val MINIMUM_VERSION = 3 to 2

        /** True when [version], as major and minor, is at least [MINIMUM_VERSION]. */
        fun isSupported(version: Pair<Int, Int>): Boolean =
            version.first > MINIMUM_VERSION.first ||
                (version.first == MINIMUM_VERSION.first && version.second >= MINIMUM_VERSION.second)

        /** `rafiq-view-<process nonce>-<id>`, or the older `rafiq-view-<id>`. */
        private val LEFTOVER_VIEW = Regex("rafiq-view-([0-9a-f]{6}-)?[0-9a-f]{8}")

        private const val EXEC_TIMEOUT_SECONDS = 5L
        private const val PANE_FIELDS = 6
        private const val SESSION_FIELDS = 3
        private const val ATTACHED = 1
        private const val PID = 2
        private const val WINDOW_ACTIVE = 3
        private const val PANE_ACTIVE = 4
        private const val PATH = 5
        private val VERSION = Regex("(\\d+)\\.(\\d+)")

        /** What tmux says when no server is running on the socket: none was started, or its last session ended. */
        private val NO_SERVER = listOf("no server running", "error connecting to")
    }
}
