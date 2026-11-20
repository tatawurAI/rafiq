package ai.tatawur.rafiq.bridge

import ai.tatawur.rafiq.bridge.server.BridgeSecurity
import ai.tatawur.rafiq.bridge.sessions.SessionManager
import ai.tatawur.rafiq.bridge.store.EventLog
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

/** Starts the bridge with settings from the environment and serves until the process is stopped. */
fun main() {
    val config = BridgeConfig.fromEnvironment(System.getenv())
    val version = bridgeVersion()
    if (config.dataDir == null) {
        LoggerFactory
            .getLogger("ai.tatawur.rafiq.bridge")
            .warn("RAFIQ_DATA_DIR is not set, so sessions and events are lost when the bridge stops.")
    }
    if (config.deviceToken == null) {
        LoggerFactory
            .getLogger("ai.tatawur.rafiq.bridge")
            .warn("RAFIQ_DEVICE_TOKEN is not set, so every route except /health answers 401.")
    }
    val security = BridgeSecurity(config.deviceToken, config.bindAddress, config.allowedHosts)
    val eventLog = EventLog.open(config.dataDir, Dispatchers.IO.limitedParallelism(1))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val sessions = SessionManager(eventLog, config.agents.map(::adapterFor), scope)
    runBlocking { sessions.recoverAfterRestart() }
    try {
        embeddedServer(CIO, host = config.bindAddress, port = config.port) {
            log.info("Rafiq bridge {} starting with {}", version, config)
            bridgeModule(version, eventLog, sessions, security, config.devPage)
        }.start(wait = true)
    } finally {
        scope.cancel()
        eventLog.close()
    }
}

/** The version the build wrote into `version.properties`. */
internal fun bridgeVersion(): String {
    val properties = Properties()
    BridgeConfig::class.java.getResourceAsStream("version.properties")?.use(properties::load)
    return properties.getProperty("version") ?: error("version.properties is missing from the bridge resources.")
}
