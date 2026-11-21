package ai.tatawur.rafiq

import ai.tatawur.rafiq.data.RafiqApplication
import ai.tatawur.rafiq.pairing.PairingScreen
import ai.tatawur.rafiq.ui.navigation.RafiqNavHost
import ai.tatawur.rafiq.ui.theme.RafiqTheme
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier

/** The app's single activity. It hosts the Compose UI and draws behind the system bars. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is dark only, so the system bars always get light icons, whatever the system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // The screens see only the repository interfaces from :client.
        val container = (application as RafiqApplication).container
        setContent {
            RafiqTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    RafiqNavHost(
                        sessions = container.sessions,
                        pairing = { onPaired -> PairingScreen(container.pairing, onPaired) },
                    )
                }
            }
        }
    }
}
