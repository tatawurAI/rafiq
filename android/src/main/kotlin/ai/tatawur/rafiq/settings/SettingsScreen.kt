package ai.tatawur.rafiq.settings

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.terminal.BiometricCheck
import ai.tatawur.rafiq.ui.theme.RafiqTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/**
 * Settings, wired to [settings]. Changing the Terminal lock in either direction needs the same
 * biometric check it turns on, so someone holding your unlocked phone cannot switch it off. When
 * the phone has no strong biometric left, turning it off needs none: removing one already took
 * your screen lock.
 */
@Composable
fun SettingsRoute(
    settings: SecuritySettings,
    onBack: () -> Unit,
    onUnpair: () -> Unit = {},
) {
    val unpair = remember(onUnpair) { UnpairPrompt(onUnpair) }
    val askingUnpair by unpair.asking.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lockOn by settings.terminalLock.collectAsStateWithLifecycle(initialValue = false)
    val available = remember { BiometricCheck.available(context) }
    val title = stringResource(R.string.settings_confirm_title)
    val subtitle = stringResource(R.string.settings_terminal_lock)
    val cancel = stringResource(R.string.terminal_unlock_cancel)
    SettingsScreen(
        terminalLock = lockOn,
        biometricAvailable = available,
        onBack = onBack,
        onTerminalLockChange = { on ->
            if (!available && !on) {
                scope.launch { settings.setTerminalLock(false) }
            } else if (available) {
                BiometricCheck.ask(context, title, subtitle, cancel) { passed ->
                    if (passed) scope.launch { settings.setTerminalLock(on) }
                }
            }
        },
        onUnpair = unpair::ask,
    )
    if (askingUnpair) UnpairConfirmation(onConfirm = unpair::confirm, onDismiss = unpair::cancel)
}

/** The settings list: for now, the biometric lock on Terminal. */
@Composable
fun SettingsScreen(
    terminalLock: Boolean,
    biometricAvailable: Boolean,
    onBack: () -> Unit,
    onTerminalLockChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onUnpair: () -> Unit = {},
) {
    val spacing = RafiqTheme.spacing
    val colors = RafiqTheme.colors
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .consumeWindowInsets(WindowInsets.safeDrawing)
                .padding(horizontal = spacing.medium, vertical = spacing.small),
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_back),
                    contentDescription = stringResource(R.string.back),
                    modifier = Modifier.size(spacing.icon),
                    tint = colors.text,
                )
            }
            Text(
                stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleLarge,
                color = colors.text,
            )
        }
        TerminalLockRow(terminalLock, biometricAvailable, onTerminalLockChange)
        HorizontalDivider(color = colors.border, thickness = spacing.hairline)
        UnpairRow(onUnpair)
    }
}

/** Unpair this phone: what it does, and the button that asks to confirm it. */
@Composable
private fun UnpairRow(onUnpair: () -> Unit) {
    val spacing = RafiqTheme.spacing
    val colors = RafiqTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xSmall)) {
        Text(stringResource(R.string.settings_unpair), style = MaterialTheme.typography.bodyLarge, color = colors.text)
        Text(
            stringResource(R.string.settings_unpair_detail),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
        OutlinedButton(
            onClick = onUnpair,
            modifier = Modifier.heightIn(min = spacing.minTouchTarget),
            border = BorderStroke(spacing.hairline, colors.deletion),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.deletion),
        ) {
            Text(stringResource(R.string.settings_unpair_button))
        }
    }
}

/** Asks before unpairing, because the phone then needs a new pairing code. */
@Composable
private fun UnpairConfirmation(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_unpair_confirm_title)) },
        text = { Text(stringResource(R.string.settings_unpair_confirm_text)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.settings_unpair_button), color = RafiqTheme.colors.deletion)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.terminal_unlock_cancel)) } },
    )
}

/** The Terminal lock switch, with what it does or why it cannot be turned on. */
@Composable
private fun TerminalLockRow(
    terminalLock: Boolean,
    biometricAvailable: Boolean,
    onTerminalLockChange: (Boolean) -> Unit,
) {
    val spacing = RafiqTheme.spacing
    val colors = RafiqTheme.colors
    val canChange = biometricAvailable || terminalLock
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = spacing.minTouchTarget)
                .toggleable(
                    value = terminalLock,
                    enabled = canChange,
                    role = Role.Switch,
                    onValueChange = onTerminalLockChange,
                ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xSmall)) {
            Text(
                stringResource(R.string.settings_terminal_lock),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.text,
            )
            Text(
                stringResource(
                    if (biometricAvailable) {
                        R.string.settings_terminal_lock_detail
                    } else {
                        R.string.settings_terminal_lock_unavailable
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
        Switch(
            checked = terminalLock,
            onCheckedChange = null,
            enabled = canChange,
            colors =
                SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                ),
        )
    }
}

@Preview(name = "Unpair confirmation", widthDp = 360)
@Composable
private fun UnpairConfirmationPreview() {
    RafiqTheme { UnpairConfirmation(onConfirm = {}, onDismiss = {}) }
}

@Preview(name = "Settings", widthDp = 412, heightDp = 400)
@Composable
private fun SettingsPreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            SettingsScreen(terminalLock = true, biometricAvailable = true, onBack = {}, onTerminalLockChange = {})
        }
    }
}

@Preview(name = "Settings, no strong biometric", widthDp = 412, heightDp = 400)
@Composable
private fun SettingsUnavailablePreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            SettingsScreen(terminalLock = false, biometricAvailable = false, onBack = {}, onTerminalLockChange = {})
        }
    }
}
