package ai.tatawur.rafiq.bridge.push

import ai.tatawur.rafiq.bridge.BridgeConfig
import ai.tatawur.rafiq.bridge.BridgeFixture
import ai.tatawur.rafiq.bridge.server.DeviceFixture
import ai.tatawur.rafiq.bridge.startPush
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@Timeout(60)
class PushSwitchTest {
    @TempDir
    lateinit var dir: Path

    private val devices = DeviceFixture()

    @AfterEach
    fun close() = devices.close()

    @Test
    fun rafiq_push_0_leaves_out_the_registry_and_the_notifier() =
        runBlocking<Unit> {
            BridgeFixture(dir).use { bridge ->
                fun start(environment: Map<String, String>) =
                    startPush(
                        BridgeConfig.fromEnvironment(environment),
                        devices.store,
                        bridge.log,
                        bridge.sessions,
                        bridge.scope,
                    )

                assertNull(start(mapOf("RAFIQ_PUSH" to "0")))
                assertNotNull(start(emptyMap()))
            }
        }
}
