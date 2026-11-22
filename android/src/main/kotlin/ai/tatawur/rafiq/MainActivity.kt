package ai.tatawur.rafiq

import ai.tatawur.rafiq.data.RafiqApplication
import ai.tatawur.rafiq.pairing.PairingScreen
import ai.tatawur.rafiq.settings.SettingsRoute
import ai.tatawur.rafiq.terminal.MultiTouchGate
import ai.tatawur.rafiq.ui.fold.LocalHinge
import ai.tatawur.rafiq.ui.fold.rememberHinge
import ai.tatawur.rafiq.ui.navigation.RafiqNavHost
import ai.tatawur.rafiq.ui.theme.RafiqTheme
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
                            settings = { onBack -> SettingsRoute(container.security, onBack) },
                        )
                    }
                }
            }
        }
    }
}
