package ai.tatawur.rafiq.bridge.agent.acp

import ai.tatawur.rafiq.bridge.agent.AgentUnavailableException
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.MessageRole
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Timeout
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The session's single consumer, driven directly with items, so no agent process is involved. */
@Timeout(30)
class SessionActorTest {
    private val ids = AtomicInteger()
    private val desk = ApprovalDesk()
    private val actor =
        SessionActor(
            AcpEventMapper("session-1", Path.of("/home/user/weather-app"), newId = { "id-${ids.incrementAndGet()}" }),
            desk,
            newId = { "id-${ids.incrementAndGet()}" },
        )

    @Test
    fun an_update_the_consumer_cannot_handle_is_reported_and_the_next_one_still_maps() =
        runBlocking {
            launch { actor.run() }
            val events = async { actor.updates.toList() }

            actor.offer(SessionActor.Item.OpenedRoot("sess_root", replay = false))
            // Params whose every read throws: the worst a bug in the mapper could do with agent JSON.
            actor.offer(update(JsonObject(ThrowingMap)))
            actor.offer(update(json("""{"sessionId":"sess_root","update":$CHUNK}""")))
            // An agent-side helper id equal to the main thread's id must not take the main thread.
            actor.offer(update(json("""{"sessionId":"sess_root","update":$HELPER_NAMED_MAIN}""")))
            actor.offer(SessionActor.Item.Finish(exitCode = 0, askedToClose = true))
            actor.finished.await()

            val messages = events.await().filterIsInstance<EventPayload.Message>()
            assertTrue(messages.any { it.role == MessageRole.SYSTEM && "could not read" in it.text() })
            assertTrue(messages.any { it.role == MessageRole.AGENT && it.text() == "still working" })
            val helper =
                events
                    .await()
                    .filterIsInstance<EventPayload.ThreadUpdated>()
                    .single()
                    .thread
            assertEquals("helper:main", helper.id)
        }

    @Test
    fun a_permission_request_the_consumer_cannot_map_is_answered_with_an_error() =
        runBlocking {
            launch { actor.run() }
            val events = async { actor.updates.toList() }
            actor.offer(SessionActor.Item.OpenedRoot("sess_root", replay = false))
            // The SDK's handler for the request is already waiting for the consumer to map it.
            val answer = async { runCatching { desk.answer("\"perm-9\"") {} } }
            yield()

            val request = WireOrder.Inbound.Request("\"perm-9\"", "session/request_permission", JsonObject(ThrowingMap))
            actor.offer(SessionActor.Item.Wire(request))
            actor.offer(update(json("""{"sessionId":"sess_root","update":$CHUNK}""")))
            actor.offer(SessionActor.Item.Finish(exitCode = 0, askedToClose = true))
            actor.finished.await()
            events.await()

            val failure = answer.await().exceptionOrNull()
            assertTrue(failure is AgentUnavailableException, "The agent gets an error answer, not silence: $failure")
            assertTrue("could not handle" in failure.message.orEmpty(), "Refused by the guard, not by the session end.")
        }

    private fun update(params: JsonObject) =
        SessionActor.Item.Wire(WireOrder.Inbound.Notification("session/update", params))

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    /** A map that fails on every read, standing in for input the mapper was never written for. */
    private object ThrowingMap : AbstractMap<String, JsonElement>() {
        override val entries: Set<Map.Entry<String, JsonElement>>
            get() = error("unreadable")

        override fun get(key: String): JsonElement = error("unreadable")
    }
}

private const val CHUNK =
    """{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"still working"}}"""
private const val HELPER_NAMED_MAIN =
    """{"sessionUpdate":"subagent_update","sessionId":"main","title":"Helper","state":{"state":"running"}}"""
