package ai.tatawur.rafiq.sessions

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.ui.theme.RafiqTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview

/**
 * What the composer's mic button needs to know and do.
 *
 * @property listening true while the recogniser hears you.
 * @property onToggle starts listening, asking for the microphone first if needed, or stops.
 */
@Immutable
data class DictationControl(
    val listening: Boolean,
    val onToggle: () -> Unit,
)

/** A quick-reply chip: what it does and what it says. */
@Immutable
data class QuickReplyChip(
    val reply: QuickReply,
    val label: String,
)

/** The quick replies M2 ships with. Editing them comes later. */
@Composable
fun defaultQuickReplies(): List<QuickReplyChip> {
    val yes = stringResource(R.string.quick_reply_yes)
    val explain = stringResource(R.string.quick_reply_explain)
    val tests = stringResource(R.string.quick_reply_tests)
    return listOf(
        QuickReplyChip(QuickReply.Prompt(yes), yes),
        QuickReplyChip(QuickReply.Prompt(explain), explain),
        QuickReplyChip(QuickReply.Prompt(tests), tests),
        QuickReplyChip(QuickReply.Stop, stringResource(R.string.quick_reply_stop)),
    )
}

/**
 * What the composer shows.
 *
 * @property target what the hint calls the session, for example its title.
 * @property canPrompt false while the main thread works. You can still type.
 * @property canStop true while there is a turn for "Stop" to stop.
 */
@Immutable
data class ComposerState(
    val draft: String,
    val target: String,
    val canPrompt: Boolean,
    val canSend: Boolean,
    val canStop: Boolean,
)

/**
 * Quick-reply chips over a text field, a mic and a send button.
 *
 * You can type while the agent works, but send and the prompt chips wait for `canPrompt`. "Stop"
 * is the opposite: it is enabled only while there is a turn to stop.
 *
 * @param dictation the mic, or null to hide it when the phone has no on-device recognition.
 */
@Composable
fun Composer(
    state: ComposerState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    quickReplies: List<QuickReplyChip>,
    onQuickReply: (QuickReply) -> Unit,
    dictation: DictationControl?,
    modifier: Modifier = Modifier,
) {
    val spacing = RafiqTheme.spacing
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        QuickReplyRow(quickReplies, state.canPrompt, state.canStop, onQuickReply)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
            val hint =
                if (state.canPrompt) {
                    stringResource(R.string.composer_hint, state.target)
                } else {
                    stringResource(R.string.composer_hint_busy)
                }
            DraftField(state.draft, hint, onDraftChange, Modifier.weight(1f))
            if (dictation != null) MicButton(dictation)
            SendButton(state.canSend, onSend)
        }
    }
}

@Composable
private fun QuickReplyRow(
    quickReplies: List<QuickReplyChip>,
    canPrompt: Boolean,
    canStop: Boolean,
    onQuickReply: (QuickReply) -> Unit,
) {
    val colors = RafiqTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(RafiqTheme.spacing.small),
    ) {
        quickReplies.forEach { chip ->
            AssistChip(
                onClick = { onQuickReply(chip.reply) },
                label = { Text(chip.label) },
                enabled = if (chip.reply is QuickReply.Stop) canStop else canPrompt,
                shape = RafiqTheme.shapes.pill,
                colors =
                    AssistChipDefaults.assistChipColors(
                        labelColor = colors.textSecondary,
                        disabledLabelColor = colors.textMuted.copy(alpha = DISABLED_ALPHA),
                    ),
                border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = colors.borderStrong),
            )
        }
    }
}

@Composable
private fun DraftField(
    draft: String,
    hint: String,
    onDraftChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = RafiqTheme.spacing
    val colors = RafiqTheme.colors
    BasicTextField(
        value = draft,
        onValueChange = onDraftChange,
        modifier = modifier.semantics { contentDescription = hint },
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.text),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        maxLines = MAX_LINES,
        decorationBox = { field ->
            Box(
                modifier =
                    Modifier
                        .heightIn(min = spacing.composerHeight)
                        .border(spacing.hairline, colors.borderStrong, RafiqTheme.shapes.pill)
                        .padding(horizontal = spacing.medium, vertical = spacing.smallMedium),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (draft.isEmpty()) {
                    Text(hint, style = MaterialTheme.typography.bodyLarge, color = colors.textMuted, maxLines = 1)
                }
                field()
            }
        },
    )
}

@Composable
private fun MicButton(dictation: DictationControl) {
    val spacing = RafiqTheme.spacing
    val colors = RafiqTheme.colors
    val description =
        stringResource(if (dictation.listening) R.string.composer_listening else R.string.composer_dictate)
    OutlinedIconButton(
        onClick = dictation.onToggle,
        modifier = Modifier.size(spacing.composerHeight),
        border = BorderStroke(spacing.hairline, if (dictation.listening) colors.accent else colors.borderStrong),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_mic),
            contentDescription = description,
            modifier = Modifier.size(spacing.icon),
            tint = if (dictation.listening) colors.accent else colors.text,
        )
    }
}

@Composable
private fun SendButton(
    enabled: Boolean,
    onSend: () -> Unit,
) {
    val spacing = RafiqTheme.spacing
    val colors = RafiqTheme.colors
    FilledIconButton(
        onClick = onSend,
        enabled = enabled,
        modifier = Modifier.size(spacing.composerHeight),
        colors =
            IconButtonDefaults.filledIconButtonColors(
                containerColor = colors.accent,
                contentColor = colors.ground,
                disabledContainerColor = colors.border,
                disabledContentColor = colors.textMuted,
            ),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_send),
            contentDescription = stringResource(R.string.composer_send),
            modifier = Modifier.size(spacing.icon),
        )
    }
}

private const val MAX_LINES = 5
private const val DISABLED_ALPHA = 0.6f

@Preview(name = "Composer, ready", widthDp = 360)
@Composable
private fun ComposerReadyPreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Composer(
                state = ComposerState("", "weather-app", canPrompt = true, canSend = false, canStop = false),
                onDraftChange = {},
                onSend = {},
                quickReplies = defaultQuickReplies(),
                onQuickReply = {},
                dictation = DictationControl(listening = false, onToggle = {}),
                modifier = Modifier.padding(RafiqTheme.spacing.medium),
            )
        }
    }
}

@Preview(name = "Composer, agent working", widthDp = 412)
@Composable
private fun ComposerBusyPreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Composer(
                state =
                    ComposerState(
                        "Then update the README",
                        "weather-app",
                        canPrompt = false,
                        canSend = false,
                        canStop = true,
                    ),
                onDraftChange = {},
                onSend = {},
                quickReplies = defaultQuickReplies(),
                onQuickReply = {},
                dictation = DictationControl(listening = true, onToggle = {}),
                modifier = Modifier.padding(RafiqTheme.spacing.medium),
            )
        }
    }
}
