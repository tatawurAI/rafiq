package ai.tatawur.rafiq.sessions

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.client.EchoState
import ai.tatawur.rafiq.client.PromptEcho
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.TurnPhase
import ai.tatawur.rafiq.protocol.api.SendRefusal
import ai.tatawur.rafiq.ui.theme.RafiqTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.delay

private const val TICK_MS = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L

/**
 * The line under the last message while a turn runs: what the agent is doing and for how long. The
 * elapsed time is worked out on your phone from when the turn started, so it ticks without traffic.
 */
@Composable
fun ProgressLine(
    progress: EventPayload.TurnProgress,
    modifier: Modifier = Modifier,
    nowMs: () -> Long = System::currentTimeMillis,
) {
    var now by remember { mutableLongStateOf(nowMs()) }
    LaunchedEffect(progress.startedAtMs) {
        while (progress.startedAtMs != null) {
            now = nowMs()
            delay(TICK_MS)
        }
    }
    val doing =
        when (progress.phase) {
            TurnPhase.RUNNING_STEP -> {
                progress.currentStep?.let { stringResource(R.string.progress_step, it) }
                    ?: stringResource(R.string.progress_working)
            }

            TurnPhase.WRITING -> {
                stringResource(R.string.progress_writing)
            }

            TurnPhase.WAITING -> {
                stringResource(R.string.progress_waiting)
            }

            TurnPhase.WORKING, TurnPhase.IDLE, TurnPhase.UNKNOWN -> {
                stringResource(R.string.progress_working)
            }
        }
    val text =
        progress.startedAtMs?.let { stringResource(R.string.progress_with_elapsed, doing, elapsed(now - it)) } ?: doing
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StreamingIndicator()
        Text(
            text = text,
            modifier = Modifier.padding(start = RafiqTheme.spacing.small),
            style = MaterialTheme.typography.labelMedium,
            color = RafiqTheme.colors.textSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun elapsed(ms: Long): String {
    val seconds = (ms / TICK_MS).coerceAtLeast(0)
    return when {
        seconds < SECONDS_PER_MINUTE -> {
            stringResource(R.string.elapsed_seconds, seconds.toInt())
        }

        seconds < SECONDS_PER_HOUR -> {
            stringResource(
                R.string.elapsed_minutes,
                (seconds / SECONDS_PER_MINUTE).toInt(),
                (seconds % SECONDS_PER_MINUTE).toInt(),
            )
        }

        else -> {
            stringResource(
                R.string.elapsed_hours,
                (seconds / SECONDS_PER_HOUR).toInt(),
                (seconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE).toInt(),
            )
        }
    }
}

/**
 * A prompt you sent to a session in tmux that the agent does not have yet, on the right like your other
 * messages, with where it is on its way. Once the agent has it, your message arrives as an event instead.
 */
@Composable
fun PromptEchoItem(
    echo: PromptEcho,
    modifier: Modifier = Modifier,
) {
    val spacing = RafiqTheme.spacing
    val colors = RafiqTheme.colors
    val notDelivered = echo.state == EchoState.NOT_DELIVERED
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Spacer(Modifier.fillMaxWidth(1f - spacing.bubbleMaxWidthFraction))
            Surface(
                modifier = Modifier.weight(1f, fill = false),
                shape = RafiqTheme.shapes.userBubble,
                color = colors.surface,
                border = BorderStroke(spacing.hairline, if (notDelivered) colors.needsYou else colors.borderStrong),
            ) {
                Text(
                    text = echo.text,
                    modifier = Modifier.padding(horizontal = spacing.smallMedium, vertical = spacing.small),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textSecondary,
                )
            }
        }
        val status =
            when (echo.state) {
                EchoState.SENT -> {
                    stringResource(R.string.echo_sent)
                }

                EchoState.QUEUED -> {
                    stringResource(R.string.echo_queued)
                }

                EchoState.NOT_DELIVERED -> {
                    stringResource(
                        R.string.echo_not_delivered,
                        refusalText(echo.code, echo.message),
                    )
                }
            }
        Text(
            text = status,
            modifier = Modifier.padding(top = spacing.xSmall).semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.labelSmall,
            color = if (notDelivered) colors.needsYou else colors.textMuted,
            textAlign = TextAlign.End,
        )
    }
}

