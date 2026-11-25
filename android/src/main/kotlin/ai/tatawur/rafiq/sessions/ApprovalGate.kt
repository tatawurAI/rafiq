package ai.tatawur.rafiq.sessions

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.client.ApprovalUnlock
import ai.tatawur.rafiq.client.Unlock
import ai.tatawur.rafiq.terminal.BiometricCheck
import ai.tatawur.rafiq.ui.theme.RafiqTheme
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * The optional biometric check before you answer an approval (guard 9, issue #14). With the setting on, an
 * answer needs the approval lock's Keystore key, which works for 30 seconds after a strong biometric check:
 * the one in the prompt here, or the one that unlocked your phone. There is no fallback to the screen lock PIN.
 *
 * @property lock true when the setting is on.
 * @property reset true while the notice that the lock turned itself off is up (issue #14).
 * @property dismissReset hides that notice.
 */
class ApprovalGate(
    val lock: Flow<Boolean>,
    val unlock: ApprovalUnlock,
    val reset: Flow<Boolean> = flowOf(false),
    val dismissReset: suspend () -> Unit = {},
)

/**
 * The notice that the approval lock turned itself off because the phone's biometrics changed. Someone who knows
 * your PIN can add a fingerprint, so it stays up until you dismiss it or change the lock, wherever it shows.
 */
@Composable
fun ApprovalLockResetNotice(
    gate: ApprovalGate?,
    modifier: Modifier = Modifier,
) {
    if (gate == null) return
    val reset by gate.reset.collectAsStateWithLifecycle(initialValue = false)
    if (!reset) return
    val scope = rememberCoroutineScope()
    ApprovalLockResetCard(onDismiss = { scope.launch { gate.dismissReset() } }, modifier = modifier)
}

/** The notice itself, for a screen that holds the reset state on its own, such as Settings. */
@Composable
fun ApprovalLockResetCard(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = RafiqTheme.spacing
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        color = RafiqTheme.colors.surface,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(start = spacing.smallMedium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            Text(
                stringResource(R.string.approval_lock_reset_short),
                modifier = Modifier.weight(1f).padding(vertical = spacing.small),
                style = MaterialTheme.typography.bodySmall,
                color = RafiqTheme.colors.needsYou,
            )
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = spacing.minTouchTarget)) {
                Text(stringResource(R.string.feedback_dismiss))
            }
        }
    }
}

@Preview(name = "Approval lock turned off", widthDp = 360)
@Composable
private fun ApprovalLockResetPreview() {
    RafiqTheme { ApprovalLockResetCard(onDismiss = {}) }
}

/**
 * [approve], behind [gate]'s check when the setting is on. With no gate, or the setting off, it answers at
 * once. A setting that cannot be read counts as on. When the key was destroyed by a biometric change, nothing
 * is sent and a message says the lock turned off.
 */
@Composable
fun rememberGatedApprove(
    gate: ApprovalGate?,
    approve: (approvalId: String, optionId: String) -> Unit,
): (approvalId: String, optionId: String) -> Unit {
    if (gate == null) return approve
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestApprove by rememberUpdatedState(approve)
    val title = stringResource(R.string.approval_unlock_title)
    val subtitle = stringResource(R.string.approval_unlock_subtitle)
    val cancel = stringResource(R.string.terminal_unlock_cancel)
    val reset = stringResource(R.string.approval_lock_reset)
    return remember(gate, context, scope, title, subtitle, cancel, reset) {
        { approvalId, optionId ->
            scope.launch {
                val lockOn = gate.lock.first()
                when (gate.unlock.check(lockOn) { BiometricCheck.await(context, title, subtitle, cancel) }) {
                    Unlock.ALLOWED -> latestApprove(approvalId, optionId)
                    Unlock.LOCK_TURNED_OFF -> Toast.makeText(context, reset, Toast.LENGTH_LONG).show()
                    Unlock.REFUSED -> Unit
                }
            }
        }
    }
}
