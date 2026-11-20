package ai.tatawur.rafiq.bridge

import io.ktor.server.application.log
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.util.Properties

/** Starts the bridge with settings from the environment and serves until the process is stopped. */
fun main() {
    val config = BridgeConfig.fromEnvironment(System.getenv())
    val version = bridgeVersion()
    embeddedServer(CIO, host = config.bindAddress, port = config.port) {
        log.info("Rafiq bridge {} starting with {}", version, config)
        bridgeModule(version)
    }.start(wait = true)
}

/** The version the build wrote into `version.properties`. */
internal fun bridgeVersion(): String {
    val properties = Properties()
    BridgeConfig::class.java.getResourceAsStream("version.properties")?.use(properties::load)
    return properties.getProperty("version") ?: error("version.properties is missing from the bridge resources.")
}
