package ai.tatawur.rafiq.bridge

import ai.tatawur.rafiq.bridge.hook.HookChannel
import ai.tatawur.rafiq.bridge.hook.HookSink
import ai.tatawur.rafiq.bridge.hook.NoopHookSink
import ai.tatawur.rafiq.bridge.server.BridgeSecurity
import ai.tatawur.rafiq.bridge.server.DeviceAdminServer
import ai.tatawur.rafiq.bridge.server.DeviceService
import ai.tatawur.rafiq.bridge.server.InMemoryConnectionRegistry
import ai.tatawur.rafiq.bridge.sessions.SessionManager
import ai.tatawur.rafiq.bridge.sessions.isTmuxSessionId
import ai.tatawur.rafiq.bridge.store.DeviceStore
import ai.tatawur.rafiq.bridge.store.EventLog
import ai.tatawur.rafiq.bridge.tmux.AgentDetector
import ai.tatawur.rafiq.bridge.tmux.TerminalGate
import ai.tatawur.rafiq.bridge.tmux.TerminalService
import ai.tatawur.rafiq.bridge.tmux.TmuxSessionSource
import ai.tatawur.rafiq.bridge.tmux.inheritedRafiqVariables
import io.ktor.server.application.log
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.Properties
import kotlin.system.exitProcess

/** Starts the bridge with settings from the environment and serves until the process is stopped. */
fun main(args: Array<String>) {
    // `rafiq-bridge pair`, `devices` and `revoke` talk to the running bridge and exit.
    if (args.firstOrNull() in DeviceCli.COMMANDS) {
        exitProcess(DeviceCli.run(args.toList(), System.getenv(), System.out, System.err))
    }
    val config = BridgeConfig.fromEnvironment(System.getenv())
    val version = bridgeVersion()
    warnAboutMissingSettings(config)
    val eventLog = EventLog.open(config.dataDir, Dispatchers.IO.limitedParallelism(1))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val connections = InMemoryConnectionRegistry()
    val deviceStore = DeviceStore.open(config.dataDir, Dispatchers.IO.limitedParallelism(1))
    val devices = DeviceService(deviceStore, connections)
    // The shared token from RAFIQ_DEVICE_TOKEN stays valid as the device "legacy" until it is revoked.
    config.deviceToken?.let { token -> runBlocking { devices.adoptLegacyToken(token) } }
    val security = BridgeSecurity(config.bindAddress, config.allowedHosts, devices)
    val adminServer =
        config.dataDir?.let { dir ->
            val url = DeviceAdminServer.urlFor(config.bindAddress, config.allowedHosts)
            DeviceAdminServer(DeviceAdminServer.socketPathFor(dir), devices, url, scope).start()
        }
    // With Terminal off none of this exists, so no tmux command runs and no tmux session is listed.
    val terminal =
        config.terminal.takeIf { it.enabled }?.let { settings ->
            val gate = TerminalGate(settings)
            TerminalService(gate, TmuxSessionSource(gate, eventLog, AgentDetector()))
        }
    val sessions =
        SessionManager(
            eventLog,
            config.agents.map(::adapterFor),
            scope,
            visibility =
                terminal?.source?.let { source -> { id: String -> source.isVisible(id) } } ?: { !isTmuxSessionId(it) },
        )
    runBlocking {
        sessions.recoverAfterRestart()
        terminal?.prepare()
    }
    terminal?.source?.start(scope)
    terminal?.let { warnAboutInheritedSecrets(it) }
    // Hooks feed tmux Chat, so the channel exists only with tmux on, and only with a data folder to hold it.
    val hookChannel = terminal?.let { config.dataDir?.let { dir -> HookChannel(dir, hookSink, scope).start() } }
    try {
        embeddedServer(CIO, host = config.bindAddress, port = config.port) {
            log.info("Rafiq bridge {} starting with {}", version, config)
            bridgeModule(version, eventLog, sessions, security, config.devPage, terminal, connections, devices)
        }.start(wait = true)
    } finally {
        hookChannel?.close()
        adminServer?.close()
        deviceStore.close()
        scope.cancel()
        eventLog.close()
    }
}

/** Where hook calls go. Until a reader is wired in, permission requests go back to the desk. */
private val hookSink: HookSink = NoopHookSink

/** The device token must not reach the panes. Names only: a value is never printed. */
private fun warnAboutInheritedSecrets(terminal: TerminalService) {
    val names = inheritedRafiqVariables(terminal.gate.server)
    if (names.isNotEmpty()) {
        LoggerFactory.getLogger("ai.tatawur.rafiq.bridge").warn(
            "The tmux server's global environment has {}, so every new pane inherits it. Remove it with " +
                "tmux set-environment -g -u <name>, and do not export it in the shell that starts tmux.",
            names.joinToString(", "),
        )
    }
}

/** The version the build wrote into `version.properties`. */
internal fun bridgeVersion(): String {
    val properties = Properties()
    BridgeConfig::class.java.getResourceAsStream("version.properties")?.use(properties::load)
    return properties.getProperty("version") ?: error("version.properties is missing from the bridge resources.")
}

private fun warnAboutMissingSettings(config: BridgeConfig) {
    val log = LoggerFactory.getLogger("ai.tatawur.rafiq.bridge")
    if (config.dataDir == null) {
        log.warn("RAFIQ_DATA_DIR is not set, so events are lost when the bridge stops, and pairing needs it.")
    }
    if (config.deviceToken == null) {
        log.warn("RAFIQ_DEVICE_TOKEN is not set. Pair a phone with: rafiq-bridge pair --name <device>")
    }
}
