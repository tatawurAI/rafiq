package ai.tatawur.rafiq.push

import ai.tatawur.rafiq.MainActivity
import android.content.Context
import android.content.Intent

/**
 * The intent a notification tap sends to [MainActivity] to open one session. Any app can start the launcher
 * activity with this, and all it can do is open a session screen the app would show anyway.
 */
internal object OpenSessionIntent {
    private const val ACTION = "ai.tatawur.rafiq.OPEN_SESSION"
    private const val EXTRA_SESSION = "session_id"

    fun create(
        context: Context,
        sessionId: String,
    ): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(ACTION)
            .putExtra(EXTRA_SESSION, sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** The session [intent] asks to open, or null when it is not one of these. */
    fun sessionOf(intent: Intent?): String? = intent?.takeIf { it.action == ACTION }?.getStringExtra(EXTRA_SESSION)
}
