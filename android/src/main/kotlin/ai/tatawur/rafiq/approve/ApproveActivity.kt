package ai.tatawur.rafiq.approve

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.client.push.ApproveModel
import ai.tatawur.rafiq.client.push.ApproveState
import ai.tatawur.rafiq.data.RafiqApplication
import ai.tatawur.rafiq.push.PushNotifier
import ai.tatawur.rafiq.terminal.BiometricCheck
import ai.tatawur.rafiq.ui.theme.RafiqTheme
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * Approve, opened from a notification's Approve action after you unlocked the phone. It shows the whole
 * request as your server has it, never shortened to its first line, and answers only when you tap an option.
 * It is not exported and never shows over the lock screen.
 */
class ApproveActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val approvalId = intent.getStringExtra(EXTRA_APPROVAL)
        val sessionId = intent.getStringExtra(EXTRA_SESSION)
        if (approvalId == null || sessionId == null) {
            finish()
            return
        }
        val container = (application as RafiqApplication).container
        val title = getString(R.string.approve_unlock_title)
        val subtitle = getString(R.string.approve_unlock_subtitle)
        val cancel = getString(R.string.terminal_unlock_cancel)
        setContent {
            RafiqTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val viewModel =
                        viewModel {
                            ApproveViewModel { scope ->
                                ApproveModel(
                                    api = container.push.api,
                                    unlock = container.approvals.unlock,
                                    lockOn = { container.approvals.lock.first() },
                                    approvalId = approvalId,
                                    sessionId = sessionId,
                                    scope = scope,
                                )
                            }
                        }
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    LaunchedEffect(state) {
                        val answered = state == ApproveState.Done || state == ApproveState.AlreadyAnswered
                        if (answered) container.push.notifier.cancel(PushNotifier.approvalTag(approvalId))
                        if (state == ApproveState.Done) finish()
                    }
                    ApproveScreen(
                        state = state,
                        onAnswer = { optionId ->
                            viewModel.model.answer(optionId) {
                                BiometricCheck.await(this@ApproveActivity, title, subtitle, cancel)
                            }
                        },
                        onRetry = viewModel.model::load,
                        onClose = ::finish,
                    )
                }
            }
        }
    }

    companion object {
        private const val EXTRA_APPROVAL = "approval_id"
        private const val EXTRA_SESSION = "session_id"

        /** Opens the Approve screen for [approvalId] in [sessionId]. */
        fun intent(
            context: Context,
            approvalId: String,
            sessionId: String,
        ): Intent =
            Intent(context, ApproveActivity::class.java)
                .putExtra(EXTRA_APPROVAL, approvalId)
                .putExtra(EXTRA_SESSION, sessionId)
                // One Approve screen per approval: a second tap brings the same one back.
                .setIdentifier(approvalId)
    }
}

/** [ApproveModel] in the screen's scope, so a rotation or fold keeps the answer in flight. */
internal class ApproveViewModel(
    create: (CoroutineScope) -> ApproveModel,
) : ViewModel() {
    val model = create(viewModelScope)

    val state: StateFlow<ApproveState> = model.state

    init {
        model.load()
    }
}
