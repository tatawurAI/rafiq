package ai.tatawur.rafiq.bridge.server

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
