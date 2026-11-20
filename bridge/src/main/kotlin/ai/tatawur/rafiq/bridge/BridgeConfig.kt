package ai.tatawur.rafiq.bridge

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
 * @property devPage whether to serve the development page at `/dev`, from `RAFIQ_DEV_PAGE=1`.
 */
class BridgeConfig(
    val bindAddress: String,
    val port: Int,
    val dataDir: Path?,
    val deviceToken: String?,
    val agents: List<AgentSpec> = emptyList(),
    val devPage: Boolean = false,
) {
    // Configuration objects end up in logs. The token must never be one of the things printed.
    override fun toString(): String =
        "BridgeConfig(bindAddress=$bindAddress, port=$port, dataDir=$dataDir, " +
            "deviceToken=${if (deviceToken == null) "unset" else "set"}, agents=${agents.map(AgentSpec::id)}, " +
            "devPage=$devPage)"

    companion object {
        const val DEFAULT_BIND_ADDRESS = "127.0.0.1"
        const val DEFAULT_PORT = 8740
        private const val MAX_PORT = 65_535

        /**
         * Reads the settings from [environment]. Blank values count as unset.
         *
         * @throws IllegalArgumentException if `RAFIQ_PORT` is not a port number, so a typo fails at
         *   start-up instead of the bridge listening somewhere unexpected.
         */
        fun fromEnvironment(environment: Map<String, String>): BridgeConfig {
            fun read(name: String): String? = environment[name]?.trim()?.takeIf { it.isNotEmpty() }

            val port =
                read("RAFIQ_PORT")?.let { raw ->
                    requireNotNull(raw.toIntOrNull()?.takeIf { it in 1..MAX_PORT }) {
                        "RAFIQ_PORT must be a port number between 1 and $MAX_PORT, got '$raw'."
                    }
                } ?: DEFAULT_PORT

            return BridgeConfig(
                bindAddress = read("RAFIQ_BIND_ADDRESS") ?: DEFAULT_BIND_ADDRESS,
                port = port,
                dataDir = read("RAFIQ_DATA_DIR")?.let(Path::of),
                deviceToken = read("RAFIQ_DEVICE_TOKEN"),
                agents = read("RAFIQ_AGENTS")?.let(::parseAgents).orEmpty(),
                devPage = parseFlag("RAFIQ_DEV_PAGE", read("RAFIQ_DEV_PAGE")),
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
