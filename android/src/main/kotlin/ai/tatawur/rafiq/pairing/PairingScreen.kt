package ai.tatawur.rafiq.pairing

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.client.pairing.CodePairResult
import ai.tatawur.rafiq.ui.theme.RafiqTheme
import android.view.View
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDataType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Pairs the app with your server: its `tailscale serve` address and the code `rafiq-bridge pair` printed.
 * On success it calls [onPaired]. On failure it shows the hint for that kind of failure and keeps what you
 * typed. After the bridge revoked this phone, or an unpair the bridge never heard about, it also says so.
 *
 * The code is held only in memory while you type, never in saved instance state.
 */
@Composable
fun PairingScreen(
    pairing: DevicePairing,
    onPaired: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var url by rememberSaveable { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<CodePairResult?>(null) }
    var notSaved by remember { mutableStateOf(false) }
    val revoked by pairing.revokedNotice.collectAsState()
    val notice = noticeText(pairing)
    val scope = rememberCoroutineScope()
    KeepOutOfAutofill()
    PairingForm(
        state =
            PairingFormState(
                url = url,
                code = code,
                busy = busy,
                notice = notice,
                hint = if (notSaved) stringResource(R.string.pairing_hint_not_saved) else failure?.let { hintFor(it) },
            ),
        onUrlChange = {
            url = it
            failure = null
            notSaved = false
        },
        onCodeChange = {
            code = it
            failure = null
            notSaved = false
        },
        onDismissNotice = {
            pairing.dismissRevokedNotice()
            pairing.dismissUnpairNotice()
        },
        onConnect = {
            busy = true
            scope.launch {
                val result = pairOrNull(pairing, url, code)
                busy = false
                when {
                    result is CodePairResult.Paired -> onPaired()
                    result == null -> notSaved = true
                    else -> failure = result
                }
            }
        },
        modifier = modifier,
    )
}

/** Why you are on this screen when it is not a first pairing, or null. */
@Composable
private fun noticeText(pairing: DevicePairing): String? {
    val revoked by pairing.revokedNotice.collectAsState()
    val unpaired by pairing.unpairNotice.collectAsState()
    val deviceId = unpaired?.deviceId
    return when {
        deviceId != null -> stringResource(R.string.pair_unpaired_unconfirmed, deviceId)
        unpaired != null -> stringResource(R.string.pair_unpaired_unconfirmed_unknown_id)
        revoked -> stringResource(R.string.pair_revoked_notice)
        else -> null
    }
}

/**
 * Keeps autofill services away from this screen while it is shown, so none of them offers to save the
 * device token as a password. Typing and pasting are not affected. The rest of the app is left as it was.
 */
@Composable
private fun KeepOutOfAutofill() {
    val view = LocalView.current
    DisposableEffect(view) {
        val before = view.importantForAutofill
        view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        onDispose { view.importantForAutofill = before }
    }
}

/**
 * Pairs, or returns null when pairing failed on the phone itself, for example when the Keystore cannot
 * store the token. Either way the app keeps running and the form stays filled in.
 */
@Suppress("TooGenericExceptionCaught") // DataStore and vendor Keystores throw more than they document.
private suspend fun pairOrNull(
    pairing: DevicePairing,
    url: String,
    code: String,
): CodePairResult? =
    try {
        pairing.pairWithCode(url, code)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

/**
 * What the pairing form shows.
 *
 * @property hint why the last attempt failed, or null to show none.
 * @property notice why you are on this screen when it is not a first pairing, or null to show none.
 */
internal data class PairingFormState(
    val url: String,
    val code: String,
    val busy: Boolean = false,
    val notice: String? = null,
    val hint: String? = null,
)

/** The pairing form itself, stateless so every failure hint can be previewed. */
@Composable
internal fun PairingForm(
    state: PairingFormState,
    onUrlChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onDismissNotice: () -> Unit,
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
        state.notice?.let { Notice(text = it, onDismiss = onDismissNotice) }
        Text(
            text = stringResource(R.string.pair_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = RafiqTheme.colors.textMuted,
        )
        OutlinedTextField(
            value = state.url,
            onValueChange = onUrlChange,
            label = { Text(stringResource(R.string.pairing_url_label)) },
            placeholder = { Text(stringResource(R.string.pairing_url_placeholder)) },
            singleLine = true,
            enabled = !state.busy,
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
            modifier = Modifier.fillMaxWidth(),
        )
        CodeField(code = state.code, enabled = !state.busy, onCodeChange = onCodeChange)
        state.hint?.let {
            Text(text = it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = onConnect,
            enabled = !state.busy && state.url.isNotBlank() && state.code.isNotBlank(),
            modifier = Modifier.fillMaxWidth().heightIn(min = spacing.minTouchTarget),
        ) {
            Text(stringResource(if (state.busy) R.string.pairing_connecting else R.string.pairing_connect))
        }
    }
}

/** The pairing code. Upper case, and a typed or pasted hyphen or space is fine. */
@Composable
private fun CodeField(
    code: String,
    enabled: Boolean,
    onCodeChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = code,
        onValueChange = {
            onCodeChange(
                it
                    .uppercase()
                    .filter { c ->
                        c.isLetterOrDigit() || c == '-'
                    }.take(MAX_CODE_CHARS),
            )
        },
        label = { Text(stringResource(R.string.pair_code_label)) },
        placeholder = { Text(stringResource(R.string.pair_code_placeholder)) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions =
            KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
        // Not a value for autofill to fill or save, even if the screen's exclusion is lost.
        modifier = Modifier.fillMaxWidth().semantics { contentDataType = ContentDataType.None },
    )
}

/** Says why you are on this screen, with a way to dismiss it. */
@Composable
private fun Notice(
    text: String,
    onDismiss: () -> Unit,
) {
    val spacing = RafiqTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = spacing.minTouchTarget)) {
            Text(stringResource(R.string.pair_revoked_dismiss))
        }
    }
}

private const val MAX_CODE_CHARS = 9

@Composable
internal fun hintFor(result: CodePairResult): String? =
    when (result) {
        is CodePairResult.Paired -> null
        CodePairResult.InvalidUrl -> stringResource(R.string.pairing_hint_invalid_url)
        is CodePairResult.Unreachable -> stringResource(R.string.pairing_hint_unreachable, result.detail)
        CodePairResult.NotABridge -> stringResource(R.string.pairing_hint_not_a_bridge)
        CodePairResult.HostRejected -> stringResource(R.string.pairing_hint_host_rejected)
        CodePairResult.CodeRejected -> stringResource(R.string.pair_hint_code_rejected)
        CodePairResult.TooManyAttempts -> stringResource(R.string.pair_hint_too_many)
    }
