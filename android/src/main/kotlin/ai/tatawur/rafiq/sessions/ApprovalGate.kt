package ai.tatawur.rafiq.sessions

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.client.UnlockWindow
import ai.tatawur.rafiq.terminal.BiometricCheck
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow

/**
 * The optional biometric check before you answer an approval (guard 9). It sits at the door: one strong
 * biometric check, with no fallback to the screen lock PIN, then a short [UnlockWindow] in which further
 * answers need none. The app going to the background closes the window.
 *
 * @property lock true when the setting is on.
 */
class ApprovalGate(
    val lock: Flow<Boolean>,
    val window: UnlockWindow,
)

/**
 * [approve], behind [gate]'s biometric check when the setting is on and the window is closed. With no
 * gate, or the setting off, it answers at once. Until the setting has loaded it counts as on.
 */
@Composable
fun rememberGatedApprove(
    gate: ApprovalGate?,
    approve: (approvalId: String, optionId: String) -> Unit,
): (approvalId: String, optionId: String) -> Unit {
    if (gate == null) return approve
    val context = LocalContext.current
    val lockOn by gate.lock.collectAsStateWithLifecycle(initialValue = true)
    val latestApprove by rememberUpdatedState(approve)
    val title = stringResource(R.string.approval_unlock_title)
    val subtitle = stringResource(R.string.approval_unlock_subtitle)
    val cancel = stringResource(R.string.terminal_unlock_cancel)
    return remember(gate, lockOn, context, title, subtitle, cancel) {
        { approvalId, optionId ->
            if (!lockOn || gate.window.isOpen) {
                latestApprove(approvalId, optionId)
            } else {
                BiometricCheck.ask(context, title, subtitle, cancel) { passed ->
                    if (passed) {
                        gate.window.passed()
                        latestApprove(approvalId, optionId)
                    }
                }
            }
        }
    }
}
