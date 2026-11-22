package ai.tatawur.rafiq.bridge.agent.acp

import ai.tatawur.rafiq.bridge.agent.AgentUnavailableException
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.MessageRole
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The session's single consumer, driven directly with items, so no agent process is involved. */
@Timeout(30)
class SessionActorTest {
    private val ids = AtomicInteger()
    private val desk = ApprovalDesk()
    private val clock = VirtualClock()
    private val actor =
        SessionActor(
            AcpEventMapper("session-1", Path.of("/home/user/weather-app"), newId = { "id-${ids.incrementAndGet()}" }),
            desk,
            newId = { "id-${ids.incrementAndGet()}" },
            scheduler = clock,
            quietPeriod = 5.seconds,
        )

    @Test
    fun a_follow_up_reply_outside_a_turn_ends_on_the_agents_usage_report_and_resumes_if_it_continues() =
        runBlocking {
            launch { actor.run() }
            val events = async { actor.updates.toList() }

            actor.offer(SessionActor.Item.OpenedRoot("sess_root", replay = false))
            // No prompt: the agent follows up on its own after its helpers report back.
            actor.offer(rootUpdate(chunk("msg_2", "Both helpers")))
            actor.offer(rootUpdate(chunk("msg_2", " are done.")))
            actor.offer(rootUpdate(USAGE_WITH_COST))
            actor.offer(marker())
            actor.offer(rootUpdate(chunk("msg_2", " One more thing.")))
            actor.offer(SessionActor.Item.Finish(exitCode = 0, askedToClose = true))
            actor.finished.await()

            val all = events.await()
            val ended =
                all.indexOfFirst {
                    it.isFinal() &&
                        (it as EventPayload.Message).text() == "Both helpers are done."
                }
            assertTrue(ended in 0 until all.indexOfFirst(::isMarker), "Ended by the usage report, not the session end.")
            val reply = all.filterIsInstance<EventPayload.Message>().filter { it.role == MessageRole.AGENT }
            val resumed = reply.single { it.isStreaming && it.text() == "Both helpers are done. One more thing." }
            assertEquals(listOf(resumed.id), reply.map { it.id }.distinct(), "One reply under one id, even resumed.")
        }

    @Test
    fun a_follow_up_reply_with_no_usage_report_ends_after_a_quiet_period_with_no_new_chunk() =
        runBlocking {
            launch { actor.run() }
            val received = Channel<EventPayload>(Channel.UNLIMITED)
            launch { actor.updates.collect { received.send(it) } }

            suspend fun receiveThrough(last: (EventPayload) -> Boolean): List<EventPayload> {
                val seen = mutableListOf<EventPayload>()
                do seen += received.receive() while (!last(seen.last()))
                return seen
            }

            actor.offer(SessionActor.Item.OpenedRoot("sess_root", replay = false))
            actor.offer(rootUpdate(chunk("msg_3", "Done")))
            receiveThrough { it is EventPayload.Message }
            clock.advanceBy(4.seconds)
            actor.offer(rootUpdate(chunk("msg_3", ", both answers are in.")))
            receiveThrough { it is EventPayload.MessageDelta }
            // Eight seconds since the first chunk but four since the last, so the reply is still open.
            clock.advanceBy(4.seconds)
            actor.offer(marker())
            val beforeQuiet = receiveThrough(::isMarker)
            clock.advanceBy(1.seconds)
            actor.offer(marker())
            val afterQuiet = receiveThrough(::isMarker)

            assertTrue(beforeQuiet.none { it.isFinal() }, "Not ended while chunks keep coming: $beforeQuiet")
            val ended = afterQuiet.single { it.isFinal() } as EventPayload.Message
            assertEquals("Done, both answers are in.", ended.text())
            actor.offer(SessionActor.Item.Finish(exitCode = 0, askedToClose = true))
            actor.finished.await()
        }

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

    private fun rootUpdate(update: String) = update(json("""{"sessionId":"sess_root","update":$update}"""))

    private fun chunk(
        messageId: String,
        text: String,
    ) = """{"sessionUpdate":"agent_message_chunk","messageId":"$messageId","content":{"type":"text","text":"$text"}}"""

    /** An item that reports a note and touches no message, to mark a point in the session's updates. */
    private fun marker() = SessionActor.Item.Wire(WireOrder.Inbound.Dropped)

    private fun isMarker(event: EventPayload) =
        event is EventPayload.Message && event.role == MessageRole.SYSTEM && "too long" in event.text()

    private fun EventPayload.isFinal() = this is EventPayload.Message && role == MessageRole.AGENT && !isStreaming

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

/** Timers that run only when the test moves time on, so nothing here waits on a real clock. */
private class VirtualClock : Scheduler {
    private class Timer(
        val dueAt: Duration,
        val action: () -> Unit,
    ) {
        var disposed = false
    }

    private var now = Duration.ZERO
    private val timers = mutableListOf<Timer>()

    override fun schedule(
        after: Duration,
        action: () -> Unit,
    ): DisposableHandle {
        val timer = Timer(now + after, action)
        timers += timer
        return DisposableHandle { timer.disposed = true }
    }

    fun advanceBy(step: Duration) {
        now += step
        val due = timers.filter { it.dueAt <= now }.sortedBy { it.dueAt }
        timers -= due.toSet()
        due.filterNot { it.disposed }.forEach { it.action() }
    }
}

// The shape claude-agent-acp sends when a model cycle ends, with its numbers reduced.
private const val USAGE_WITH_COST =
    """{"sessionUpdate":"usage_update","used":100,"size":1000,"cost":{"amount":0.01,"currency":"USD"}}"""

private const val CHUNK =
    """{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"still working"}}"""
private const val HELPER_NAMED_MAIN =
    """{"sessionUpdate":"subagent_update","sessionId":"main","title":"Helper","state":{"state":"running"}}"""
