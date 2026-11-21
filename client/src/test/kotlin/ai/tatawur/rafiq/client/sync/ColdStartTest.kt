package ai.tatawur.rafiq.client.sync

import ai.tatawur.rafiq.client.ConnectionState
import ai.tatawur.rafiq.client.SessionReducer
import ai.tatawur.rafiq.client.toListItems
import ai.tatawur.rafiq.protocol.Event
import ai.tatawur.rafiq.protocol.api.SessionSummary
import ai.tatawur.rafiq.protocol.protocolJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** What the app shows the moment it starts: the last known state, and the right first screen. */
@OptIn(ExperimentalCoroutinesApi::class) // The test scheduler's clock stands in for the wall clock.
class ColdStartTest {
    @Test
    fun a_paired_app_never_reads_as_not_paired_before_its_scope_runs() =
        runTest {
            // backgroundScope has not run yet, so this is the value the first screen reads.
            val repository = repository(source = { _, _ -> throw IOException("No network.") })

            assertNotEquals(ConnectionState.NotPaired, repository.connection.value)
        }

    @Test
    fun with_no_network_the_app_shows_the_cached_session_and_list_and_waits_for_the_network() =
        runTest {
            val log = recordedLog()
            val store = InMemoryEventStore()
            log.forEach { store.append(it) }
            val session = SessionReducer.fold(log.first().sessionId, log)
            val summaries = listOf(SessionSummary(checkNotNull(session.session), lastSeq = session.lastSeq))
            store.replaceSummaries(summaries)
            val repository =
                repository(
                    source = { _, _ -> flow { throw IOException("Network is unreachable") } },
                    store = store,
                    conditions = FakeConditions(network = false),
                )

            assertEquals(session, repository.session(session.sessionId).first())
            assertEquals(summaries.toListItems(), repository.sessions().first())
            backgroundScope.launch { repository.session(session.sessionId).collect {} }
            repository.connection.first { it == ConnectionState.Offline(retryAtMs = null) }
        }

    @Test
    fun a_socket_refused_with_401_reads_as_unauthorized_and_is_not_opened_again() =
        runTest {
            val source = RejectingSource(status = 401)
            val repository = repository(source = source)

            openAndWait(repository)

            assertEquals(ConnectionState.Unauthorized, repository.connection.value)
            assertEquals(1, source.opens)
        }

    @Test
    fun a_socket_refused_with_503_is_retried() =
        runTest {
            val source = RejectingSource(status = 503)
            val repository = repository(source = source)

            openAndWait(repository)

            assertTrue(source.opens > 1, "opened ${source.opens} times")
        }

    private fun TestScope.repository(
        source: EventSource,
        store: EventStore = InMemoryEventStore(),
        conditions: AppConditions = FakeConditions(),
    ) = SyncingSessionRepository(
        http = { throw IOException("Network is unreachable") },
        source = source,
        store = store,
        conditions = conditions,
        credentials = pairedWith(),
        scope = backgroundScope,
        nowMs = { testScheduler.currentTime },
        random = Random(seed = 1),
    )

    private fun TestScope.openAndWait(repository: SyncingSessionRepository) {
        backgroundScope.launch { repository.session("ses_weather_app").collect {} }
        testScheduler.advanceTimeBy(2.minutes)
    }

    private fun recordedLog(): List<Event> =
        checkNotNull(javaClass.getResource("/events/recorded/with-subagents.events.jsonl"))
            .readText()
            .lines()
            .filter { it.isNotBlank() }
            .map { protocolJson.decodeFromString(Event.serializer(), it) }
}

/** A bridge that answers every socket handshake with HTTP [status]. */
private class RejectingSource(
    private val status: Int,
) : EventSource {
    var opens = 0

    override fun open(
        url: String,
        token: String,
    ) = flow<SocketFrame> {
        opens++
        throw HandshakeRejectedException(status)
    }
}
