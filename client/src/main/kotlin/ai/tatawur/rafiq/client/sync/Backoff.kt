package ai.tatawur.rafiq.client.sync

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How long to wait before reconnecting. The ceiling doubles per failed attempt from [initial] up to
 * [max], and the wait is a random point in the upper half of it, so phones that lost the bridge at the
 * same moment do not all come back at the same moment.
 */
class Backoff(
    private val random: Random,
    private val initial: Duration = 1.seconds,
    private val max: Duration = 30.seconds,
) {
    /** The wait before attempt number [attempt], counting from 0. */
    fun delayFor(attempt: Int): Duration {
        // The shift is capped so the multiplication cannot overflow. At the defaults, 2^5 s already passes max.
        val ceiling = (initial * (1 shl attempt.coerceIn(0, MAX_SHIFT))).coerceAtMost(max)
        return ceiling * (HALF + random.nextDouble() * HALF)
    }

    private companion object {
        const val MAX_SHIFT = 10
        const val HALF = 0.5
    }
}
