package ai.tatawur.rafiq.bridge.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class ConnectionRegistryTest {
    @Test
    fun closing_a_device_closes_only_its_own_connections_once_and_survives_a_failing_close() =
        runBlocking {
            val registry = InMemoryConnectionRegistry()
            val closed = mutableListOf<String>()
            registry.register("dev_a", "events") { closed += "a1:$it" }
            registry.register("dev_a", "terminal") { error("socket already gone") }
            registry.register("dev_a", "events") { closed += "a3:$it" }
            registry.register("dev_b", "events") { closed += "b:$it" }
            val ended = registry.register("dev_a", "events") { closed += "ended" }
            ended.close()

            assertEquals(3, registry.closeAll("dev_a", "revoked"))
            assertEquals(listOf("a1:revoked", "a3:revoked"), closed.sorted())
            assertEquals(0, registry.closeAll("dev_a", "revoked"))
            assertEquals(1, registry.openCount("dev_b"))
        }

    @Test
    fun a_connection_that_registers_after_its_device_was_revoked_is_closed_at_once() =
        runBlocking {
            val registry = InMemoryConnectionRegistry()
            val reason = CompletableDeferred<String>()
            registry.closeAll("dev_a", "device revoked")

            registry.register("dev_a", "terminal") { reason.complete(it) }

            assertEquals("device revoked", withTimeout(5_000) { reason.await() })
            assertEquals(0, registry.openCount("dev_a"))
        }

    @Test
    fun a_stalled_socket_does_not_hold_up_the_others_or_the_answer() =
        runBlocking {
            val registry = InMemoryConnectionRegistry(closeTimeout = 300.milliseconds)
            val closed = mutableListOf<String>()
            registry.register("dev_a", "events") { awaitCancellation() }
            registry.register("dev_a", "events") { closed += "second" }
            registry.register("dev_a", "terminal") { closed += "third" }

            val count = withTimeout(5_000) { registry.closeAll("dev_a", "device revoked") }

            assertEquals(3, count)
            assertEquals(listOf("second", "third"), closed.sorted())
        }
}
