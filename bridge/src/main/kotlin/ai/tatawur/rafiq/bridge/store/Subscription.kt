package ai.tatawur.rafiq.bridge.store

import ai.tatawur.rafiq.protocol.Event
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/** Thrown from [Subscription.events] when the reader fell behind and the log gave up waiting for it. */
class SlowConsumerException(
    message: String,
) : Exception(message)

/**
 * A live feed of one session's new events, in `seq` order.
 *
 * The buffer is bounded. When it is full the subscription closes with a [SlowConsumerException]
 * instead of making the writer wait, so one slow reader cannot hold up the session or other readers.
 * Events already buffered are still delivered before the exception.
 */
class Subscription internal constructor(
    private val channel: Channel<Event>,
    private val onClose: (Subscription) -> Unit,
) {
    /** Delivered events. Iterating ends by throwing [SlowConsumerException] when the reader fell behind. */
    val events: ReceiveChannel<Event> get() = channel

    internal fun offer(event: Event) {
        val result = channel.trySend(event)
        if (result.isFailure && !result.isClosed) {
            channel.close(SlowConsumerException("The reader is too far behind to keep up with the session."))
            onClose(this)
        }
    }

    /** Stops delivery and drops anything still buffered. Safe to call more than once. */
    fun close() {
        channel.cancel()
        onClose(this)
    }
}
