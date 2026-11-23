package ai.tatawur.rafiq.sessions

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.client.ApprovalUnlock
import ai.tatawur.rafiq.client.Unlock
import ai.tatawur.rafiq.terminal.BiometricCheck
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The optional biometric check before you answer an approval (guard 9, issue #14). With the setting on, an
 * answer needs the approval lock's Keystore key, which works for 30 seconds after a strong biometric check:
 * the one in the prompt here, or the one that unlocked your phone. There is no fallback to the screen lock PIN.
 *
 * @property lock true when the setting is on.
 */
class ApprovalGate(
    val lock: Flow<Boolean>,
    val unlock: ApprovalUnlock,
)

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
