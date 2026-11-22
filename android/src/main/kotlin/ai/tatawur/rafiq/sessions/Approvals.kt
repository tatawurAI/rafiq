package ai.tatawur.rafiq.sessions

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.client.ApprovalCard
import ai.tatawur.rafiq.protocol.ApprovalOption
import ai.tatawur.rafiq.protocol.ApprovalOptionKind
import ai.tatawur.rafiq.ui.theme.RafiqTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview

/**
 * One button per option, in the order the contract gives: allow once, allow always, deny, then
 * unknown kinds. Allow once is the filled button.
 *
 * @param answering true while an answer is on its way. Every button is disabled until the
 *   repository drops the approval, so a lost answer is never shown as answered.
 * @param twoColumns lays the buttons out two to a row at full size, for the half-folded bottom
 *   pane. Otherwise they wrap onto more lines on a narrow screen instead of shrinking below the
 *   touch target.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ApprovalOptions(
    options: List<ApprovalOption>,
    answering: Boolean,
    onAnswer: (optionId: String) -> Unit,
    modifier: Modifier = Modifier,
    twoColumns: Boolean = false,
) {
    val spacing = RafiqTheme.spacing
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        if (twoColumns) {
            options.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                    pair.forEach { option ->
                        OptionButton(
                            option,
                            answering,
                            onAnswer,
                            Modifier.weight(1f).heightIn(min = spacing.largeTouchTarget),
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.small),
                verticalArrangement = Arrangement.spacedBy(spacing.small),
            ) {
                options.forEach { option ->
                    OptionButton(option, answering, onAnswer, Modifier.heightIn(min = spacing.minTouchTarget))
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

@Composable
private fun OptionButton(
    option: ApprovalOption,
    answering: Boolean,
    onAnswer: (optionId: String) -> Unit,
    modifier: Modifier,
) {
    if (option.kind == ApprovalOptionKind.ALLOW_ONCE) {
        Button(
            onClick = { onAnswer(option.id) },
            enabled = !answering,
            modifier = modifier,
            shape = MaterialTheme.shapes.small,
        ) { Text(option.label, textAlign = TextAlign.Center) }
    } else {
        OutlinedButton(
            onClick = { onAnswer(option.id) },
            enabled = !answering,
            modifier = modifier,
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(RafiqTheme.spacing.hairline, RafiqTheme.colors.borderStrong),
        ) { Text(option.label, color = RafiqTheme.colors.text, textAlign = TextAlign.Center) }
    }
}

/**
 * The newest open approval, pinned above the composer so you can answer it from wherever you have
 * scrolled. It is one short card: up to two lines of the request and an "Answer" button. Tapping
 * the card or "Answer" brings the approval's tool card into view, where the full options are. When
 * the screen has no card to go to, "Answer" opens the options in place instead.
 *
 * @param onJump opens the approval's thread at its tool card, or null when its thread is not known.
 */
@Composable
fun PinnedApproval(
    approval: ApprovalCard,
    answering: Boolean,
    onAnswer: (optionId: String) -> Unit,
    onJump: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val spacing = RafiqTheme.spacing
    var expanded by rememberSaveable(approval.id) { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = RafiqTheme.colors.surface,
        border = BorderStroke(spacing.hairline, RafiqTheme.colors.needsYou),
    ) {
        if (expanded) {
            Column(
                modifier = Modifier.padding(spacing.smallMedium),
                verticalArrangement = Arrangement.spacedBy(spacing.small),
            ) {
                ApprovalDescription(approval.description, maxLines = Int.MAX_VALUE)
                ApprovalOptions(approval.options, answering, onAnswer)
            }
        } else {
            val jumpLabel = stringResource(R.string.approval_jump)
            Row(
                modifier =
                    Modifier
                        .heightIn(min = spacing.largeTouchTarget)
                        .then(
                            if (onJump != null) {
                                Modifier.clickable(role = Role.Button, onClickLabel = jumpLabel, onClick = onJump)
                            } else {
                                Modifier
                            },
                        ).padding(start = spacing.smallMedium, end = spacing.small),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.small),
            ) {
                ApprovalDescription(approval.description, maxLines = 2, modifier = Modifier.weight(1f))
                Button(
                    onClick = onJump ?: { expanded = true },
                    enabled = !answering,
                    modifier = Modifier.heightIn(min = spacing.minTouchTarget),
                    shape = MaterialTheme.shapes.small,
                ) { Text(stringResource(if (answering) R.string.approval_sending_short else R.string.approval_expand)) }
            }
        }
    }
}

/**
 * The pinned approval in the half-folded bottom pane, where there is room for every option at
 * full size. The request shows up to three lines. Tapping it brings the tool card into view above
 * the hinge.
 */
@Composable
fun PinnedApprovalFull(
    approval: ApprovalCard,
    answering: Boolean,
    onAnswer: (optionId: String) -> Unit,
    onJump: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val spacing = RafiqTheme.spacing
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = RafiqTheme.colors.surface,
        border = BorderStroke(spacing.hairline, RafiqTheme.colors.needsYou),
    ) {
        Column(
            modifier = Modifier.padding(spacing.smallMedium),
            verticalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            val jumpLabel = stringResource(R.string.approval_jump)
            ApprovalDescription(
                approval.description,
                maxLines = 3,
                modifier =
                    if (onJump != null) {
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = spacing.minTouchTarget)
                            .clickable(role = Role.Button, onClickLabel = jumpLabel, onClick = onJump)
                    } else {
                        Modifier
                    },
            )
            ApprovalOptions(approval.options, answering, onAnswer, twoColumns = true)
        }
    }
}

@Composable
private fun ApprovalDescription(
    text: String,
    maxLines: Int,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.CenterStart) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = RafiqTheme.colors.text,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
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

@Preview(name = "Pinned approval", widthDp = 360)
@Composable
private fun PinnedApprovalPreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            PinnedApproval(previewApproval, answering = false, onAnswer = {}, onJump = {})
        }
    }
}

@Preview(name = "Pinned approval, half-folded bottom pane", widthDp = 360)
@Composable
private fun PinnedApprovalFullPreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            PinnedApprovalFull(previewApproval, answering = false, onAnswer = {}, onJump = {})
        }
    }
}
