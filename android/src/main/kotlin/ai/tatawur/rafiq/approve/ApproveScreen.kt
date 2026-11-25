package ai.tatawur.rafiq.approve

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.client.ApprovalCard
import ai.tatawur.rafiq.client.ApprovalText
import ai.tatawur.rafiq.client.push.ApproveState
import ai.tatawur.rafiq.protocol.ApprovalDetail
import ai.tatawur.rafiq.protocol.ApprovalOption
import ai.tatawur.rafiq.protocol.ApprovalOptionKind
import ai.tatawur.rafiq.sessions.ApprovalOptions
import ai.tatawur.rafiq.ui.theme.RafiqTheme
import ai.tatawur.rafiq.ui.theme.plexMono
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview

/**
 * The Approve screen: the session, the whole request, and one button per option. The request is never
 * shortened: every line shows, invisible controls show as `<U+XXXX>`, and a request your server cut is marked.
 */
@Composable
fun ApproveScreen(
    state: ApproveState,
    onAnswer: (optionId: String) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = RafiqTheme.spacing
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .consumeWindowInsets(WindowInsets.safeDrawing)
                .padding(horizontal = spacing.medium, vertical = spacing.small),
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        when (state) {
            ApproveState.Loading -> {
                Message(stringResource(R.string.approve_loading))
            }

            is ApproveState.Ready -> {
                Request(state, onAnswer, Modifier.weight(1f))
            }

            ApproveState.AlreadyAnswered -> {
                Message(
                    stringResource(R.string.approve_already_answered),
                    onClose = onClose,
                )
            }

            ApproveState.Unreachable -> {
                Message(stringResource(R.string.approve_unreachable), onRetry, onClose)
            }

            is ApproveState.Refused -> {
                Message(
                    stringResource(R.string.push_result_failed, state.message),
                    onRetry,
                    onClose,
                )
            }

            ApproveState.LockTurnedOff -> {
                Message(stringResource(R.string.approval_lock_reset), onClose = onClose)
            }

            ApproveState.Done -> {
                Unit
            }
        }
    }
}

@Composable
private fun Request(
    state: ApproveState.Ready,
    onAnswer: (optionId: String) -> Unit,
    modifier: Modifier,
) {
    val spacing = RafiqTheme.spacing
    val colors = RafiqTheme.colors
    val approval = state.approval
    val text = ApprovalText.of(approval, expanded = true)
    Text(
        stringResource(R.string.approve_session, state.sessionLabel),
        style = MaterialTheme.typography.titleLarge,
        color = colors.text,
    )
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(spacing.xSmall),
    ) {
        Text(
            stringResource(R.string.approve_full_command),
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary,
        )
        if (text.showsLineCount) {
            Text(
                stringResource(R.string.approval_lines, text.lineCount),
                style = MaterialTheme.typography.labelSmall,
                color = colors.needsYou,
            )
        }
        val base = MaterialTheme.typography.bodyMedium
        val style = if (approval.detail != null) base.copy(fontFamily = plexMono) else base
        // One Text per line, never cut with an ellipsis, so what you confirm is all on screen.
        text.lines.forEach { line -> Text(line.ifEmpty { " " }, style = style, color = colors.text) }
        if (text.cut) {
            Text(
                stringResource(R.string.approval_cut),
                style = MaterialTheme.typography.labelSmall,
                color = colors.needsYou,
            )
        }
        val rule = text.sessionRule
        if (rule != null && approval.options.any { it.kind == ApprovalOptionKind.ALLOW_ALWAYS }) {
            Text(
                stringResource(R.string.approval_session_rule, rule),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
    }
    ApprovalOptions(options = approval.options, answering = state.answering, onAnswer = onAnswer)
}

@Composable
private fun Message(
    text: String,
    onRetry: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
) {
    val spacing = RafiqTheme.spacing
    Text(text, style = MaterialTheme.typography.bodyLarge, color = RafiqTheme.colors.text)
    if (onRetry != null) {
        OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = spacing.minTouchTarget)) {
            Text(stringResource(R.string.approve_retry))
        }
    }
    if (onClose != null) {
        TextButton(onClick = onClose, modifier = Modifier.heightIn(min = spacing.minTouchTarget)) {
            Text(stringResource(R.string.approve_close))
        }
    }
}

private val previewApproval =
    ApprovalCard(
        id = "apr_1",
        sessionId = "ses_weather_app",
        threadId = null,
        toolCallId = "call_1",
        description = "Run a command",
        options =
            listOf(
                ApprovalOption("once", ApprovalOptionKind.ALLOW_ONCE, "Allow once"),
                ApprovalOption("always", ApprovalOptionKind.ALLOW_ALWAYS, "Allow for this session"),
                ApprovalOption("deny", ApprovalOptionKind.DENY, "Deny"),
            ),
        expiresAtMs = null,
        detail =
            ApprovalDetail(
                text = (1..14).joinToString("\n") { "npm run test -- --shard=$it/14 --reporter=dot" },
                lineCount = 14,
                sessionRule = "Bash(npm run test:*)",
            ),
    )

@Preview(name = "Approve, long command", widthDp = 412, heightDp = 800)
@Composable
private fun ApproveLongPreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            ApproveScreen(ApproveState.Ready("weather-app", previewApproval), {}, {}, {})
        }
    }
}

@Preview(name = "Approve, already answered", widthDp = 412, heightDp = 400)
@Composable
private fun ApproveAnsweredPreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            ApproveScreen(ApproveState.AlreadyAnswered, {}, {}, {})
        }
    }
}

@Preview(name = "Approve, unreachable", widthDp = 412, heightDp = 400)
@Composable
private fun ApproveUnreachablePreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            ApproveScreen(ApproveState.Unreachable, {}, {}, {})
        }
    }
}
