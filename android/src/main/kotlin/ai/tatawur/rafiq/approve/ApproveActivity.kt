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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
    // The approval on screen. A tap on another approval's Approve while this screen is open arrives through
    // onNewIntent (singleTop) and replaces it, so the screen never answers a different approval than it shows.
    private var shown by mutableStateOf<Shown?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        shown = Shown.of(intent)
        if (shown == null) {
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
                    val (approvalId, sessionId) = shown ?: return@Surface
                    // Keyed by approval, so another approval gets its own model and loads afresh.
                    val viewModel =
                        viewModel(key = approvalId) {
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Shown.of(intent)?.let {
            setIntent(intent)
            shown = it
        }
    }

    private data class Shown(
        val approvalId: String,
        val sessionId: String,
    ) {
        companion object {
            fun of(intent: Intent): Shown? {
                val approvalId = intent.getStringExtra(EXTRA_APPROVAL)
                val sessionId = intent.getStringExtra(EXTRA_SESSION)
                return if (approvalId != null && sessionId != null) Shown(approvalId, sessionId) else null
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