/**
 * Why your server would not type a prompt, in this app's words when it knows the code, otherwise in your
 * server's own words.
 */
@Composable
fun refusalText(
    code: String?,
    message: String?,
): String {
    val known =
        when (code) {
            SendRefusal.AGENT_WAITING -> R.string.refusal_agent_waiting
            SendRefusal.INPUT_NOT_EMPTY -> R.string.refusal_input_not_empty
            SendRefusal.AGENT_NOT_RUNNING -> R.string.refusal_agent_not_running
            SendRefusal.STATE_UNKNOWN -> R.string.refusal_state_unknown
            SendRefusal.HOOKS_NOT_INSTALLED -> R.string.refusal_hooks_not_installed
            SendRefusal.NOT_CONFIRMED -> R.string.refusal_not_confirmed
            else -> null
        }
    return known?.let { stringResource(it) } ?: message.orEmpty()
}

/**
 * The send guard's refusal, just above the composer. Your draft stays, so you can send it again once the
 * reason is gone. When the project lacks hooks, it offers the hook setup.
 */
@Composable
fun RefusalLine(
    refusal: ComposerRefusal,
    onDismiss: () -> Unit,
    onOpenHooks: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val spacing = RafiqTheme.spacing
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        color = RafiqTheme.colors.surface,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(spacing.hairline, RafiqTheme.colors.needsYou),
    ) {
        Row(modifier = Modifier.padding(start = spacing.smallMedium), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = refusalText(refusal.code, refusal.message),
                modifier = Modifier.weight(1f).padding(vertical = spacing.small),
                style = MaterialTheme.typography.bodySmall,
                color = RafiqTheme.colors.text,
            )
            if (refusal.code == SendRefusal.HOOKS_NOT_INSTALLED && onOpenHooks != null) {
                TextButton(onClick = onOpenHooks, modifier = Modifier.heightIn(min = spacing.minTouchTarget)) {
                    Text(stringResource(R.string.hooks_offer_review))
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = spacing.minTouchTarget)) {
                Text(stringResource(R.string.refusal_dismiss))
            }
        }
    }
}

/** The offer to set up hooks, above the timeline of a session in tmux whose project has none. */
@Composable
fun HooksOffer(
    onReview: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = RafiqTheme.spacing
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = RafiqTheme.colors.surface,
    ) {
        Column(modifier = Modifier.padding(horizontal = spacing.medium, vertical = spacing.small)) {
            Text(
                stringResource(R.string.hooks_offer_text),
                style = MaterialTheme.typography.bodySmall,
                color = RafiqTheme.colors.text,
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = spacing.minTouchTarget)) {
                    Text(stringResource(R.string.hooks_offer_dismiss))
                }
                TextButton(onClick = onReview, modifier = Modifier.heightIn(min = spacing.minTouchTarget)) {
                    Text(stringResource(R.string.hooks_offer_review))
                }
            }
        }
    }
}

@Preview(name = "Chat in tmux: progress, echoes, refusal", widthDp = 360)
@Composable
private fun TmuxChatPreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.padding(RafiqTheme.spacing.medium),
                verticalArrangement = Arrangement.spacedBy(RafiqTheme.spacing.smallMedium),
            ) {
                HooksOffer(onReview = {}, onDismiss = {})
                ProgressLine(
                    EventPayload.TurnProgress("m", TurnPhase.RUNNING_STEP, "Bash: npm test", startedAtMs = 0),
                    nowMs = { 83_000 },
                )
                PromptEchoItem(PromptEcho("c-1", "Then update the README", EchoState.QUEUED))
                PromptEchoItem(
                    PromptEcho("c-2", "/compact", EchoState.NOT_DELIVERED, SendRefusal.INPUT_NOT_EMPTY, "Half-typed"),
                )
                RefusalLine(
                    ComposerRefusal(SendRefusal.HOOKS_NOT_INSTALLED, "No hooks"),
                    onDismiss = {},
                    onOpenHooks = {},
                )
            }
        }
    }
}
