package ai.tatawur.rafiq.bridge.server

import ai.tatawur.rafiq.bridge.store.EventLog
import ai.tatawur.rafiq.bridge.store.SlowConsumerException
import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EventStreamerTest {
    @TempDir
    lateinit var dir: Path

    private fun open() =
        EventLog
            .open(dir, Dispatchers.IO.limitedParallelism(1))
            .also {
                runBlocking {
                    it.createSession(
                        Session("ses_1", "fake", "/work/weather-app", "weather-app", AgentState.DONE, 1, 1),
                        "ref",
                    )
                }
            }

    private fun delta(n: Int) = EventPayload.MessageDelta(messageId = "m", threadId = "main", text = "d$n")

    @Test
    fun events_appended_while_replaying_arrive_once_and_in_order() =
        runBlocking {
            val log = open()
            repeat(3) { log.append("ses_1", delta(it + 1)) }
            val seen = mutableListOf<Long>()
            val fiveSeen = CompletableDeferred<Unit>()
            val sixSeen = CompletableDeferred<Unit>()
            val streaming =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    // One event per page, so events 4 and 5 are both read by a later replay page and
                    // buffered by the live subscription: the streamer must send each only once.
                    EventStreamer(log, pageSize = 1).stream("ses_1", 0) { event ->
                        seen += event.seq
                        if (event.seq == 1L) repeat(2) { log.append("ses_1", delta(4 + it)) }
                        if (seen.size == 5) fiveSeen.complete(Unit)
                        if (seen.size == 6) sixSeen.complete(Unit)
                    }
                }

            fiveSeen.await()
            log.append("ses_1", delta(6))
            sixSeen.await()
            streaming.cancel()

            assertEquals(listOf(1L, 2, 3, 4, 5, 6), seen)
            log.close()
        }

    @Test
    fun a_client_that_stops_reading_is_cut_off_without_holding_up_appends_or_other_readers() =
        runBlocking {
            val log = open()
            log.append("ses_1", delta(0))
            val blocked = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val slow =
                async {
                    runCatching {
                        EventStreamer(log, bufferSize = 2).stream("ses_1", 0) {
                            blocked.complete(Unit)
                            release.await()
                        }
                    }.exceptionOrNull()
                }
            blocked.await()
            val healthy = log.subscribe("ses_1", capacity = 100)

            repeat(5) { log.append("ses_1", delta(it + 1)) }

            assertEquals((2L..6L).toList(), List(5) { healthy.events.receive().seq })
            release.complete(Unit)
            assertFailsWith<SlowConsumerException> { slow.await()?.let { throw it } }
            healthy.close()
            log.close()
        }
}
