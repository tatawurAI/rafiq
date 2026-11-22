package ai.tatawur.rafiq.terminal

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle

/**
 * Runs [hold] while [lifecycle] is at least started, cancelling it on every stop and starting it
 * again on the next start. Terminal holds its socket this way: the app going to the background
 * stops it, and leaving the screen (back, the toggle, the cover screen) cancels the caller.
 */
internal suspend fun holdWhileStarted(
    lifecycle: Lifecycle,
    hold: suspend () -> Unit,
) = lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { hold() }
