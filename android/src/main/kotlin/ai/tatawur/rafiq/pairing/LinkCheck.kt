package ai.tatawur.rafiq.pairing

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.ui.theme.RafiqTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * A server address that came from a QR code or a link rather than from you.
 *
 * @property tailscaleName false when the host is not a `*.ts.net` name, which earns a warning.
 */
internal data class LinkCheck(
    val host: String,
    val tailscaleName: Boolean,
)

/** The host a scan or a link filled in, large enough to check, and a warning when it is not a Tailscale name. */
@Composable
internal fun LinkCheckLines(check: LinkCheck) {
    Text(
        text = stringResource(R.string.setup_link_check),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Text(text = check.host, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
    if (!check.tailscaleName) {
        Text(
            text = stringResource(R.string.setup_link_not_tailscale),
            style = MaterialTheme.typography.bodyMedium,
            color = RafiqTheme.colors.needsYou,
        )
    }
}
