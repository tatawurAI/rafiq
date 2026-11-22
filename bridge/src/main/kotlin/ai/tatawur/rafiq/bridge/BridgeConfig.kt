package ai.tatawur.rafiq.bridge

import ai.tatawur.rafiq.bridge.tmux.TerminalSettings
import java.nio.file.Path

/**
 * Settings the bridge reads from its environment at start-up.
 *
 * The variable names and defaults are part of the documented setup, so changing one breaks
 * existing installs.
 *
 * @property bindAddress the address to listen on. Use your server's Tailscale address so only
 *   your devices can reach the bridge. The default listens on loopback only.
 * @property dataDir the folder for the bridge's database and logs, or null when not set.
 * @property deviceToken the secret your phone presents on each request, or null when not set.
 * @property agents the agents you can start sessions with, from `RAFIQ_AGENTS`.
 * @property allowedHosts extra `Host` header values the bridge accepts, from `RAFIQ_ALLOWED_HOSTS`, for
 *   example your Tailscale name. The bind address and `localhost` are always accepted.
 * @property devPage whether to serve the development page at `/dev`, from `RAFIQ_DEV_PAGE=1`.
 * @property terminal the tmux gate, from `RAFIQ_TMUX`, `RAFIQ_TERMINAL`, `RAFIQ_TMUX_SESSIONS`,
 *   `RAFIQ_TERMINAL_SHELLS` and `RAFIQ_TMUX_SOCKET`. Off unless you turn it on.
 * @property tailscaleLogin from `RAFIQ_TAILSCALE_LOGIN`: the Tailscale login every request must come from, as
 *   `tailscale serve` names it, or null to skip that check. See `TailscaleIdentityCheck`.
 */
class BridgeConfig(
    val bindAddress: String,
    val port: Int,
    val dataDir: Path?,
    val deviceToken: String?,
    val agents: List<AgentSpec> = emptyList(),
    val devPage: Boolean = false,
    val allowedHosts: List<String> = emptyList(),
    val terminal: TerminalSettings = TerminalSettings.OFF,
    val tailscaleLogin: String? = null,
) {
    // Configuration objects end up in logs. The token must never be one of the things printed.
    override fun toString(): String =
        "BridgeConfig(bindAddress=$bindAddress, port=$port, dataDir=$dataDir, " +
            "deviceToken=${if (deviceToken == null) "unset" else "set"}, agents=${agents.map(AgentSpec::id)}, " +
            "devPage=$devPage, allowedHosts=$allowedHosts, tmux=${terminal.enabled}, terminal=${terminal.terminal}, " +
            "tailscaleLogin=${if (tailscaleLogin == null) "unset" else "set"})"

    companion object {
        const val DEFAULT_BIND_ADDRESS = "127.0.0.1"
        const val DEFAULT_PORT = 8740
        private const val MAX_PORT = 65_535

        /** The shortest device token the bridge accepts when it can run agents: 32 hex characters is 128 bits. */
        const val MIN_TOKEN_LENGTH = 32

        /**
         * Reads the settings from [environment]. Blank values count as unset.
         *
         * @throws IllegalArgumentException if `RAFIQ_PORT` is not a port number, so a typo fails at
         *   start-up instead of the bridge listening somewhere unexpected. It also refuses agents without
         *   a device token of at least [MIN_TOKEN_LENGTH] characters, because anything that reaches the
         *   port could otherwise run them. And it refuses `RAFIQ_TAILSCALE_LOGIN` with a bind address other than
         *   loopback, because the identity header is then something any client could send.
         */
        fun fromEnvironment(environment: Map<String, String>): BridgeConfig {
            fun read(name: String): String? = environment[name]?.trim()?.takeIf { it.isNotEmpty() }

            val port =
                read("RAFIQ_PORT")?.let { raw ->
                    requireNotNull(raw.toIntOrNull()?.takeIf { it in 1..MAX_PORT }) {
                        "RAFIQ_PORT must be a port number between 1 and $MAX_PORT, got '$raw'."
                    }
                } ?: DEFAULT_PORT

            val agents = read("RAFIQ_AGENTS")?.let(::parseAgents).orEmpty()
            val token = read("RAFIQ_DEVICE_TOKEN")
            require(token == null || token.length >= MIN_TOKEN_LENGTH) {
                "RAFIQ_DEVICE_TOKEN must be at least $MIN_TOKEN_LENGTH characters when it is set. " +
                    "Generate one with: openssl rand -hex 32"
            }

            val bindAddress = read("RAFIQ_BIND_ADDRESS") ?: DEFAULT_BIND_ADDRESS
            val tailscaleLogin = read(TAILSCALE_LOGIN)
            require(tailscaleLogin == null || LOGIN.matches(tailscaleLogin)) {
                "$TAILSCALE_LOGIN must be one Tailscale login, for example alex@example.com, with no spaces or commas."
            }
            require(tailscaleLogin == null || TerminalSettings.isLoopback(bindAddress)) {
                "$TAILSCALE_LOGIN is set, so RAFIQ_BIND_ADDRESS must be a loopback address, got '$bindAddress'. " +
                    "The identity header can be trusted only from tailscale serve on the same machine."
            }
            return BridgeConfig(
                bindAddress = bindAddress,
                port = port,
                dataDir = read("RAFIQ_DATA_DIR")?.let(Path::of),
                deviceToken = token,
                agents = agents,
                devPage = parseFlag("RAFIQ_DEV_PAGE", read("RAFIQ_DEV_PAGE")),
                allowedHosts =
                    read("RAFIQ_ALLOWED_HOSTS")
                        ?.split(',')
                        ?.map { it.trim().lowercase() }
                        .orEmpty()
                        .filter(String::isNotEmpty),
                terminal = TerminalSettings.fromEnvironment(::read, bindAddress),
                tailscaleLogin = tailscaleLogin,
            )
        }

        private fun parseFlag(
            name: String,
            raw: String?,
        ): Boolean =
            when (raw) {
                null, "0" -> false
                "1" -> true
                else -> throw IllegalArgumentException("$name must be 1 or 0, got '$raw'.")
            }

        private fun parseAgents(raw: String): List<AgentSpec> {
            val agents =
                raw.split(',').map { entry ->
                    val id = entry.substringBefore('=').trim()
                    val command =
                        entry
                            .substringAfter('=', "")
                            .trim()
                            .split(Regex("\\s+"))
                            .filter(String::isNotEmpty)
                    require(AGENT_ID.matches(id) && command.isNotEmpty()) {
                        "RAFIQ_AGENTS entries must look like id=command, for example claude-code=claude-agent-acp, " +
                            "got '${entry.trim()}'."
                    }
                    AgentSpec(id, command)
                }
            val duplicate = agents.groupBy(AgentSpec::id).entries.firstOrNull { it.value.size > 1 }
            require(duplicate == null) { "RAFIQ_AGENTS lists the agent '${duplicate?.key}' twice." }
            return agents
        }

        private val AGENT_ID = Regex("[a-z0-9][a-z0-9-]*")

        /** The variable that turns on the Tailscale identity check. */
        const val TAILSCALE_LOGIN = "RAFIQ_TAILSCALE_LOGIN"

        // Printable ASCII with no space or comma, so a list or a stray quote fails at start-up.
        private val LOGIN = Regex("[!-~&&[^,]]+")
    }
}

/**
 * One agent from `RAFIQ_AGENTS`: the id clients use, and the command that starts its ACP adapter.
 *
 * @property command the program and its arguments, split on whitespace.
 */
class AgentSpec(
    val id: String,
    val command: List<String>,
)
