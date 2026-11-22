package ai.tatawur.rafiq.sessions

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.client.NoticeKind
import ai.tatawur.rafiq.client.TimelineItem
import ai.tatawur.rafiq.protocol.ContentBlock
import ai.tatawur.rafiq.protocol.MessageRole
import ai.tatawur.rafiq.protocol.ToolCallKind
import ai.tatawur.rafiq.protocol.ToolCallStatus
import ai.tatawur.rafiq.ui.components.MarkdownText
import ai.tatawur.rafiq.ui.theme.RafiqTheme
import ai.tatawur.rafiq.ui.theme.plexMono
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview

/**
 * A chat message. Yours sit in a bubble on the right. The agent's run full width with no bubble, so
 * long replies read like a page. Bridge messages are a quiet line.
 */
@Composable
fun MessageItem(
    message: TimelineItem.Message,
    modifier: Modifier = Modifier,
) {
    val spacing = RafiqTheme.spacing
    when (message.role) {
        MessageRole.USER -> {
            Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                // Keeps a margin on the left so your bubble never spans the whole width.
                Spacer(Modifier.fillMaxWidth(1f - spacing.bubbleMaxWidthFraction))
                Surface(
                    modifier = Modifier.weight(1f, fill = false),
                    shape = RafiqTheme.shapes.userBubble,
                    color = RafiqTheme.colors.border,
                ) {
                    MessageBlocks(
                        message = message,
                        color = RafiqTheme.colors.text,
                        modifier =
                            Modifier.padding(
                                horizontal = spacing.smallMedium + spacing.xSmall / 2,
                                vertical = spacing.smallMedium,
                            ),
                    )
                }
            }
        }

        MessageRole.SYSTEM -> {
            MessageBlocks(message = message, color = RafiqTheme.colors.textMuted, modifier = modifier.fillMaxWidth())
        }

        MessageRole.AGENT, MessageRole.UNKNOWN -> {
            MessageBlocks(message = message, color = RafiqTheme.colors.text, modifier = modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun MessageBlocks(
    message: TimelineItem.Message,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(RafiqTheme.spacing.small)) {
        message.blocks.forEach { block ->
            when (block) {
                is ContentBlock.Markdown -> {
                    MarkdownText(block.markdown, color = color)
                }

                is ContentBlock.Text -> {
                    Text(block.text, style = MaterialTheme.typography.bodyLarge, color = color)
                }

                is ContentBlock.Image -> {
                    Text(
                        text =
                            block.altText?.let { stringResource(R.string.image_placeholder, it) }
                                ?: stringResource(R.string.image_placeholder_no_alt),
                        style = MaterialTheme.typography.bodySmall,
                        color = RafiqTheme.colors.textMuted,
                    )
                }

                is ContentBlock.Unknown -> {
                    Text(
                        text =
                            block.type?.let { stringResource(R.string.unknown_content, it) }
                                ?: stringResource(R.string.unknown_content_untyped),
                        style = MaterialTheme.typography.bodySmall,
                        color = RafiqTheme.colors.textMuted,
                    )
                }
            }
        }
        if (message.isStreaming) StreamingIndicator()
    }
}

/** A pulsing caret under a message that is still arriving. */
@Composable
fun StreamingIndicator(modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "streaming").animateFloat(
        initialValue = 1f,
        targetValue = PULSE_MIN_ALPHA,
        animationSpec = infiniteRepeatable(tween(PULSE_MS), RepeatMode.Reverse),
        label = "streaming-alpha",
    )
    val description = stringResource(R.string.streaming)
    Text(
        text = CARET,
        modifier = modifier.alpha(pulse).semantics { contentDescription = description },
        style = MaterialTheme.typography.bodyLarge,
        color = RafiqTheme.colors.accent,
    )
}

private const val CARET = "▍"
private const val PULSE_MS = 600
private const val PULSE_MIN_ALPHA = 0.2f

@StringRes
private fun noticeSentence(kind: NoticeKind): Int =
    when (kind) {
        NoticeKind.AUTH_REQUIRED -> R.string.notice_auth_required
        NoticeKind.PREVIEW -> R.string.notice_preview
        NoticeKind.FILE_CHANGED -> R.string.notice_file_changed
        NoticeKind.UNKNOWN_EVENT -> R.string.notice_unknown_event
        NoticeKind.ASK_AT_DESK -> R.string.notice_ask_at_desk
        NoticeKind.CHAT_INCOMPLETE -> R.string.notice_chat_incomplete
    }

/**
 * A sign-in request, preview, file change, unknown event, approval sent back to the desk or a turn
 * Chat may be missing, as one plain line. The notice carries only the event's own words, such as a
 * path or a provider, and the line puts them in a sentence for its kind. A notice with a URL, such
 * as a sign-in page, opens it in the browser when tapped, and "Chat may be missing part of this
 * turn" opens Terminal through [onOpenTerminal] when the screen has it.
 */
@Composable
fun NoticeLine(
    notice: TimelineItem.Notice,
    modifier: Modifier = Modifier,
    onOpenTerminal: (() -> Unit)? = null,
) {
    val color =
        when (notice.kind) {
            NoticeKind.AUTH_REQUIRED, NoticeKind.ASK_AT_DESK, NoticeKind.CHAT_INCOMPLETE -> RafiqTheme.colors.needsYou
            NoticeKind.PREVIEW, NoticeKind.FILE_CHANGED, NoticeKind.UNKNOWN_EVENT -> RafiqTheme.colors.textMuted
        }
    val uriHandler = LocalUriHandler.current
    val url = notice.url
    // "Chat may be incomplete" opens Terminal, the ground truth, when this screen has it.
    val onTap: (() -> Unit)? =
        when {
            url != null -> {
                { uriHandler.openUri(url) }
            }

            notice.kind == NoticeKind.CHAT_INCOMPLETE -> {
                onOpenTerminal
            }

            else -> {
                null
            }
        }
    val tappable =
        if (onTap != null) {
            Modifier
                .heightIn(min = RafiqTheme.spacing.minTouchTarget)
                .clickable(role = Role.Button, onClick = onTap)
        } else {
            Modifier
        }
    val sentence =
        if (notice.kind == NoticeKind.CHAT_INCOMPLETE && onOpenTerminal == null) {
            R.string.notice_chat_incomplete_no_terminal
        } else {
            noticeSentence(notice.kind)
        }
    Text(
        text = stringResource(sentence, notice.text),
        modifier = modifier.fillMaxWidth().then(tappable).padding(vertical = RafiqTheme.spacing.xSmall),
        style = MaterialTheme.typography.labelMedium,
        color = color,
    )
}

private val previewTools =
    listOf(
        TimelineItem.Tool(
            "e",
            1,
            ToolCallKind.EDIT,
            "Edited 3 files",
            "src/forecast/Units.kt  +48",
            ToolCallStatus.COMPLETED,
            null,
        ),
        TimelineItem.Tool(
            "c",
            2,
            ToolCallKind.COMMAND,
            "./gradlew :forecast:test",
            "38 tests passed",
            ToolCallStatus.IN_PROGRESS,
            null,
        ),
        TimelineItem.Tool(
            "f",
            3,
            ToolCallKind.FETCH,
            "https://api.example.com/docs/units",
            null,
            ToolCallStatus.FAILED,
            null,
        ),
        TimelineItem.Tool(
            "a",
            4,
            ToolCallKind.COMMAND,
            "npm test -- routes",
            null,
            ToolCallStatus.PENDING,
            previewApproval,
        ),
    )

@Preview(name = "Timeline items", widthDp = 360, heightDp = 900)
@Composable
private fun TimelineItemsPreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.padding(RafiqTheme.spacing.medium),
                verticalArrangement = Arrangement.spacedBy(RafiqTheme.spacing.smallMedium),
            ) {
                MessageItem(
                    TimelineItem.Message(
                        "u",
                        0,
                        MessageRole.USER,
                        listOf(ContentBlock.Text("Move the forecast parsing into its own module.")),
                        isStreaming = false,
                    ),
                )
                MessageItem(
                    TimelineItem.Message(
                        "m",
                        0,
                        MessageRole.AGENT,
                        listOf(
                            ContentBlock.Markdown("Units now live in **their own module**. Waiting on the test writer"),
                        ),
                        isStreaming = true,
                    ),
                )
                previewTools.forEachIndexed { index, tool ->
                    ToolCard(tool, expanded = index == 0, onToggle = {}, answering = false, onAnswer = {})
                }
                NoticeLine(
                    TimelineItem.Notice("n", 0, NoticeKind.FILE_CHANGED, "src/forecast/Units.kt (modified)", null),
                )
            }
        }
    }
}
