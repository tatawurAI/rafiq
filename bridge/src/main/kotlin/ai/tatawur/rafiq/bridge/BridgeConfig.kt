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
 */
class BridgeConfig(
    val bindAddress: String,
    val port: Int,
    val dataDir: Path?,
    val deviceToken: String?,
) {
    // Configuration objects end up in logs. The token must never be one of the things printed.
    override fun toString(): String =
        "BridgeConfig(bindAddress=$bindAddress, port=$port, dataDir=$dataDir, " +
            "deviceToken=${if (deviceToken == null) "unset" else "set"})"

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
            )
        }
    }
}
