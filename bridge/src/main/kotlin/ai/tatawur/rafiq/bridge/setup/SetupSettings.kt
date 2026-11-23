package ai.tatawur.rafiq.bridge.setup

import ai.tatawur.rafiq.bridge.BridgeConfig
import java.nio.file.Path

internal const val ADAPTER = "claude-agent-acp"
internal const val ADAPTER_PACKAGE = "@agentclientprotocol/claude-agent-acp"
internal const val DATA_DIR = "RAFIQ_DATA_DIR"
internal const val AGENTS = "RAFIQ_AGENTS"
internal const val ALLOWED_HOSTS = "RAFIQ_ALLOWED_HOSTS"

/**
 * A setting `setup` offers to write to `bridge.conf`.
 *
 * @property reason one sentence on what the setting does, shown before the question.
 */
internal class ProposedSetting(
    val name: String,
    val value: String,
    val reason: String,
)

/**
 * The settings `setup` offers, given what is already set: a data folder and the Claude Code agent when they are
 * missing, and this machine's `*.ts.net` name when the allowed hosts lack it. [fileHosts] is the file's own
 * `RAFIQ_ALLOWED_HOSTS`, which the new name is added to, so hosts you listed by hand stay.
 */
internal fun proposedSettings(
    environment: Map<String, String>,
    loaded: LoadedSettings,
    agentFound: Boolean,
    dnsName: String?,
    fileHosts: String?,
): List<ProposedSetting> {
    fun hosts(raw: String?) =
        raw
            .orEmpty()
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
    val dataDir = defaultDataDir(environment)
    return listOfNotNull(
        ProposedSetting(DATA_DIR, dataDir.toString(), "The bridge keeps its database here.")
            .takeIf { loaded.values[DATA_DIR].isNullOrBlank() && dataDir != null },
        ProposedSetting(AGENTS, "claude-code=$ADAPTER", "Lets your phone start Claude Code sessions.")
            .takeIf { loaded.values[AGENTS].isNullOrBlank() && agentFound },
        dnsName
            ?.takeIf { name -> hosts(loaded.values[ALLOWED_HOSTS]).none { it.equals(name, ignoreCase = true) } }
            ?.let { name ->
                ProposedSetting(
                    ALLOWED_HOSTS,
                    (hosts(fileHosts) + name).joinToString(","),
                    "Lets your phone reach the bridge as $name.",
                )
            },
    )
}

/**
 * One line per setting the bridge will use, saying where its value came from: the environment, `bridge.conf` or the
 * default. A token and a Tailscale login say where they come from, never what they are.
 */
internal fun originLines(settings: LoadedSettings): List<String> {
    val names = (SHOWN + settings.origins.keys).distinct().sorted()
    val width = names.maxOf(String::length)
    return names.map { name ->
        val origin = settings.origins[name]
        val value =
            when {
                origin == null -> DEFAULTS[name]?.let { "$it (default)" } ?: "not set"
                name in HIDDEN -> "set, value hidden (${origin.label})"
                else -> "${settings.values[name]} (${origin.label})"
            }
        "${name.padEnd(width)}  $value"
    }
}

/** `$XDG_DATA_HOME/rafiq`, or `~/.local/share/rafiq`, as the XDG spec has it. Null with neither set. */
internal fun defaultDataDir(environment: Map<String, String>): Path? {
    fun absolute(name: String) = environment[name]?.takeIf { it.isNotBlank() }?.let(Path::of)?.takeIf { it.isAbsolute }
    val base = absolute("XDG_DATA_HOME") ?: absolute("HOME")?.resolve(".local/share") ?: return null
    return base.resolve("rafiq")
}

private val SHOWN = listOf("RAFIQ_BIND_ADDRESS", "RAFIQ_PORT", DATA_DIR, AGENTS, ALLOWED_HOSTS)
private val DEFAULTS =
    mapOf(
        "RAFIQ_BIND_ADDRESS" to BridgeConfig.DEFAULT_BIND_ADDRESS,
        "RAFIQ_PORT" to BridgeConfig.DEFAULT_PORT.toString(),
    )
private val HIDDEN = setOf("RAFIQ_DEVICE_TOKEN", BridgeConfig.TAILSCALE_LOGIN)
