package ai.tatawur.rafiq.push

import ai.tatawur.rafiq.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * The app's notification channels, declared once at start-up so Android's settings list them before the first
 * push. Approvals are high importance, because an agent is stopped until you answer. Each channel hides its
 * content on the lock screen; the notifications carry a generic public version.
 */
internal object PushChannels {
    const val APPROVALS = "approvals"
    const val TURNS = "turns"
    const val ERRORS = "errors"
    const val STATUS = "answer_status"

    fun declare(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(
            listOf(
                channel(
                    context,
                    APPROVALS,
                    R.string.push_channel_approvals,
                    R.string.push_channel_approvals_detail,
                    NotificationManager.IMPORTANCE_HIGH,
                ),
                channel(
                    context,
                    TURNS,
                    R.string.push_channel_turns,
                    R.string.push_channel_turns_detail,
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
                channel(
                    context,
                    ERRORS,
                    R.string.push_channel_errors,
                    R.string.push_channel_errors_detail,
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
                channel(
                    context,
                    STATUS,
                    R.string.push_channel_status,
                    R.string.push_channel_status_detail,
                    NotificationManager.IMPORTANCE_LOW,
                ),
            ),
        )
    }

    private fun channel(
        context: Context,
        id: String,
        name: Int,
        detail: Int,
        importance: Int,
    ) = NotificationChannel(id, context.getString(name), importance).apply {
        description = context.getString(detail)
        lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
    }
}
