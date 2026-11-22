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
 * The size a session's windows had before the first view of this bridge took them, so the last view to leave
 * can give it back. Shared by every view, because two phones may show one session: the size to give back is the
 * one from before the first of them.
 */
class SessionSizes {
    private class Held(
        val size: Pair<Int, Int>,
        val views: Int,
    )

    private val held = mutableMapOf<String, Held>()

    /** A view of [sessionId] opened, whose window had [size] just before. Only the first view's size is kept. */
    @Synchronized
    fun claim(
        sessionId: String,
        size: Pair<Int, Int>,
    ) {
        val now = held[sessionId]
        held[sessionId] = if (now == null) Held(size, 1) else Held(now.size, now.views + 1)
    }

    /** A view of [sessionId] closed. The earlier size when it was the last view, otherwise null. */
    @Synchronized
    fun release(sessionId: String): Pair<Int, Int>? {
        val now = held.remove(sessionId) ?: return null
        if (now.views > 1) held[sessionId] = Held(now.size, now.views - 1)
        return now.size.takeIf { now.views == 1 }
    }
}

/** What [TerminalSlots.acquire] decided. */
sealed interface SlotResult {
    /** The socket may open. Close [slot] when it ends. */
    class Granted(
        val slot: AutoCloseable,
    ) : SlotResult

    /** The device or the bridge already has all the sockets it may have open. */
    data object AtLimit : SlotResult

    /** The device opened terminals too often. It may try again in a moment. */
    data object TooManyOpens : SlotResult
}

/**
 * Counts the open terminal sockets, per device and in total, so one phone cannot start every tmux client
 * the server will give it. It also limits how often a device may open one: every open starts a tmux client
 * and a view session, so a phone that connects and leaves in a loop would cost the server real work.
 */
class TerminalSlots(
    private val limits: TerminalLimits,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private val perDevice = mutableMapOf<String, Int>()
    private val opens = mutableMapOf<String, TokenBucket>()
    private var total = 0

    /** A slot for [deviceId], or why not. A refusal for a full house costs the device nothing. */
    @Suppress("ReturnCount") // Each refusal leaves at once.
    @Synchronized
    fun acquire(deviceId: String): SlotResult {
        val held = perDevice[deviceId] ?: 0
        if (held >= limits.maxPerDevice || total >= limits.maxTotal) return SlotResult.AtLimit
        val bucket =
            opens.getOrPut(
                deviceId,
            ) { TokenBucket(limits.opensBurst, limits.opensPerMinute / SECONDS_PER_MINUTE, nowNanos) }
        if (!bucket.tryTake()) return SlotResult.TooManyOpens
        perDevice[deviceId] = held + 1
        total++
        var released = false
        return SlotResult.Granted(AutoCloseable { release(deviceId, released.also { released = true }) })
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

/** A token bucket: up to [burst] at once, then [perSecond] a second. For things a phone has no reason to spam. */
internal class TokenBucket(
    private val burst: Int,
    private val perSecond: Double,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private var tokens = burst.toDouble()
    private var last = nowNanos()

    /** True and spends a token when one is left. */
    @Synchronized
    fun tryTake(): Boolean {
        val now = nowNanos()
        tokens = minOf(burst.toDouble(), tokens + (now - last) * perSecond / NANOS_PER_SECOND)
        last = now
        if (tokens < 1.0) return false
        tokens -= 1.0
        return true
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}

private const val SECONDS_PER_MINUTE = 60.0
