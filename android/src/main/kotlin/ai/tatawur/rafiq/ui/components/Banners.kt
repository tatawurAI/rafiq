package ai.tatawur.rafiq.ui.components

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.client.ConnectionState
import ai.tatawur.rafiq.sessions.Feedback
import ai.tatawur.rafiq.ui.theme.RafiqTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview

/**
 * One line about the link to your server. It shows nothing while the link is live. Offline is not
 * an error: the screen below it keeps showing cached state.
 *
 * When the server refuses the token it offers "Pair again", which calls [onPairAgain]. The app
 * never unpairs on its own: a token rotated on the server should not wipe the cache unless you ask.
 */
@Composable
fun ConnectionBanner(
    connection: ConnectionState,
    modifier: Modifier = Modifier,
    onPairAgain: () -> Unit = {},
) {
    val (textRes, color) =
        when (connection) {
            ConnectionState.Live, ConnectionState.NotPaired -> return
            ConnectionState.Connecting -> R.string.connection_connecting to RafiqTheme.colors.textMuted
            is ConnectionState.Offline -> R.string.connection_offline to RafiqTheme.colors.textSecondary
            ConnectionState.Unauthorized -> R.string.connection_unauthorized to RafiqTheme.colors.needsYou
            ConnectionState.IdentityRefused -> R.string.connection_identity_refused to RafiqTheme.colors.needsYou
        }
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        color = RafiqTheme.colors.surface,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(textRes),
                modifier =
                    Modifier.weight(1f).padding(
                        horizontal = RafiqTheme.spacing.medium,
                        vertical = RafiqTheme.spacing.small,
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = color,
            )
            if (offersPairAgain(connection)) {
                TextButton(
                    onClick = onPairAgain,
                    modifier = Modifier.heightIn(min = RafiqTheme.spacing.minTouchTarget),
                ) {
                    Text(stringResource(R.string.connection_pair_again))
                }
            }
        }
    }
}

/** Only a refused token is fixed by pairing again. Offline and connecting fix themselves. */
internal fun offersPairAgain(connection: ConnectionState): Boolean = connection == ConnectionState.Unauthorized

/** The line a screen shows after a command did not go through, with a way to dismiss it. */
@Composable
fun FeedbackBar(
    feedback: Feedback?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (feedback == null) return
    val text =
        when (feedback) {
            is Feedback.Failed -> stringResource(feedback.textRes, feedback.message)
            else -> stringResource(feedback.textRes)
        }
    // Busy is ordinary news while an agent runs, so it gets the muted colour, not the warning one.
    val color =
        when (feedback) {
            Feedback.StillWorking, Feedback.AlreadyAnswered -> RafiqTheme.colors.textSecondary
            else -> RafiqTheme.colors.needsYou
        }
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        color = RafiqTheme.colors.surface,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(start = RafiqTheme.spacing.smallMedium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RafiqTheme.spacing.small),
        ) {
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = color,
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = RafiqTheme.spacing.minTouchTarget),
            ) {
                Text(stringResource(R.string.feedback_dismiss))
            }
        }
    }
}

@Preview(name = "Banners", widthDp = 360)
@Composable
private fun BannersPreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(verticalArrangement = Arrangement.spacedBy(RafiqTheme.spacing.small)) {
                ConnectionBanner(ConnectionState.Connecting)
                ConnectionBanner(ConnectionState.Offline(retryAtMs = null))
                ConnectionBanner(ConnectionState.Unauthorized)
                ConnectionBanner(ConnectionState.IdentityRefused)
                FeedbackBar(Feedback.StillWorking, onDismiss = {})
                FeedbackBar(Feedback.Failed("session_closed"), onDismiss = {})
            }
        }
    }
}
