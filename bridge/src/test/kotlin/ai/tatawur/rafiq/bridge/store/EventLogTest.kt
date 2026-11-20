package ai.tatawur.rafiq.bridge.store

import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.AgentThread
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.Session
import ai.tatawur.rafiq.protocol.ThreadKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class EventLogTest {
    @TempDir
    lateinit var dir: Path

    private val session =
        Session("ses_1", "fake", "/work/weather-app", "weather-app", AgentState.DONE, createdAtMs = 1, updatedAtMs = 1)

    private fun delta(n: Int) = EventPayload.MessageDelta(messageId = "m", threadId = "main", text = "d$n")

    @Test
    fun concurrent_appends_get_every_seq_from_one_to_n_once() =
        runBlocking {
            val log = EventLog.open(dir, Dispatchers.IO.limitedParallelism(1))
            log.createSession(session, "ref")

            val returned =
                (1..20)
                    .map { writer ->
                        async(Dispatchers.Default) {
                            (1..25).map {
                                log
                                    .append(
                                        "ses_1",
                                        delta(writer * 100 + it),
                                    ).seq
                            }
                        }
                    }.awaitAll()
                    .flatten()

            assertEquals((1L..500L).toList(), returned.sorted())
            assertEquals((1L..500L).toList(), log.after("ses_1", 0, 1000).map { it.seq })
            log.close()
        }

    @Test
    fun events_sessions_and_threads_survive_a_restart_and_seq_carries_on() =
        runBlocking {
            val first = EventLog.open(dir, Dispatchers.IO.limitedParallelism(1))
            first.createSession(session, "ref")
            val helper = AgentThread("sub-1", "ses_1", "main", ThreadKind.SUBAGENT, "Explore", AgentState.WORKING)
            first.append("ses_1", EventPayload.ThreadUpdated(helper))
            first.append("ses_1", delta(1))
            val written = first.after("ses_1", 0, 10)
            first.close()

            val second = EventLog.open(dir, Dispatchers.IO.limitedParallelism(1))

            assertEquals(written, second.after("ses_1", 0, 10))
            assertEquals(listOf(helper), second.threads("ses_1"))
            assertEquals("ref", second.session("ses_1")?.agentSessionRef)
            assertEquals(3L, second.append("ses_1", delta(2)).seq)
            assertEquals(listOf(3L), second.after("ses_1", 2, 10).map { it.seq })
            second.close()
        }
}
