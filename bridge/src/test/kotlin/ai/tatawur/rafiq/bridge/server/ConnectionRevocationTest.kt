package ai.tatawur.rafiq.bridge.server

import ai.tatawur.rafiq.bridge.seed
import ai.tatawur.rafiq.bridge.tmux.RealBridge
import ai.tatawur.rafiq.bridge.tmux.Received
import ai.tatawur.rafiq.bridge.tmux.eventually
import ai.tatawur.rafiq.protocol.EventPayload
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
    fun a_handler_that_is_cancelled_mid_stream_leaves_the_registry() {
        val bridge = RealBridge(dir.resolve("data"), gate = null).also { bridge = it }
        runBlocking { bridge.fixture.log.seed() }
        val socket = bridge.socket("/events?session=ses_1&after=0", device = "phone-3")
        socket.until { it is Received.Text }
        assertEquals(1, bridge.connections.openCount("phone-3"))

        // Stopping the server cancels the handler's coroutine while it is streaming. No close frame, no return.
        bridge.stopServer()

        eventually("the registry forgets a cancelled handler") { bridge.connections.openCount("phone-3") == 0 }
    }

    @Test
    fun revoking_a_device_does_not_wait_for_a_phone_that_stopped_reading() {
        val bridge = RealBridge(dir.resolve("data"), gate = null).also { bridge = it }
        runBlocking {
            bridge.fixture.log.seed()
            // Enough to fill every buffer between the server and a phone that reads nothing.
            repeat(BIG_EVENTS) {
                bridge.fixture.log.append("ses_1", EventPayload.MessageDelta("m", "main", "x".repeat(BIG_TEXT)))
            }
        }
        val socket = bridge.socket("/events?session=ses_1&after=0", device = "phone-4")
        socket.stall()
        eventually("the registry has the socket") { bridge.connections.openCount("phone-4") == 1 }
        Thread.sleep(SETTLE_MS)

        val revoke = Thread { runBlocking { bridge.connections.closeAll("phone-4", "device_revoked") } }
        revoke.start()
        revoke.join(JOIN_MS)

        assertEquals(false, revoke.isAlive, "the revoke waited for a phone that was not reading")
        // And the handler itself is gone, not left blocked on the phone until the connection times out.
        eventually("the handler ended", seconds = 10) { bridge.handlersEnded.get() == 1 }
    }
}

private const val BIG_EVENTS = 6000
private const val BIG_TEXT = 16000
private const val SETTLE_MS = 1500L
private const val JOIN_MS = 15_000L
