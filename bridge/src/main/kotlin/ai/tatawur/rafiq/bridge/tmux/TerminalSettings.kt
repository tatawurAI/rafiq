package ai.tatawur.rafiq.bridge.tmux

import java.net.InetAddress
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The Terminal gate: whether the bridge shows tmux sessions and lets the phone open them, and which ones.
 *
 * Terminal is a remote shell on your server, so everything here defaults to off and to the narrowest choice.
 *
 * @property enabled from `RAFIQ_TERMINAL=1`. While false the bridge lists no tmux session, closes
 *   `WS /terminal` as "no such session" and never starts tmux.
 * @property sessions from `RAFIQ_TMUX_SESSIONS`: the exact names of the tmux sessions the phone may see and
 *   open. A grant covers every window and pane in the session.
 * @property socket from `RAFIQ_TMUX_SOCKET`: the tmux socket name (`tmux -L`), or null for the default server.
 */
data class TerminalSettings(
    val enabled: Boolean = false,
    val sessions: Set<String> = emptySet(),
    val socket: String? = null,
    val limits: TerminalLimits = TerminalLimits(),
) {
    /** True when the phone may see and open the tmux session called [name]. Exact match only. */
    fun allows(name: String): Boolean = enabled && name in sessions && !name.startsWith(TmuxServer.VIEW_PREFIX)

    companion object {
        val OFF = TerminalSettings()

        const val SWITCH = "RAFIQ_TERMINAL"
        const val SESSIONS = "RAFIQ_TMUX_SESSIONS"
        const val SOCKET = "RAFIQ_TMUX_SOCKET"
        const val ALLOW_REMOTE_BIND = "RAFIQ_TERMINAL_ALLOW_NON_LOOPBACK"

        /** A session or socket name: letters, digits, dot, underscore and hyphen. No `*`, no space, no `;`. */
        private val NAME = Regex("[A-Za-z0-9._-]+")

        /**
         * Reads the gate from [read], which returns a variable's trimmed value or null when unset or blank.
         *
         * @throws IllegalArgumentException with the variable's name when a value is bad, so a typo fails at
         *   start-up instead of quietly widening or narrowing what the phone can reach. Also when Terminal is
         *   on and the bridge is bound to an address other than loopback, unless the override is set.
         */
        fun fromEnvironment(
            read: (String) -> String?,
            bindAddress: String,
        ): TerminalSettings {
            val enabled = flag(SWITCH, read(SWITCH))
            val sessions = read(SESSIONS)?.let(::parseSessions).orEmpty()
            val socket = read(SOCKET)
            require(socket == null || NAME.matches(socket)) {
                "$SOCKET must be a tmux socket name of letters, digits, '.', '_' and '-', got '$socket'."
            }
            if (!enabled) return OFF.copy(sessions = sessions, socket = socket)
            require(sessions.isNotEmpty()) {
                "$SWITCH is on, so $SESSIONS must name the tmux sessions the phone may open. Terminal has no wildcard."
            }
            require(isLoopback(bindAddress) || flag(ALLOW_REMOTE_BIND, read(ALLOW_REMOTE_BIND))) {
                "$SWITCH is on, so RAFIQ_BIND_ADDRESS must be a loopback address, got '$bindAddress'. " +
                    "Publish the bridge with tailscale serve. To bind elsewhere on purpose, set $ALLOW_REMOTE_BIND=1."
            }
            return TerminalSettings(enabled = true, sessions = sessions, socket = socket)
        }

        private fun parseSessions(raw: String): Set<String> =
            raw
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .onEach { name ->
                    require(NAME.matches(name)) {
                        "$SESSIONS entries must be exact tmux session names of letters, digits, '.', '_' and '-', " +
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
 * The limits on terminal sockets. Fixed in code on purpose: a setting that raises them is a setting someone
 * can get wrong. Tests pass smaller ones.
 *
 * @property maxPerDevice sockets one device may hold open at once.
 * @property maxTotal sockets all devices together may hold open.
 * @property minCols the clamp on the phone's width in columns, on the first resize and on every later one.
 * @property maxFrameBytes the largest binary frame of key bytes the bridge accepts.
 * @property maxInputBytesPerSecond how much input one socket may send per second, a paste included.
 * @property idleTimeout how long a socket may go without the phone sending a frame before it is closed.
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
) {
    fun clampCols(cols: Int): Int = cols.coerceIn(minCols, maxCols)

    fun clampRows(rows: Int): Int = rows.coerceIn(minRows, maxRows)

    private companion object {
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
