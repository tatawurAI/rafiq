package ai.tatawur.rafiq.push

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.approve.ApproveActivity
import ai.tatawur.rafiq.client.ApprovalCard
import ai.tatawur.rafiq.client.ApprovalText
import ai.tatawur.rafiq.client.push.PushNotice
import ai.tatawur.rafiq.client.push.notificationLabel
import ai.tatawur.rafiq.protocol.ApprovalOptionKind
import ai.tatawur.rafiq.protocol.Session
import ai.tatawur.rafiq.protocol.api.PushKind
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon

/**
 * Posts and removes the app's notifications. Each one is private on the lock screen: the lock screen shows
 * only "An agent needs you", never a session name or command text.
 *
 * An approval offers Deny, which answers at once, and Approve, which needs the phone unlocked and then opens
 * [ApproveActivity] with the full request. A finished turn offers Reply, which also needs the phone unlocked,
 * because a message to the agent can make it do anything.
 */
@Suppress("TooManyFunctions") // One builder per notification kind, and the parts they share.
internal class PushNotifier(
    private val context: Context,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun show(notice: PushNotice) {
        when (notice) {
            is PushNotice.Approval -> post(approvalTag(notice.approval.id), approval(notice.session, notice.approval))
            is PushNotice.TurnDone -> post(turnTag(notice.session.id), turnDone(notice.session, notice.lastLine))
            is PushNotice.Failed -> post(errorTag(notice.session.id), failed(notice.session))
            is PushNotice.Generic -> post(genericTag(notice), generic(notice))
            is PushNotice.Clear -> cancel(approvalTag(notice.approvalId))
            PushNotice.Nothing -> Unit
        }
    }

    /** Removes what a session's notifications said once you open it. Approvals stay until answered. */
    fun sessionOpened(sessionId: String) {
        cancel(turnTag(sessionId))
        cancel(errorTag(sessionId))
    }

    fun cancel(tag: String) {
        manager?.cancel(tag, NOTIFICATION_ID)
    }

    /**
     * Replaces the notification under [tag] with a short outcome of an answer sent from it. A [brief] one, such
     * as "Sent", goes away on its own.
     */
    fun outcome(
        tag: String,
        sessionId: String,
        text: String,
        brief: Boolean,
    ) {
        val builder =
            base(PushChannels.STATUS, sessionId, tag)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
        if (brief) builder.setTimeoutAfter(BRIEF_OUTCOME_MS)
        post(tag, builder.build())
    }

    private fun approval(
        session: Session,
        card: ApprovalCard,
    ): Notification {
        val tag = approvalTag(card.id)
        val text = ApprovalText.of(card, expanded = false)
        val body =
            buildString {
                append(text.lines.joinToString("\n"))
                if (text.hiddenLines > 0 || text.cut) {
                    val more =
                        context.resources.getQuantityString(
                            R.plurals.push_approval_more,
                            text.lineCount,
                            text.lineCount,
                        )
                    append("\n\n").append(more)
                }
            }
        val builder =
            base(PushChannels.APPROVALS, session.id, tag)
                .setContentTitle(context.getString(R.string.push_approval_title, session.notificationLabel()))
                .setContentText(text.lines.firstOrNull().orEmpty())
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setCategory(Notification.CATEGORY_REMINDER)
        card.expiresAtMs?.let { at -> (at - nowMs()).takeIf { it > 0 }?.let(builder::setTimeoutAfter) }
        card.options.firstOrNull { it.kind == ApprovalOptionKind.DENY }?.let { deny ->
            val intent = NotificationActionReceiver.deny(context, card.id, deny.id, session.id, tag)
            builder.addAction(action(R.string.push_action_deny, broadcast(tag, DENY, intent, mutable = false)).build())
        }
        if (card.options.any { it.kind != ApprovalOptionKind.DENY }) {
            val open =
                PendingIntent.getActivity(
                    context,
                    requestCode(tag, APPROVE),
                    ApproveActivity.intent(context, card.id, session.id),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            // The phone must be unlocked before Approve does anything (ADR 0010).
            builder.addAction(action(R.string.push_action_approve, open).setAuthenticationRequired(true).build())
        }
        return builder.build()
    }

    private fun turnDone(
        session: Session,
        lastLine: String?,
    ): Notification {
        val tag = turnTag(session.id)
        val text = lastLine?.takeIf { it.isNotBlank() } ?: context.getString(R.string.push_turn_text)
        val input =
            RemoteInput
                .Builder(
                    NotificationActionReceiver.REPLY_KEY,
                ).setLabel(context.getString(R.string.push_reply_hint))
                .build()
        // RemoteInput writes your text into the intent, so this one PendingIntent has to be mutable. Its intent
        // names the receiver explicitly, so nothing else can be substituted.
        val reply = broadcast(tag, REPLY, NotificationActionReceiver.reply(context, session.id, tag), mutable = true)
        return base(PushChannels.TURNS, session.id, tag)
            .setContentTitle(context.getString(R.string.push_turn_title, session.notificationLabel()))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .addAction(
                action(R.string.push_action_reply, reply)
                    .addRemoteInput(input)
                    .setAllowGeneratedReplies(false)
                    .setAuthenticationRequired(true)
                    .build(),
            ).build()
    }

    private fun failed(session: Session): Notification =
        base(PushChannels.ERRORS, session.id, errorTag(session.id))
            .setContentTitle(context.getString(R.string.push_error_title, session.notificationLabel()))
            .setContentText(context.getString(R.string.push_error_text))
            .setAutoCancel(true)
            .build()

    private fun generic(notice: PushNotice.Generic): Notification {
        val (channel, text) =
            when (notice.kind) {
                PushKind.TURN_DONE -> PushChannels.TURNS to R.string.push_generic_turn
                PushKind.ERROR -> PushChannels.ERRORS to R.string.push_generic_error
                else -> PushChannels.APPROVALS to R.string.push_generic_approval
            }
        return base(channel, notice.sessionId, genericTag(notice))
            .setContentText(context.getString(text))
            .setAutoCancel(true)
            .build()
    }

    /** What every notification shares: the icon, the tap that opens the session, and the generic public version. */
    private fun base(
        channel: String,
        sessionId: String,
        tag: String,
    ): Notification.Builder {
        val publicVersion =
            Notification
                .Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentText(context.getString(R.string.push_public_text))
                .build()
        val open =
            PendingIntent.getActivity(
                context,
                requestCode(tag, OPEN),
                OpenSessionIntent.create(context, sessionId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return Notification
            .Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
    }

    private fun action(
        label: Int,
        intent: PendingIntent,
    ) = Notification.Action.Builder(
        Icon.createWithResource(context, R.drawable.ic_notification),
        context.getString(label),
        intent,
    )

    private fun broadcast(
        tag: String,
        kind: Int,
        intent: Intent,
        mutable: Boolean,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode(tag, kind),
            intent,
            (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE) or
                PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun post(
        tag: String,
        notification: Notification,
    ) {
        // Without the notification permission this is a no-op, which is what you asked for.
        if (manager?.areNotificationsEnabled() == true) manager.notify(tag, NOTIFICATION_ID, notification)
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val BRIEF_OUTCOME_MS = 4_000L
        private const val OPEN = 0
        private const val DENY = 1
        private const val APPROVE = 2
        private const val REPLY = 3

        fun approvalTag(approvalId: String) = "approval:$approvalId"

        fun turnTag(sessionId: String) = "turn:$sessionId"

        fun errorTag(sessionId: String) = "error:$sessionId"

        // A generic approval shares its approval's tag, so APPROVAL_CLEARED removes it as well.
        private fun genericTag(notice: PushNotice.Generic): String =
            notice.approvalId?.let(::approvalTag)
                ?: if (notice.kind == PushKind.ERROR) errorTag(notice.sessionId) else turnTag(notice.sessionId)

        // A distinct request code per notification and action, so one PendingIntent never overwrites another's extras.
        private fun requestCode(
            tag: String,
            kind: Int,
        ) = tag.hashCode() * ACTION_KINDS + kind

        private const val ACTION_KINDS = 4
    }
}
