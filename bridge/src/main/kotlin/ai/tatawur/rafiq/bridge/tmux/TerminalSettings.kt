package ai.tatawur.rafiq.bridge.tmux

import java.net.InetAddress
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * The tmux gate: whether the bridge shows tmux sessions, whether the phone may open them in Terminal, and which.
 *
 * Listing a session lets the phone chat with the agent in it. Terminal is a remote shell on your server, so it
 * is a separate switch, and a session with no agent in it opens only when it is named a second time. Everything
 * here defaults to off and to the narrowest choice.
 *
 * @property enabled whether tmux sessions are listed at all, from `RAFIQ_TMUX=1` or `RAFIQ_TERMINAL=1`. While
 *   false the bridge lists no tmux session, closes `WS /terminal` as "no such session" and never starts tmux.
 * @property sessions from `RAFIQ_TMUX_SESSIONS`: the exact names of the tmux sessions the phone may see. A grant
 *   covers every window and pane in the session.
 * @property socket from `RAFIQ_TMUX_SOCKET`: the tmux socket name (`tmux -L`), or null for the default server.
 * @property terminal from `RAFIQ_TERMINAL=1`: whether a listed session may be opened in Terminal.
 * @property shellSessions from `RAFIQ_TERMINAL_SHELLS`: listed sessions that open in Terminal even when no agent
 *   is detected in them. A session with an agent needs no entry here.
 */
data class TerminalSettings(
    val enabled: Boolean = false,
    val sessions: Set<String> = emptySet(),
    val socket: String? = null,
    val limits: TerminalLimits = TerminalLimits(),
    val terminal: Boolean = false,
    val shellSessions: Set<String> = emptySet(),
) {
    /** True when the phone may see the tmux session called [name]. Exact match only. */
    fun allows(name: String): Boolean = enabled && name in sessions && !name.startsWith(TmuxServer.VIEW_PREFIX)

    /**
     * What the phone may do with the tmux session called [name], given whether an agent was detected in it.
     * The session list sets `Session.terminalAllowed` from [TmuxAccess.terminal], and `WS /terminal` refuses
     * a session for which it is false before it runs any tmux command.
     */
    fun access(
        name: String,
        agentDetected: Boolean,
    ): TmuxAccess {
        val listed = allows(name)
        return TmuxAccess(listed = listed, terminal = listed && terminal && (agentDetected || name in shellSessions))
    }

    companion object {
        val OFF = TerminalSettings()

        const val LISTING = "RAFIQ_TMUX"
        const val SWITCH = "RAFIQ_TERMINAL"
        const val SESSIONS = "RAFIQ_TMUX_SESSIONS"
        const val SHELLS = "RAFIQ_TERMINAL_SHELLS"
        const val SOCKET = "RAFIQ_TMUX_SOCKET"
        const val ALLOW_REMOTE_BIND = "RAFIQ_TERMINAL_ALLOW_NON_LOOPBACK"

        /** A session or socket name: letters, digits, dot, underscore and hyphen. No `*`, no space, no `;`. */
        private val NAME = Regex("[A-Za-z0-9._-]+")

        /**
         * Reads the gate from [read], which returns a variable's trimmed value or null when unset or blank.
         * `RAFIQ_TERMINAL=1` implies `RAFIQ_TMUX=1`.
         *
         * @throws IllegalArgumentException with the variable's name when a value is bad, so a typo fails at
         *   start-up instead of quietly widening or narrowing what the phone can reach. Also when tmux sessions
         *   are on and the bridge is bound to an address other than loopback, unless the override is set.
         */
        fun fromEnvironment(
            read: (String) -> String?,
            bindAddress: String,
        ): TerminalSettings {
            val terminal = flag(SWITCH, read(SWITCH))
            val listing = flag(LISTING, read(LISTING)) || terminal
            val sessions = read(SESSIONS)?.let { parseNames(SESSIONS, it) }.orEmpty()
            val shells = read(SHELLS)?.let { parseNames(SHELLS, it) }.orEmpty()
            val socket = read(SOCKET)
            require(socket == null || NAME.matches(socket)) {
                "$SOCKET must be a tmux socket name of letters, digits, '.', '_' and '-', got '$socket'."
            }
            val stray = shells - sessions
            require(stray.isEmpty()) {
                "$SHELLS names '${stray.first()}', which is not in $SESSIONS. A session must be listed to open."
            }
            if (!listing) return OFF.copy(sessions = sessions, socket = socket, shellSessions = shells)
            val switch = if (terminal) SWITCH else LISTING
            require(sessions.isNotEmpty()) {
                "$switch is on, so $SESSIONS must name the tmux sessions the phone may see. There is no wildcard."
            }
            require(isLoopback(bindAddress) || flag(ALLOW_REMOTE_BIND, read(ALLOW_REMOTE_BIND))) {
                "$switch is on, so RAFIQ_BIND_ADDRESS must be a loopback address, got '$bindAddress'. " +
                    "Publish the bridge with tailscale serve. To bind elsewhere on purpose, set $ALLOW_REMOTE_BIND=1."
            }
            return TerminalSettings(
                enabled = true,
                sessions = sessions,
                socket = socket,
                terminal = terminal,
                shellSessions = shells,
            )
        }

        private fun parseNames(
            variable: String,
            raw: String,
        ): Set<String> =
            raw
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .onEach { name ->
                    require(NAME.matches(name)) {
                        "$variable entries must be exact tmux session names of letters, digits, '.', '_' and '-', " +
                            "got '$name'. A wildcard is not allowed."
                    }
                }.toSet()

        private fun flag(
            name: String,
            raw: String?,
        ): Boolean =
            when (raw) {
                null, "0" -> false
                "1" -> true
                else -> throw IllegalArgumentException("$name must be 1 or 0, got '$raw'.")
            }

        /** True for `localhost` and for an IP literal in the loopback range. Names are never resolved. */
        internal fun isLoopback(address: String): Boolean {
            val host = address.removeSurrounding("[", "]").lowercase()
            if (host == "localhost") return true
            val isLiteral = host.contains(':') || host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))
            return isLiteral && runCatching { InetAddress.getByName(host).isLoopbackAddress }.getOrDefault(false)
        }
    }
}

