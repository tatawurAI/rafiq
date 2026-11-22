package ai.tatawur.rafiq.client.terminal

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
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

    /** Queues [frame] from the socket's thread. False when it does not fit. */
    fun offer(frame: TerminalFrame): Boolean {
        val size = frame.byteSize()
        if (queuedBytes.addAndGet(size) > maxBytes || !channel.trySend(frame).isSuccess) {
            queuedBytes.addAndGet(-size)
            return false
        }
        return true
    }

    /** Ends [frames]: normally for a null [cause], with [cause] otherwise. */
    fun close(cause: Throwable? = null) {
        channel.close(cause)
    }

    /** The queued frames, in order. Collect it once. */
    fun frames(): Flow<TerminalFrame> = channel.receiveAsFlow().onEach { queuedBytes.addAndGet(-it.byteSize()) }

    private fun TerminalFrame.byteSize(): Long =
        when (this) {
            is TerminalFrame.Binary -> bytes.size.toLong()
            is TerminalFrame.Text -> text.length.toLong()
            is TerminalFrame.Opened -> 0L
        }

    companion object {
        const val DEFAULT_MAX_FRAMES = 256
        const val DEFAULT_MAX_BYTES = 2L * 1024 * 1024
    }
}
