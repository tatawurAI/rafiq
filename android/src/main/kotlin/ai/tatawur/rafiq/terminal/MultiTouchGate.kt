package ai.tatawur.rafiq.terminal

import android.view.MotionEvent

/**
 * Drops multi-finger touches while the Terminal view is on screen, so the emulator's pinch zoom
 * never starts: the library has no switch for it. [ai.tatawur.rafiq.MainActivity] consults it
 * before it dispatches a touch.
 */
object MultiTouchGate {
    @Volatile var active: Boolean = false

    /** True when [event] carries a second finger and must not reach the screen. */
    fun blocks(event: MotionEvent): Boolean =
        active && (event.pointerCount > 1 || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN)
}
