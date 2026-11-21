package ai.tatawur.rafiq.sessions

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.client.ApprovalCard
import ai.tatawur.rafiq.protocol.ApprovalOption
import ai.tatawur.rafiq.protocol.ApprovalOptionKind
import ai.tatawur.rafiq.ui.theme.RafiqTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview

/**
 * One button per option, in the order the contract gives: allow once, allow always, deny, then
 * unknown kinds. Allow once is the filled button. The buttons wrap onto more lines on a narrow
 * screen instead of shrinking below the touch target.
 *
 * @param answering true while an answer is on its way. Every button is disabled until the
 *   repository drops the approval, so a lost answer is never shown as answered.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ApprovalOptions(
    options: List<ApprovalOption>,
    answering: Boolean,
    onAnswer: (optionId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = RafiqTheme.spacing
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
            verticalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            options.forEach { option ->
                val buttonModifier = Modifier.heightIn(min = spacing.minTouchTarget)
                if (option.kind == ApprovalOptionKind.ALLOW_ONCE) {
                    Button(
                        onClick = { onAnswer(option.id) },
                        enabled = !answering,
                        modifier = buttonModifier,
                        shape = MaterialTheme.shapes.small,
                    ) { Text(option.label) }
                } else {
                    OutlinedButton(
                        onClick = { onAnswer(option.id) },
                        enabled = !answering,
                        modifier = buttonModifier,
                        shape = MaterialTheme.shapes.small,
                        border = BorderStroke(spacing.hairline, RafiqTheme.colors.borderStrong),
                    ) { Text(option.label, color = RafiqTheme.colors.text) }
                }
            }
        }
        if (answering) {
            Text(
                text = stringResource(R.string.approval_sending),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall,
                color = RafiqTheme.colors.textMuted,
            )
        }
    }
}

/**
 * The newest open approval, pinned above the composer so you can answer it from wherever you have
 * scrolled. On a short screen it starts as two lines with an "Answer" button, which keeps the chat
 * readable on the cover screen; the full options are one tap away.
 *
 * @param compact true on short screens.
 * @param onJump opens the approval's thread at its tool card, or null when its thread is not known.
 */
@Composable
fun PinnedApproval(
    approval: ApprovalCard,
    compact: Boolean,
    answering: Boolean,
    onAnswer: (optionId: String) -> Unit,
    onJump: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val spacing = RafiqTheme.spacing
    var expanded by rememberSaveable(approval.id) { mutableStateOf(false) }
    val showOptions = !compact || expanded
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = RafiqTheme.colors.surface,
        border = BorderStroke(spacing.hairline, RafiqTheme.colors.needsYou),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = spacing.smallMedium, vertical = spacing.small),
            verticalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.approval_pinned).uppercase(),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = RafiqTheme.colors.needsYou,
                )
                if (onJump != null) {
                    TextButton(onClick = onJump, modifier = Modifier.heightIn(min = spacing.minTouchTarget)) {
                        Text(stringResource(R.string.approval_jump))
                    }
                }
            }
            Text(
                text = approval.description,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = RafiqTheme.colors.text,
                maxLines = if (showOptions) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (showOptions) {
                ApprovalOptions(approval.options, answering, onAnswer)
            } else {
                Button(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = spacing.minTouchTarget),
                    shape = MaterialTheme.shapes.small,
                ) { Text(stringResource(R.string.approval_expand)) }
            }
        }
    }
}

internal val previewApproval =
    ApprovalCard(
        id = "a1",
        sessionId = "s1",
        threadId = "t1",
        toolCallId = "c1",
        description = "Run npm test -- routes after editing the rate limiter?",
        options =
            listOf(
                ApprovalOption("once", ApprovalOptionKind.ALLOW_ONCE, "Allow once"),
                ApprovalOption("always", ApprovalOptionKind.ALLOW_ALWAYS, "Always allow npm test"),
                ApprovalOption("deny", ApprovalOptionKind.DENY, "Deny"),
            ),
        expiresAtMs = null,
    )

@Preview(name = "Pinned approval, tall", widthDp = 412)
@Composable
private fun PinnedApprovalPreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            PinnedApproval(previewApproval, compact = false, answering = false, onAnswer = {}, onJump = {})
        }
    }
}

@Preview(name = "Pinned approval, cover", widthDp = 360)
@Composable
private fun PinnedApprovalCompactPreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            PinnedApproval(previewApproval, compact = true, answering = false, onAnswer = {}, onJump = {})
        }
    }
}
