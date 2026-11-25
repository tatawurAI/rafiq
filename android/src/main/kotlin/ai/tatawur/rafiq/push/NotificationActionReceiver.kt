package ai.tatawur.rafiq.push

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.client.CommandResult
import ai.tatawur.rafiq.data.RafiqApplication
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Deny and Reply, straight from a notification. Not exported: only the app's own PendingIntents reach it.
 * The answer goes to your server while the receiver holds the broadcast open, and the notification is then
 * replaced with what happened, so a lost answer never looks sent.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val push = (context.applicationContext as RafiqApplication).container.push
        val tag = intent.getStringExtra(EXTRA_TAG)
        val sessionId = intent.getStringExtra(EXTRA_SESSION)
        val send = sessionId?.let { sender(push, intent, it) }
        if (tag == null || sessionId == null || send == null) return
        val pending = goAsync()
        push.scope.launch {
            try {
                // A broadcast receiver has ten seconds. Past this, the notification says it could not be sent.
                val result = withTimeoutOrNull(ANSWER_BUDGET_MS) { send() } ?: CommandResult.Offline
                report(context, push, tag, sessionId, result)
            } finally {
                pending.finish()
            }
        }
    }

    /** The call this action makes, or null when the intent lacks what it needs, such as an empty reply. */
    private fun sender(
        push: PushModule,
        intent: Intent,
        sessionId: String,
    ): (suspend () -> CommandResult<Unit>)? =
        when (intent.action) {
            ACTION_DENY -> {
                val approvalId = intent.getStringExtra(EXTRA_APPROVAL)
                val optionId = intent.getStringExtra(EXTRA_OPTION)
                if (approvalId != null && optionId != null) suspend { push.api.approve(approvalId, optionId) } else null
            }

            ACTION_REPLY -> {
                replyText(intent)?.let { text -> suspend { push.api.prompt(sessionId, text) } }
            }

            else -> {
                null
            }
        }

    private fun replyText(intent: Intent): String? =
        RemoteInput
            .getResultsFromIntent(intent)
            ?.getCharSequence(REPLY_KEY)
            ?.toString()
            ?.takeIf { it.isNotBlank() }

    private fun report(
        context: Context,
        push: PushModule,
        tag: String,
        sessionId: String,
        result: CommandResult<Unit>,
    ) {
        val notifier = push.notifier
        when (result) {
            is CommandResult.Ok -> {
                notifier.outcome(tag, sessionId, context.getString(R.string.push_result_sent), brief = true)
            }

            CommandResult.Gone -> {
                notifier.outcome(tag, sessionId, context.getString(R.string.push_result_already_answered), brief = true)
            }

            CommandResult.Busy -> {
                notifier.outcome(tag, sessionId, context.getString(R.string.push_result_busy), brief = false)
            }

            is CommandResult.Failed -> {
                notifier.outcome(
                    tag,
                    sessionId,
                    context.getString(R.string.push_result_failed, result.message),
                    brief = false,
                )
            }

            // Your server no longer accepts this phone. The pairing is cleared elsewhere; nothing more to say.
            CommandResult.Unauthorized -> {
                notifier.cancel(tag)
            }

            CommandResult.Offline, CommandResult.NotFound, is CommandResult.RateLimited -> {
                notifier.outcome(tag, sessionId, context.getString(R.string.push_result_unreachable), brief = false)
            }
        }
    }

    companion object {
        /** The RemoteInput key of a reply's text. */
        const val REPLY_KEY = "reply"

        private const val ACTION_DENY = "ai.tatawur.rafiq.push.DENY"
        private const val ACTION_REPLY = "ai.tatawur.rafiq.push.REPLY"
        private const val EXTRA_TAG = "tag"
        private const val EXTRA_SESSION = "session_id"
        private const val EXTRA_APPROVAL = "approval_id"
        private const val EXTRA_OPTION = "option_id"
        private const val ANSWER_BUDGET_MS = 8_000L

        /** Deny [approvalId] with [optionId], then update the notification under [tag]. */
        fun deny(
            context: Context,
            approvalId: String,
            optionId: String,
            sessionId: String,
            tag: String,
        ): Intent =
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(ACTION_DENY)
                .putExtra(EXTRA_APPROVAL, approvalId)
                .putExtra(EXTRA_OPTION, optionId)
                .putExtra(EXTRA_SESSION, sessionId)
                .putExtra(EXTRA_TAG, tag)

        /** Send the RemoteInput text to [sessionId] as a prompt, then update the notification under [tag]. */
        fun reply(
            context: Context,
            sessionId: String,
            tag: String,
        ): Intent =
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(ACTION_REPLY)
                .putExtra(EXTRA_SESSION, sessionId)
                .putExtra(EXTRA_TAG, tag)
    }
}
