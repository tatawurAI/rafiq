package ai.tatawur.rafiq.bridge.agent.acp

import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.ToolCallKind
import ai.tatawur.rafiq.protocol.ToolCallStatus
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallContent
import java.nio.file.Path

/**
 * Keeps the last known state of every tool call, because ACP sends a call once and then patches it,
 * while each Rafiq [EventPayload.ToolCall] carries the whole call.
 *
 * ACP tool call ids are unique only within one ACP session. Calls on the root session keep their id;
 * calls on a child session are prefixed with the child's id, so two sessions' `call_001` stay apart.
 */
internal class ToolCallTracker(
    private val projectPath: Path,
) {
    /** Which thread a tool call ran on and what it was called, for grouping a helper under it. */
    data class Origin(
        val threadId: String,
        val title: String,
    )

    private val calls = mutableMapOf<String, EventPayload.ToolCall>()

    /** The Rafiq id for ACP tool call [toolCallId] on [acpSessionId]. */
    fun rafiqId(
        acpSessionId: String,
        rootAcpSessionId: String?,
        toolCallId: String,
    ): String = if (acpSessionId == rootAcpSessionId) toolCallId else "$acpSessionId/$toolCallId"

    /** The thread and title of a call already seen, by its Rafiq id. */
    fun origin(id: String): Origin? = calls[id]?.let { Origin(it.threadId, it.inputSummary) }

    /** Records a new call. ACP's default status for a new call is pending. */
    fun started(
        id: String,
        threadId: String,
        update: SessionUpdate.ToolCall,
    ): EventPayload.ToolCall =
        store(
            EventPayload.ToolCall(
                id = id,
                threadId = threadId,
                kind = toolCallKind(update.kind),
                inputSummary = update.title,
                output = output(update.content),
                status = update.status?.let(::toolCallStatus) ?: ToolCallStatus.PENDING,
            ),
        )

    /** Applies a patch. Fields the patch leaves out keep their last value. */
    fun updated(
        id: String,
        threadId: String,
        update: SessionUpdate.ToolCallUpdate,
    ): EventPayload.ToolCall {
        val previous =
            calls[id] ?: EventPayload.ToolCall(
                id = id,
                threadId = threadId,
                kind = ToolCallKind.OTHER,
                inputSummary = UNTITLED_TOOL_CALL,
                status = ToolCallStatus.PENDING,
            )
        return store(
            previous.copy(
                kind = update.kind?.let(::toolCallKind) ?: previous.kind,
                inputSummary = update.title ?: previous.inputSummary,
                output = update.content?.let(::output) ?: previous.output,
                status = update.status?.let(::toolCallStatus) ?: previous.status,
            ),
        )
    }

    private fun store(call: EventPayload.ToolCall): EventPayload.ToolCall {
        calls[call.id] = call
        return call
    }

    /**
     * What the app shows under the call: text output and the files an edit touched. Terminal output
     * is not included, because the bridge does not offer the terminal capability. Long output is cut,
     * since events are stored, replayed and pushed, and a read tool's output is a whole file.
     */
    private fun output(content: List<ToolCallContent>): String? {
        val parts =
            content.mapNotNull { item ->
                when (item) {
                    is ToolCallContent.Content -> contentText(item.content)
                    is ToolCallContent.Diff -> "Edited ${displayPath(item.path)}"
                    is ToolCallContent.Terminal -> null
                }
            }
        if (parts.isEmpty()) return null
        val text = parts.joinToString("\n")
        return if (text.length <= OUTPUT_LIMIT_CHARS) text else text.take(OUTPUT_LIMIT_CHARS) + "\n[output cut]"
    }

    private fun displayPath(path: String): String {
        val absolute = runCatching { Path.of(path) }.getOrNull() ?: return path
        return if (absolute.startsWith(projectPath)) projectPath.relativize(absolute).toString() else path
    }
}

/** Readable text for an ACP content block, or null for one with nothing to read, such as audio. */
internal fun contentText(block: ContentBlock): String? =
    when (block) {
        is ContentBlock.Text -> block.text
        is ContentBlock.ResourceLink -> "[${block.name}](${block.uri})"
        is ContentBlock.Resource, is ContentBlock.Image, is ContentBlock.Audio -> null
    }

/** Enough for a phone screen. Kept well under a push payload's size. */
private const val OUTPUT_LIMIT_CHARS = 2000

private const val UNTITLED_TOOL_CALL = "Tool call"
