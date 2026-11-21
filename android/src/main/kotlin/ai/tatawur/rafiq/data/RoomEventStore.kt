package ai.tatawur.rafiq.data

import ai.tatawur.rafiq.client.sync.EventStore
import ai.tatawur.rafiq.protocol.Event
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.api.SessionSummary
import ai.tatawur.rafiq.protocol.protocolJson

/** The [EventStore] the app uses, over [CacheDatabase]. Rows that no longer parse are skipped. */
class RoomEventStore(
    private val dao: CacheDao,
) : EventStore {
    override suspend fun events(sessionId: String): List<Event> =
        dao.events(sessionId).mapNotNull { row ->
            decodeOrNull(row.payloadJson) {
                Event(
                    row.sessionId,
                    row.seq,
                    row.timestampMs,
                    protocolJson.decodeFromString(EventPayload.serializer(), it),
                )
            }
        }

    override suspend fun append(event: Event) {
        dao.putEvent(
            EventRow(
                sessionId = event.sessionId,
                seq = event.seq,
                timestampMs = event.timestampMs,
                payloadJson = protocolJson.encodeToString(EventPayload.serializer(), event.payload),
            ),
        )
    }

    override suspend fun summaries(): List<SessionSummary> =
        dao.summaries().mapNotNull { row ->
            decodeOrNull(row.summaryJson) { protocolJson.decodeFromString(SessionSummary.serializer(), it) }
        }

    override suspend fun replaceSummaries(summaries: List<SessionSummary>) {
        dao.replaceSummaries(
            summaries.map { SummaryRow(it.session.id, protocolJson.encodeToString(SessionSummary.serializer(), it)) },
        )
    }

    override suspend fun clear() = dao.clear()

    private inline fun <T> decodeOrNull(
        json: String,
        decode: (String) -> T,
    ): T? =
        try {
            decode(json)
        } catch (_: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException. A skipped event leaves a gap, and the
            // fold stops before it, so the socket replays it from the bridge.
            null
        }
}
