package ai.tatawur.rafiq.sessions

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.ui.theme.RafiqTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview

/**
 * The session list. With no server connected yet it shows only its empty state, and
 * "New session" stays disabled because there is nowhere to start one.
 */
@Composable
fun SessionsScreen(modifier: Modifier = Modifier) {
    val spacing = RafiqTheme.spacing
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(spacing.medium),
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        Text(
            text = stringResource(R.string.sessions_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(R.string.sessions_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = RafiqTheme.colors.textMuted,
        )
        Button(
            onClick = {},
            enabled = false,
            modifier = Modifier.heightIn(min = spacing.minTouchTarget),
        ) {
            Text(text = stringResource(R.string.sessions_new))
        }
    }
}

@Preview(name = "Sessions, empty", showSystemUi = true)
@Composable
private fun SessionsScreenEmptyPreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            SessionsScreen()
        }
    }
}
