package ai.tatawur.rafiq

import ai.tatawur.rafiq.data.RafiqApplication
import ai.tatawur.rafiq.pairing.PairingScreen
import ai.tatawur.rafiq.push.OpenSessionIntent
import ai.tatawur.rafiq.push.PushSettingsRoute
import ai.tatawur.rafiq.settings.SettingsRoute
import ai.tatawur.rafiq.terminal.MultiTouchGate
import ai.tatawur.rafiq.ui.fold.LocalHinge
import ai.tatawur.rafiq.ui.fold.rememberHinge
import ai.tatawur.rafiq.ui.navigation.RafiqNavHost
import ai.tatawur.rafiq.ui.theme.RafiqTheme
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** The app's single activity. It hosts the Compose UI and draws behind the system bars. */
class MainActivity : ComponentActivity() {
    // Terminal takes one finger at a time, so the emulator's pinch zoom never starts.
    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
        MultiTouchGate.blocks(event) || super.dispatchTouchEvent(event)

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is dark only, so the system bars always get light icons, whatever the system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // The screens see only the repository interfaces from :client.
        val container = (application as RafiqApplication).container
        val sessions = sessionsForLaunch(intent, container.sessions)
        // Push: a notification tap that started the app opens its session.
        OpenSessionIntent.sessionOf(intent)?.let(container.push::open)
        setContent {
            // Folding is handled as a configuration change (see the manifest), so the hinge updates
            // in place and the screens keep their scroll position.
            CompositionLocalProvider(LocalHinge provides rememberHinge(this)) {
                RafiqTheme {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        RafiqNavHost(
                            sessions = sessions,
                            pairing = { onPaired -> PairingScreen(container.pairing, onPaired) },
                            onPairAgain = { lifecycleScope.launch { container.pairing.unpair() } },
                            terminal = container.terminal,
                            approvals = container.approvals,
                            settings = { onBack ->
                                // The activity's scope: Settings closes as soon as the phone is unpaired.
                                SettingsRoute(
                                    container.security,
                                    onBack,
                                    onUnpair = { lifecycleScope.launch { container.pairing.unpair() } },
                                    approvalUnlock = container.approvals.unlock,
                                    notifications = { PushSettingsRoute(container.push) },
                                )
                            },
                            // Push: open the session a notification names; tell push which session is on screen.
                            openRequest = container.push.openRequest,
                            onOpened = container.push::opened,
                            onSessionShown = container.push::sessionShown,
                        )
                    }
                }
            }
        }
    }

    // Push: a notification tap while the app is open (singleTop) opens its session.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        OpenSessionIntent.sessionOf(intent)?.let((application as RafiqApplication).container.push::open)
    }
}
