package ai.tatawur.rafiq.client.terminal

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The frames a `WS /terminal` socket has received and the view has not applied yet, bounded in
 * count and in bytes. A busy pane on a slow phone would otherwise grow this without limit. When a
 * frame does not fit, [offer] says so and the port closes the socket with a
 * [TerminalOverflowException]. [TerminalLink] then opens a new socket, which starts with a fresh
 * snapshot, so nothing on screen is lost.
 */
class InboundFrames(
    maxFrames: Int = DEFAULT_MAX_FRAMES,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val channel = Channel<TerminalFrame>(maxFrames)
    private val queuedBytes = AtomicLong()
    private val queuedFrames = AtomicInteger()

    /**
     * Queues [frame] from the socket's thread. False when it does not fit. A frame always fits an
     * empty queue, however large, so one big frame can never make every socket overflow.
     */
    fun offer(frame: TerminalFrame): Boolean {
        val size = frame.byteSize()
        val first = queuedFrames.incrementAndGet() == 1
        val bytes = queuedBytes.addAndGet(size)
        val fits = (first || bytes <= maxBytes) && channel.trySend(frame).isSuccess
        if (!fits) {
            queuedFrames.decrementAndGet()
            queuedBytes.addAndGet(-size)
        }
        return fits
    }

    /** Ends [frames]: normally for a null [cause], with [cause] otherwise. */
    fun close(cause: Throwable? = null) {
        channel.close(cause)
    }

    /** The queued frames, in order. Collect it once. */
    fun frames(): Flow<TerminalFrame> =
        channel.receiveAsFlow().onEach {
            queuedBytes.addAndGet(-it.byteSize())
            queuedFrames.decrementAndGet()
        }

    private fun TerminalFrame.byteSize(): Long =
        when (this) {
            is TerminalFrame.Binary -> bytes.size.toLong()
            is TerminalFrame.Text -> text.length.toLong()
            is TerminalFrame.Opened -> 0L
        }

    companion object {
        const val DEFAULT_MAX_FRAMES = 4096
        const val DEFAULT_MAX_BYTES = 2L * 1024 * 1024
    }
}
