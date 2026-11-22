package ai.tatawur.rafiq.client

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * How long a passed biometric check lets you answer approvals without another one. The window is short,
 * and it ends at once when the app goes to the background, so a phone left unlocked on a desk does not
 * stay able to approve.
 */
class UnlockWindow(
    private val validFor: Duration = DEFAULT_VALID_FOR,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private var openedAt: TimeMark? = null

    /** True while a check passed less than [validFor] ago and the app has not left the foreground since. */
    @get:Synchronized
    val isOpen: Boolean
        get() = openedAt?.let { it.elapsedNow() < validFor } == true

    /** A check just passed. */
    @Synchronized
    fun passed() {
        openedAt = timeSource.markNow()
    }

    /** The app went to the background, or the setting changed. */
    @Synchronized
    fun close() {
        openedAt = null
    }

    companion object {
        val DEFAULT_VALID_FOR: Duration = 30.seconds
    }
}
