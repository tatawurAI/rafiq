package ai.tatawur.rafiq.bridge

import ai.tatawur.rafiq.bridge.hook.HookChannel
import ai.tatawur.rafiq.bridge.hook.HookSettings
import ai.tatawur.rafiq.bridge.hook.HookSetup
import ai.tatawur.rafiq.bridge.hook.NoopHookSink
import ai.tatawur.rafiq.bridge.server.BridgeSecurity
import ai.tatawur.rafiq.bridge.server.DeviceAdminServer
import ai.tatawur.rafiq.bridge.server.DeviceService
import ai.tatawur.rafiq.bridge.server.InMemoryConnectionRegistry
import ai.tatawur.rafiq.bridge.server.installIdentityCheck
import ai.tatawur.rafiq.bridge.sessions.SessionManager
import ai.tatawur.rafiq.bridge.sessions.isTmuxSessionId
import ai.tatawur.rafiq.bridge.store.DeviceStore
import ai.tatawur.rafiq.bridge.store.EventLog
import ai.tatawur.rafiq.bridge.tmux.AgentDetector
import ai.tatawur.rafiq.bridge.tmux.TerminalGate
import ai.tatawur.rafiq.bridge.tmux.TerminalService
import ai.tatawur.rafiq.bridge.tmux.TmuxSessionSource
import ai.tatawur.rafiq.bridge.tmux.inheritedRafiqVariables
import ai.tatawur.rafiq.bridge.tmux.reader.ClaudeReader
import io.ktor.server.application.log
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.atomic.AtomicReference
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
    // The reader is built later, once Terminal is known. A device paired before that has no approval to join.
    val reader = AtomicReference<ClaudeReader?>(null)
    val devices = DeviceService(deviceStore, connections, onPaired = { id -> reader.get()?.devicePaired(id) })
    // The shared token from RAFIQ_DEVICE_TOKEN stays valid as the device "legacy" until it is revoked.
    config.deviceToken?.let { token -> runBlocking { devices.adoptLegacyToken(token) } }
    val security = BridgeSecurity(config.bindAddress, config.allowedHosts, devices)
    val adminServer =
        config.dataDir?.let { dir ->
            val url = DeviceAdminServer.urlFor(config.bindAddress, config.allowedHosts)
            DeviceAdminServer(DeviceAdminServer.socketPathFor(dir), devices, url, scope).start()
        }
    val terminal = terminalServiceFor(config, eventLog)
    // Hooks feed tmux Chat, so the channel and the installer exist only with tmux on and a data folder for them.
    val hookDir = terminal?.let { config.dataDir }
    val hooks = hookDir?.let { hookSetup(it, deviceStore) }
    // Built before the tmux source starts, so the reader sees the source's first scan.
    val claude = readerFor(terminal, eventLog, scope, config.dataDir, connections, devices, hooks)
    reader.set(claude)
    val sessions =
        SessionManager(
            eventLog,
            config.agents.map(::adapterFor),
            scope,
            visibility =
                terminal?.source?.let { source -> { id: String -> source.isVisible(id) } } ?: { !isTmuxSessionId(it) },
            tmuxChat = claude,
            present = terminal?.source?.let { source -> source::present } ?: { it },
        )
    runBlocking {
        sessions.recoverAfterRestart()
        terminal?.prepare()
    }
    terminal?.source?.start(scope)
    terminal?.let { warnAboutInheritedSecrets(it) }
    val hookChannel = hookDir?.let { dir -> HookChannel(dir, claude ?: NoopHookSink, scope).start() }
    try {
        embeddedServer(CIO, host = config.bindAddress, port = config.port) {
            log.info("Rafiq bridge {} starting with {}", version, config)
            bridgeModule(version, eventLog, sessions, security, config.devPage, terminal, connections, devices, hooks)
            // After the token check, so a request with no token still gets 401 first.
            installIdentityCheck(config.tailscaleLogin)
        }.start(wait = true)
    } finally {
        hookChannel?.close()
        runBlocking { claude?.close() }
        adminServer?.close()
        deviceStore.close()
        scope.cancel()
        eventLog.close()
    }
}

/** With tmux sessions off none of this exists, so no tmux command runs and no tmux session is listed. */
private fun terminalServiceFor(
    config: BridgeConfig,
    eventLog: EventLog,
): TerminalService? =
    config.terminal.takeIf { it.enabled }?.let { settings ->
        val gate = TerminalGate(settings)
        TerminalService(gate, TmuxSessionSource(gate, eventLog, AgentDetector()))
    }

/** The Claude reader for tmux Chat, asking the device service for paired devices and the installer for hooks. */
@Suppress("LongParameterList") // Start-up pieces the reader needs, each built in main.
private fun readerFor(
    terminal: TerminalService?,
    eventLog: EventLog,
    scope: CoroutineScope,
    dataDir: Path?,
    connections: InMemoryConnectionRegistry,
    devices: DeviceService,
    hooks: HookSetup?,
): ClaudeReader? =
    claudeReaderFor(
        terminal,
        eventLog,
        scope,
        dataDir,
        connections,
        devices = { devices.devices().filter { it.revokedAtMs == null }.map { it.id } },
        hooksInstalled = { project -> hooks?.preview(project)?.installed ?: true },
    )

/** The hook installer, or null with a warning when the hook program is not where an installed bridge has it. */
private fun hookSetup(
    dataDir: Path?,
    consents: DeviceStore,
): HookSetup? {
    val program = HookSetup.locateProgram()
    if (program == null || dataDir == null) {
        LoggerFactory
            .getLogger("ai.tatawur.rafiq.bridge")
            .warn("hooks/rafiq-hook.py was not found next to the bridge, so hooks cannot be installed from the app.")
        return null
    }
    return HookSetup(HookSettings(program.toString(), dataDir.toAbsolutePath().toString()), consents)
}

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
