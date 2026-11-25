package ai.tatawur.rafiq.ui.navigation

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.client.ConnectionState
import ai.tatawur.rafiq.client.SessionRepository
import ai.tatawur.rafiq.sessions.ApprovalGate
import ai.tatawur.rafiq.sessions.HookSetupRoute
import ai.tatawur.rafiq.sessions.HookSetupViewModel
import ai.tatawur.rafiq.sessions.NewSessionRoute
import ai.tatawur.rafiq.sessions.NewSessionViewModel
import ai.tatawur.rafiq.sessions.SessionListRoute
import ai.tatawur.rafiq.sessions.SessionListViewModel
import ai.tatawur.rafiq.sessions.SessionRoute
import ai.tatawur.rafiq.sessions.SessionViewModel
import ai.tatawur.rafiq.terminal.TerminalAccess
import ai.tatawur.rafiq.ui.theme.RafiqTheme
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The app's screens and how you move between them. Screens get [sessions] through their view
 * models and never build a repository themselves.
 *
 * While no bridge is paired the app shows [pairing], and it returns there whenever the pairing is
 * lost. It also leaves [pairing] on its own once a bridge is paired, so a start screen picked from
 * an early connection value never strands a paired user there. Pairing is its own screen, so it
 * comes in as a slot.
 *
 * @param pairing the pairing screen. It calls `onPaired` once a bridge is paired.
 * @param onPairAgain forgets the refused pairing when you tap "Pair again". The connection then
 *   reads as not paired and the app moves to [pairing].
 * @param terminal offers Terminal on sessions your server serves as a terminal, or null for none.
 * @param settings the settings screen. It calls `onBack` to leave.
 * @param approvals the optional biometric check before answering an approval, or null for none.
 * @param openRequest a session a notification asked to open, or null. [onOpened] says it was opened.
 * @param onSessionShown the session now on screen, or null when none is, so push does not notify twice.
 */
@Suppress("LongMethod") // One destination per screen; splitting them would hide the graph.
@Composable
fun RafiqNavHost(
    sessions: SessionRepository,
    pairing: @Composable (onPaired: () -> Unit) -> Unit,
    onPairAgain: () -> Unit,
    modifier: Modifier = Modifier,
    terminal: TerminalAccess? = null,
    settings: @Composable (onBack: () -> Unit) -> Unit = {},
    navController: NavHostController = rememberNavController(),
    approvals: ApprovalGate? = null,
    openRequest: StateFlow<String?> = MutableStateFlow(null),
    onOpened: (String) -> Unit = {},
    onSessionShown: (String?) -> Unit = {},
) {
    val connection by sessions.connection.collectAsStateWithLifecycle()
    val start = remember { if (connection == ConnectionState.NotPaired) Routes.PAIRING else Routes.SESSIONS }
    val current by navController.currentBackStackEntryAsState()
    LaunchedEffect(connection, current) {
        val route = current?.destination?.route ?: return@LaunchedEffect
        val target = pairingRedirect(connection, route) ?: return@LaunchedEffect
        navController.navigate(target) {
            popUpTo(navController.graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }

    // Push: open the session a notification names, once paired, and report the session on screen.
    val pendingOpen by openRequest.collectAsStateWithLifecycle()
    LaunchedEffect(pendingOpen, connection) {
        val id = pendingOpen ?: return@LaunchedEffect
        if (connection == ConnectionState.NotPaired) return@LaunchedEffect
        navController.navigate(Routes.session(id)) { launchSingleTop = true }
        onOpened(id)
    }
    LaunchedEffect(current) {
        val entry = current
        onSessionShown(
            entry?.arguments?.getString(Routes.SESSION_ID)?.takeIf {
                entry.destination.route ==
                    Routes.SESSION
            },
        )
    }

    NavHost(navController = navController, startDestination = start, modifier = modifier) {
        composable(Routes.PAIRING) {
            pairing {
                navController.navigate(Routes.SESSIONS) {
                    popUpTo(Routes.PAIRING) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
        composable(Routes.SESSIONS) {
            val viewModel = viewModel { SessionListViewModel(sessions) }
            SessionListRoute(
                viewModel = viewModel,
                onOpen = { id -> navController.navigate(Routes.session(id)) },
                onNewSession = { navController.navigate(Routes.NEW_SESSION) },
                onPairAgain = onPairAgain,
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                approvals = approvals,
            )
        }
        composable(Routes.SETTINGS) {
            settings { navController.popBackStack() }
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
            SessionRoute(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onPairAgain = onPairAgain,
                terminal = terminal,
                approvals = approvals,
                onOpenHooks = { navController.navigate(Routes.hooks(sessionId)) },
            )
        }
        composable(
            route = Routes.HOOKS,
            arguments = listOf(navArgument(Routes.SESSION_ID) { type = NavType.StringType }),
        ) { entry ->
            val sessionId = entry.arguments?.getString(Routes.SESSION_ID) ?: return@composable
            val viewModel = viewModel(key = "hooks:$sessionId") { HookSetupViewModel(sessions, sessionId) }
            HookSetupRoute(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
    }
}
