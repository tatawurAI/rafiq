package ai.tatawur.rafiq.bridge.server

import ai.tatawur.rafiq.bridge.store.EventLog
import ai.tatawur.rafiq.bridge.store.SlowConsumerException
import ai.tatawur.rafiq.protocol.Event

/**
 * Feeds one client a session's events: first the stored events after a given `seq`, then live ones,
 * with no gap and no repeat where the two meet.
 *
 * The order that makes it work: subscribe first, so nothing appended from then on can be missed, then
 * read the log until it has nothing newer, then drain what was buffered meanwhile, skipping what the
 * replay already sent.
 *
 * @param bufferSize how many live events may wait while the client is slow, replay included.
 *   Past that the stream fails with [SlowConsumerException] and the client reconnects and replays.
 */
class EventStreamer(
    private val log: EventLog,
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    /**
     * Calls [send] for every event of [sessionId] with `seq` greater than [afterSeq], in order, and keeps
     * going as events arrive. Runs until cancelled.
     *
     * @throws SlowConsumerException when [send] is too slow for the live events to wait.
     */
    suspend fun stream(
        sessionId: String,
        afterSeq: Long,
        send: suspend (Event) -> Unit,
    ) {
        val subscription = log.subscribe(sessionId, bufferSize)
        try {
            var last = afterSeq
            while (true) {
                val page = log.after(sessionId, last, pageSize)
                if (page.isEmpty()) break
                for (event in page) {
                    send(event)
                    last = event.seq
                }
            }
            for (event in subscription.events) {
                if (event.seq <= last) continue
                send(event)
                last = event.seq
            }
        } finally {
            subscription.close()
        }
    }

    companion object {
        const val DEFAULT_BUFFER_SIZE = 1024
        const val DEFAULT_PAGE_SIZE = 500
    }
}
