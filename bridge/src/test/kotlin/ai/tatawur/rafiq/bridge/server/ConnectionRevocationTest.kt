package ai.tatawur.rafiq.bridge.server

import ai.tatawur.rafiq.bridge.seed
import ai.tatawur.rafiq.bridge.tmux.RealBridge
import ai.tatawur.rafiq.bridge.tmux.Received
import ai.tatawur.rafiq.bridge.tmux.eventually
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

@Timeout(60)
class ConnectionRevocationTest {
    @TempDir
    lateinit var dir: Path

    private var bridge: RealBridge? = null

    @AfterEach
    fun cleanUp() {
        bridge?.close()
    }

    @Test
    fun revoking_a_device_closes_its_event_socket_at_once_and_a_closed_socket_is_forgotten() {
        val bridge = RealBridge(dir.resolve("data"), gate = null).also { bridge = it }
        runBlocking { bridge.fixture.log.seed() }
        val socket = bridge.socket("/events?session=ses_1&after=0", device = "phone-1")
        socket.until { it is Received.Text }
        assertEquals(1, bridge.connections.openCount("phone-1"))

        runBlocking { bridge.connections.closeAll("phone-1", "device_revoked") }

        assertEquals("device_revoked", (socket.until { it is Received.Closed } as Received.Closed).reason)

        // A socket that ends by itself leaves the registry too, or a long-lived bridge would keep every one.
        val ended = bridge.socket("/events?session=ses_1&after=0", device = "phone-2")
        ended.until { it is Received.Text }
        assertEquals(1, bridge.connections.openCount("phone-2"))
        ended.close()
        eventually("the registry forgets a socket that ended") { bridge.connections.openCount("phone-2") == 0 }
    }

    @Test
    fun a_connection_that_drops_without_a_close_frame_leaves_the_registry() {
        val bridge = RealBridge(dir.resolve("data"), gate = null).also { bridge = it }
        runBlocking { bridge.fixture.log.seed() }
        val socket = bridge.socket("/events?session=ses_1&after=0", device = "phone-3")
        socket.until { it is Received.Text }
        assertEquals(1, bridge.connections.openCount("phone-3"))

        socket.drop()

        eventually("the registry forgets a dropped socket") { bridge.connections.openCount("phone-3") == 0 }
    }
}
