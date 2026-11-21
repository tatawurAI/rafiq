package ai.tatawur.rafiq.client.sync

import ai.tatawur.rafiq.protocol.Event
import ai.tatawur.rafiq.protocol.api.SessionSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// In-memory ports for the sync tests. They hold state and nothing else, so a test reads as the
// conditions it sets up.

internal class FakeConditions(
    network: Boolean = true,
) : AppConditions {
    override val foreground: StateFlow<Boolean> = MutableStateFlow(true)
    val network = MutableStateFlow(network)
    override val networkAvailable: StateFlow<Boolean> = this.network
}

internal class InMemoryEventStore : EventStore {
    private val events = sortedMapOf<Pair<String, Long>, Event>(compareBy({ it.first }, { it.second }))
    private var summaries = emptyList<SessionSummary>()

    override suspend fun events(sessionId: String) = events.values.filter { it.sessionId == sessionId }

    override suspend fun append(event: Event) {
        events[event.sessionId to event.seq] = event
    }

    override suspend fun summaries() = summaries

    override suspend fun replaceSummaries(summaries: List<SessionSummary>) {
        this.summaries = summaries
    }

    override suspend fun clear() {
        events.clear()
        summaries = emptyList()
    }
}

internal fun pairedWith(token: String = "test-token") =
    MutableStateFlow<BridgeCredentials?>(BridgeCredentials("https://bridge.example.ts.net", token))
