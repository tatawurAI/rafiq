package ai.tatawur.rafiq.bridge.tmux

import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicLong

/**
 * Carries a control client's items from its reader thread to the coroutine that sends them to the phone.
 *
 * The reader must never wait for the phone: a command reply is read by the same thread, so a reader stuck
 * behind a slow socket would stall every command too. Instead, when more than [maxPendingBytes] of output
 * is waiting, the queue drops output until the next screen and tells the consumer with
 * [TermItem.Overflowed], which answers by asking for one. A screen replaces everything dropped.
 */
internal class TermQueue(
    private val maxPendingBytes: Int,
) {
    private val channel = Channel<TermItem>(Channel.UNLIMITED)
    private val pending = AtomicLong()

    @Volatile
    private var dropping = false

    /** Called on the reader thread. Never waits. */
    fun offer(item: TermItem) {
        when (item) {
            is TermItem.Output -> {
                offerOutput(item)
            }

            is TermItem.Screen, TermItem.ScreenFailed -> {
                dropping = false
                channel.trySend(item)
            }

            else -> {
                channel.trySend(item)
            }
        }
    }

    private fun offerOutput(item: TermItem.Output) {
        if (dropping) return
        if (pending.addAndGet(item.bytes.size.toLong()) > maxPendingBytes) {
            pending.addAndGet(-item.bytes.size.toLong())
            dropping = true
            channel.trySend(TermItem.Overflowed)
        } else {
            channel.trySend(item)
        }
    }

    /** The next item, or null once the queue is closed. */
    suspend fun next(): TermItem? {
        val item = channel.receiveCatching().getOrNull() ?: return null
        if (item is TermItem.Output) pending.addAndGet(-item.bytes.size.toLong())
        return item
    }

    fun close() {
        channel.close()
    }
}

/**
 * Counts the open terminal sockets, per device and in total, so one phone cannot start every tmux client
 * the server will give it.
 */
class TerminalSlots(
    private val limits: TerminalLimits,
) {
    private val perDevice = mutableMapOf<String, Int>()
    private var total = 0

    /** A slot for [deviceId], or null when the device or the bridge is at its limit. Close it when the socket ends. */
    @Synchronized
    fun acquire(deviceId: String): AutoCloseable? {
        val held = perDevice[deviceId] ?: 0
        if (held >= limits.maxPerDevice || total >= limits.maxTotal) return null
        perDevice[deviceId] = held + 1
        total++
        var released = false
        return AutoCloseable { release(deviceId, released.also { released = true }) }
    }

    @Synchronized
    private fun release(
        deviceId: String,
        alreadyReleased: Boolean,
    ) {
        if (alreadyReleased) return
        val held = perDevice[deviceId] ?: return
        if (held <= 1) perDevice.remove(deviceId) else perDevice[deviceId] = held - 1
        total--
    }
}

/** Limits how much input one socket may send: a size for each frame and a total for each second. */
internal class InputBudget(
    private val maxFrameBytes: Int,
    private val maxBytesPerSecond: Int,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private var windowStart = nowNanos()
    private var used = 0L

    /** False when [size] bytes would break either limit. */
    fun allows(size: Int): Boolean {
        if (size > maxFrameBytes) return false
        val now = nowNanos()
        if (now - windowStart >= NANOS_PER_SECOND) {
            windowStart = now
            used = 0
        }
        used += size
        return used <= maxBytesPerSecond
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
