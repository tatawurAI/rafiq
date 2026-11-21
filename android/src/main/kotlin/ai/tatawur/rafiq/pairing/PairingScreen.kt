package ai.tatawur.rafiq.pairing

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.client.PairingRepository
import ai.tatawur.rafiq.client.PairingResult
import ai.tatawur.rafiq.ui.theme.RafiqTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Pairs the app with your server: its `tailscale serve` address and the device token. On success it
 * calls [onPaired]. On failure it shows the hint for that kind of failure and keeps what you typed.
 *
 * The token is held only in memory while you type, never in saved instance state.
 */
@Composable
fun PairingScreen(
    pairing: PairingRepository,
    onPaired: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var url by rememberSaveable { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<PairingResult?>(null) }
    var notSaved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    PairingForm(
        url = url,
        token = token,
        busy = busy,
        failure = failure,
        notSaved = notSaved,
        onUrlChange = {
            url = it
            failure = null
            notSaved = false
        },
        onTokenChange = {
            token = it
            failure = null
            notSaved = false
        },
        onConnect = {
            busy = true
            scope.launch {
                val result = pairOrNull(pairing, url, token)
                busy = false
                when {
                    result is PairingResult.Paired -> onPaired()
                    result == null -> notSaved = true
                    else -> failure = result
                }
            }
        },
        modifier = modifier,
    )
}

/**
 * Pairs, or returns null when pairing failed on the phone itself, for example when the Keystore cannot
 * store the token. Either way the app keeps running and the form stays filled in.
 */
@Suppress("TooGenericExceptionCaught") // DataStore and vendor Keystores throw more than they document.
private suspend fun pairOrNull(
    pairing: PairingRepository,
    url: String,
    token: String,
): PairingResult? =
    try {
        pairing.pair(url, token)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

/**
 * The pairing form itself, stateless so every failure hint can be previewed.
 *
 * @param notSaved pairing failed on the phone itself, not on your server.
 */
@Composable
private fun PairingForm(
    url: String,
    token: String,
    busy: Boolean,
    failure: PairingResult?,
    notSaved: Boolean,
    onUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = RafiqTheme.spacing
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(spacing.medium),
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        Text(
            text = stringResource(R.string.pairing_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(R.string.pairing_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = RafiqTheme.colors.textMuted,
        )
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            label = { Text(stringResource(R.string.pairing_url_label)) },
            placeholder = { Text(stringResource(R.string.pairing_url_placeholder)) },
            singleLine = true,
            enabled = !busy,
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
            modifier = Modifier.fillMaxWidth(),
        )
        TokenField(token = token, enabled = !busy, onTokenChange = onTokenChange)
        (if (notSaved) stringResource(R.string.pairing_hint_not_saved) else failure?.let { hintFor(it) })?.let { hint ->
            Text(text = hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = onConnect,
            enabled = !busy && url.isNotBlank() && token.isNotBlank(),
            modifier = Modifier.fillMaxWidth().heightIn(min = spacing.minTouchTarget),
        ) {
            Text(stringResource(if (busy) R.string.pairing_connecting else R.string.pairing_connect))
        }
    }
}

/** The device token, masked unless you choose to show it. Pasting works as in any text field. */
@Composable
private fun TokenField(
    token: String,
    enabled: Boolean,
    onTokenChange: (String) -> Unit,
) {
    val spacing = RafiqTheme.spacing
    var tokenVisible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = token,
        // A pasted token often carries a trailing newline or spaces.
        onValueChange = { onTokenChange(it.trim()) },
        label = { Text(stringResource(R.string.pairing_token_label)) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions =
            KeyboardOptions(
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
        trailingIcon = {
            TextButton(
                onClick = { tokenVisible = !tokenVisible },
                modifier = Modifier.heightIn(min = spacing.minTouchTarget),
            ) {
                Text(stringResource(if (tokenVisible) R.string.pairing_token_hide else R.string.pairing_token_show))
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun hintFor(result: PairingResult): String? =
    when (result) {
        is PairingResult.Paired -> null
        PairingResult.InvalidUrl -> stringResource(R.string.pairing_hint_invalid_url)
        is PairingResult.Unreachable -> stringResource(R.string.pairing_hint_unreachable, result.detail)
        PairingResult.NotABridge -> stringResource(R.string.pairing_hint_not_a_bridge)
        PairingResult.HostRejected -> stringResource(R.string.pairing_hint_host_rejected)
        PairingResult.TokenRejected -> stringResource(R.string.pairing_hint_token_rejected)
    }

@Preview(name = "Pairing, empty", showSystemUi = true)
@Composable
private fun PairingEmptyPreview() {
    PairingPreview(url = "", token = "", failure = null)
}

@Preview(name = "Pairing, host refused", showSystemUi = true)
@Composable
private fun PairingHostRejectedPreview() {
    PairingPreview(
        url = "https://server.example.ts.net",
        token = "replace-with-a-long-random-value",
        failure = PairingResult.HostRejected,
    )
}

@Preview(name = "Pairing, unreachable, long detail", showSystemUi = true)
@Composable
private fun PairingUnreachablePreview() {
    PairingPreview(
        url = "https://server.example.ts.net",
        token = "replace-with-a-long-random-value",
        failure =
            PairingResult.Unreachable(
                "Unable to resolve host server.example.ts.net: No address associated with hostname",
            ),
    )
}

@Composable
private fun PairingPreview(
    url: String,
    token: String,
    failure: PairingResult?,
) {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            PairingForm(
                url,
                token,
                busy = false,
                failure = failure,
                notSaved = false,
                onUrlChange = {},
                onTokenChange = {},
                onConnect = {},
            )
        }
    }
}