/**
 * What the phone may do with one tmux session.
 *
 * @property listed the session shows in the session list, and the phone may chat with its agent.
 * @property terminal the phone may also open it in Terminal. Never true when [listed] is false.
 */
data class TmuxAccess(
    val listed: Boolean,
    val terminal: Boolean,
)

/**
 * The limits on terminal sockets. Fixed in code on purpose: a setting that raises them is a setting someone
 * can get wrong. Tests pass smaller ones.
 *
 * @property maxPerDevice sockets one device may hold open at once.
 * @property maxTotal sockets all devices together may hold open.
 * @property minCols the clamp on the phone's width in columns, on the first resize and on every later one.
 * @property maxFrameBytes the largest binary frame of key bytes the bridge accepts.
 * @property maxInputBytesPerSecond how much input one socket may send per second, a paste included.
 * @property idleTimeout how long a socket may go without the phone sending a frame, or output reaching it, before it
 *   is closed.
 * @property pauseAfterSeconds how long tmux lets this client fall behind before it pauses the pane. A pause
 *   is answered with a fresh snapshot.
 * @property opensBurst how many terminals a device may open at once, before [opensPerMinute] applies.
 * @property maxAge how long one terminal socket may stay open, whatever it is doing.
 * @property abuseResizes how many size changes in 10 s count as abuse and end the socket. Fewer are coalesced.
 * @property resizeBurst how many size changes a socket may send at once, before [resizesPerSecond] applies.
 * @property maxPendingOutputBytes how much pane output may wait for a slow phone before the bridge drops it
 *   and sends a fresh screen instead.
 */
data class TerminalLimits(
    val maxPerDevice: Int = 2,
    val maxTotal: Int = 4,
    val minCols: Int = DEFAULT_MIN_COLS,
    val maxCols: Int = DEFAULT_MAX_COLS,
    val minRows: Int = DEFAULT_MIN_ROWS,
    val maxRows: Int = DEFAULT_MAX_ROWS,
    val maxFrameBytes: Int = DEFAULT_FRAME_BYTES,
    val maxInputBytesPerSecond: Int = DEFAULT_INPUT_BYTES_PER_SECOND,
    val idleTimeout: Duration = DEFAULT_IDLE_TIMEOUT,
    val maxPendingOutputBytes: Int = DEFAULT_PENDING_OUTPUT_BYTES,
    val pauseAfterSeconds: Int = DEFAULT_PAUSE_AFTER_SECONDS,
    val opensBurst: Int = DEFAULT_OPENS_BURST,
    val opensPerMinute: Double = DEFAULT_OPENS_PER_MINUTE,
    val maxAge: Duration = DEFAULT_MAX_AGE,
    val abuseResizes: Int = DEFAULT_ABUSE_RESIZES,
    val resizeBurst: Int = DEFAULT_RESIZE_BURST,
    val resizesPerSecond: Double = DEFAULT_RESIZES_PER_SECOND,
) {
    fun clampCols(cols: Int): Int = cols.coerceIn(minCols, maxCols)

    fun clampRows(rows: Int): Int = rows.coerceIn(minRows, maxRows)

    private companion object {
        const val DEFAULT_PAUSE_AFTER_SECONDS = 2
        const val DEFAULT_RESIZE_BURST = 10
        const val DEFAULT_RESIZES_PER_SECOND = 5.0
        const val DEFAULT_OPENS_BURST = 5
        const val DEFAULT_OPENS_PER_MINUTE = 5.0
        const val DEFAULT_ABUSE_RESIZES = 60
        val DEFAULT_MAX_AGE: Duration = 8.hours
        const val DEFAULT_MIN_COLS = 20
        const val DEFAULT_MAX_COLS = 500
        const val DEFAULT_MIN_ROWS = 5
        const val DEFAULT_MAX_ROWS = 200
        const val DEFAULT_FRAME_BYTES = 16 * 1024
        const val DEFAULT_INPUT_BYTES_PER_SECOND = 64 * 1024
        const val DEFAULT_PENDING_OUTPUT_BYTES = 4 * 1024 * 1024
        val DEFAULT_IDLE_TIMEOUT: Duration = 10.minutes
    }
}
