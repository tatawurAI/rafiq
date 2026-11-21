package ai.tatawur.rafiq.ui.navigation

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.client.ConnectionState
import ai.tatawur.rafiq.client.SessionRepository
import ai.tatawur.rafiq.sessions.NewSessionRoute
import ai.tatawur.rafiq.sessions.NewSessionViewModel
import ai.tatawur.rafiq.sessions.SessionListRoute
import ai.tatawur.rafiq.sessions.SessionListViewModel
import ai.tatawur.rafiq.sessions.SessionRoute
import ai.tatawur.rafiq.sessions.SessionViewModel
import ai.tatawur.rafiq.ui.theme.RafiqTheme
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

/** The app's destinations. */
private object Routes {
    const val PAIRING = "pairing"
    const val SESSIONS = "sessions"
    const val NEW_SESSION = "sessions/new"
    const val SESSION_ID = "sessionId"
    const val SESSION = "session/{$SESSION_ID}"

    // Session ids are opaque and may hold any character, so they are encoded into the route.
    fun session(id: String) = "session/${Uri.encode(id)}"
}

/**
 * The app's screens and how you move between them. Screens get [sessions] through their view
 * models and never build a repository themselves.
 *
 * While no bridge is paired the app shows [pairing], and it returns there whenever the pairing is
 * lost. Pairing is its own screen, so it comes in as a slot.
 *
 * @param pairing the pairing screen. It calls `onPaired` once a bridge is paired.
 */
@Composable
fun RafiqNavHost(
    sessions: SessionRepository,
    pairing: @Composable (onPaired: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val connection by sessions.connection.collectAsStateWithLifecycle()
    val start = remember { if (connection == ConnectionState.NotPaired) Routes.PAIRING else Routes.SESSIONS }
    val current by navController.currentBackStackEntryAsState()
    LaunchedEffect(connection, current) {
        val route = current?.destination?.route ?: return@LaunchedEffect
        if (connection == ConnectionState.NotPaired && route != Routes.PAIRING) {
            navController.navigate(Routes.PAIRING) { popUpTo(0) }
        }
    }

    NavHost(navController = navController, startDestination = start, modifier = modifier) {
        composable(Routes.PAIRING) {
            pairing {
                navController.navigate(Routes.SESSIONS) { popUpTo(Routes.PAIRING) { inclusive = true } }
            }
        }
        composable(Routes.SESSIONS) {
            val viewModel = viewModel { SessionListViewModel(sessions) }
            SessionListRoute(
                viewModel = viewModel,
                onOpen = { id -> navController.navigate(Routes.session(id)) },
                onNewSession = { navController.navigate(Routes.NEW_SESSION) },
            )
        }
        composable(Routes.NEW_SESSION) {
            val viewModel = viewModel { NewSessionViewModel(sessions) }
            NewSessionRoute(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onCreated = { id ->
                    navController.navigate(Routes.session(id)) { popUpTo(Routes.NEW_SESSION) { inclusive = true } }
                },
            )
        }
        composable(
            route = Routes.SESSION,
            arguments = listOf(navArgument(Routes.SESSION_ID) { type = NavType.StringType }),
        ) { entry ->
            val sessionId = entry.arguments?.getString(Routes.SESSION_ID) ?: return@composable
            val viewModel = viewModel(key = sessionId) { SessionViewModel(sessions, sessionId) }
            SessionRoute(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
    }
}
