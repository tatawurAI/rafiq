package ai.tatawur.rafiq.sessions

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.client.CommandResult
import ai.tatawur.rafiq.client.IDENTITY_MISMATCH
import androidx.annotation.StringRes

/**
 * A short line a screen shows after a command did not go through. None of these is an error
 * page: the screen stays as it was, with its cached state.
 */
sealed interface Feedback {
    /** The string resource the screen shows. [Failed] fills its one placeholder with the bridge's message. */
    @get:StringRes
    val textRes: Int

    /** A turn is still running, so the bridge did not take the prompt. Informational, not an error. */
    data object StillWorking : Feedback {
        override val textRes = R.string.feedback_still_working
    }

    data object AlreadyAnswered : Feedback {
        override val textRes = R.string.feedback_already_answered
    }

    data object Offline : Feedback {
        override val textRes = R.string.feedback_offline
    }

    data object NotFound : Feedback {
        override val textRes = R.string.feedback_not_found
    }

    data object Unauthorized : Feedback {
        override val textRes = R.string.feedback_unauthorized
    }

    /** Your server accepts only its owner's Tailscale login. Not a token problem: the pairing stays. */
    data object IdentityRefused : Feedback {
        override val textRes = R.string.connection_identity_refused
    }

    data class Failed(
        val message: String,
    ) : Feedback {
        override val textRes = R.string.feedback_failed
    }
}

/** The feedback for a finished command, or null when it went through. */
fun CommandResult<*>.feedback(): Feedback? =
    when (this) {
        is CommandResult.Ok -> null
        CommandResult.Busy -> Feedback.StillWorking
        CommandResult.Gone -> Feedback.AlreadyAnswered
        CommandResult.Offline -> Feedback.Offline
        CommandResult.NotFound -> Feedback.NotFound
        CommandResult.Unauthorized -> Feedback.Unauthorized
        is CommandResult.Failed -> if (code == IDENTITY_MISMATCH) Feedback.IdentityRefused else Feedback.Failed(message)
    }

/**
 * Whether an approval's buttons come back after this answer. Not after Ok, and not after Gone or
 * NotFound: the bridge holds no open approval with that id, so a second tap could only fail the same
 * way. The approval leaves the screen when its `approval_resolved` event arrives.
 */
internal fun CommandResult<*>.canRetryAnswer(): Boolean =
    when (this) {
        is CommandResult.Ok, CommandResult.Gone, CommandResult.NotFound -> false
        CommandResult.Busy, CommandResult.Offline, CommandResult.Unauthorized, is CommandResult.Failed -> true
    }
