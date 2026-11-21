package ai.tatawur.rafiq.client.sync

import ai.tatawur.rafiq.client.ConnectionState
import ai.tatawur.rafiq.client.SessionReducer
import ai.tatawur.rafiq.protocol.Event
import ai.tatawur.rafiq.protocol.protocolJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.readLines
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The M2 promise that airplane mode loses nothing: a socket that drops mid-stream, a phone with no
 * network for a while, and a bridge whose replays overlap what the app already has. Every stream
 * under `events/` in the test resources runs through the real sync logic.
 */
@OptIn(ExperimentalCoroutinesApi::class) // The test scheduler's clock stands in for the wall clock.
class ReconnectReplayTest {
    @Test
    fun every_event_is_applied_exactly_once_across_drops_and_replays() {
        val streams = streamFiles()
        assertTrue(streams.isNotEmpty(), "No event streams found under the test resources.")
        for (file in streams) replayThroughDrops(file)
    }

    private fun replayThroughDrops(file: Path) =
        runTest {
            val lines = file.readLines().filter { it.isNotBlank() }
            val log = lines.map { protocolJson.decodeFromString(Event.serializer(), it) }
            val sessionId = log.first().sessionId
            val conditions = FakeConditions()
            // First socket drops a third of the way in and takes the network with it, like airplane
            // mode. The second drops after four frames. The third streams the rest and stays open.
            val firstDrop = lines.size / 3
            val source = ScriptedSource(lines, dropAfterFrames = ArrayDeque(listOf(firstDrop, 4)), conditions.network)
            val store = InMemoryEventStore()
            val repository =
                SyncingSessionRepository(
                    http = { throw IOException("This test makes no REST calls.") },
                    source = source,
                    store = store,
                    conditions = conditions,
                    credentials = MutableStateFlow(BridgeCredentials("https://bridge.example.ts.net", "test-token")),
                    scope = backgroundScope,
                    nowMs = { testScheduler.currentTime },
                    random = Random(seed = 1),
                )
            val sawWaitingForNetwork =
                backgroundScope.launch {
                    repository.connection.first { it == ConnectionState.Offline(retryAtMs = null) }
                    conditions.network.value = true
                }

            val seen =
                repository
                    .session(sessionId)
                    .transformWhile { state ->
                        emit(state)
                        state.lastSeq != log.last().seq
                    }.toList()

            assertTrue(sawWaitingForNetwork.isCompleted, "${file.fileName}: never waited for the network.")
            // Every state the screen saw, not only the last, is a clean fold of the log up to its seq.
            // A final snapshot can hide a delta applied twice, but the states before it cannot.
            for (state in seen) {
                val clean = SessionReducer.fold(sessionId, log.filter { it.seq <= state.lastSeq })
                assertEquals(clean, state, "${file.fileName}: state at seq ${state.lastSeq}")
            }
            assertEquals(log.last().seq, seen.last().lastSeq, "${file.fileName}: reached the end of the log")
            assertEquals(log.map { it.seq }, store.events(sessionId).map { it.seq }, "${file.fileName}: cached seqs")
            // Each reconnect resumed after the newest event the app had applied, never from the start.
            val resumedAfter = source.requestedAfter
            assertEquals(3, resumedAfter.size, "${file.fileName}: connections")
            assertEquals(listOf(0L, log[firstDrop - 1].seq), resumedAfter.take(2), "${file.fileName}: resume points")
            assertTrue(resumedAfter[2] > resumedAfter[1], "${file.fileName}: the second socket made progress")
        }

    private fun streamFiles(): List<Path> {
        val root = Paths.get(checkNotNull(javaClass.getResource("/events")).toURI())
        return Files.walk(root).use { paths -> paths.filter { it.extension == "jsonl" }.sorted().toList() }
    }
}

/** A bridge that replays from `after`, starting two events early so replays overlap, and drops on cue. */
private class ScriptedSource(
    private val log: List<String>,
    private val dropAfterFrames: ArrayDeque<Int>,
    private val network: MutableStateFlow<Boolean>,
) : EventSource {
    val requestedAfter = mutableListOf<Long>()
    private var connections = 0

    override fun open(
        url: String,
        token: String,
    ) = flow {
        val after = url.substringAfter("after=").toLong()
        requestedAfter += after
        connections++
        emit(SocketFrame.Opened)
        val replay = log.filter { protocolJson.decodeFromString(Event.serializer(), it).seq > after - 2 }
        val dropAt = dropAfterFrames.removeFirstOrNull()
        replay.forEachIndexed { index, line ->
            if (index == dropAt) {
                if (connections == 1) network.value = false
                throw IOException("Connection reset.")
            }
            emit(SocketFrame.Text(line))
        }
        awaitCancellation()
    }
}
