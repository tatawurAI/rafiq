package ai.tatawur.rafiq.ui.navigation

import ai.tatawur.rafiq.client.ConnectionState
import android.net.Uri

/** The app's destinations. */
internal object Routes {
    const val PAIRING = "pairing"
    const val SESSIONS = "sessions"
    const val NEW_SESSION = "sessions/new"
    const val SETTINGS = "settings"
    const val SESSION_ID = "sessionId"
    const val SESSION = "session/{$SESSION_ID}"
    const val HOOKS = "session/{$SESSION_ID}/hooks"

    // Session ids are opaque and may hold any character, so they are encoded into the route.
    fun session(id: String) = "session/${Uri.encode(id)}"

    fun hooks(id: String) = "session/${Uri.encode(id)}/hooks"
}

/**
 * Where the app must go so the screen matches the pairing: to [Routes.PAIRING] when no bridge is
 * paired, and away from it to [Routes.SESSIONS] once one is. Null when [route] already fits.
 *
 * [ConnectionState.Unauthorized] counts as paired: the screens show it in their banner.
 */
internal fun pairingRedirect(
    connection: ConnectionState,
    route: String,
): String? {
    val paired = connection != ConnectionState.NotPaired
    return when {
        !paired && route != Routes.PAIRING -> Routes.PAIRING
        paired && route == Routes.PAIRING -> Routes.SESSIONS
        else -> null
    }
}
