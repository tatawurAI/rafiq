package ai.tatawur.rafiq.bridge.agent.acp

import ai.tatawur.rafiq.protocol.ContentBlock
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.MessageRole

/**
 * Turns ACP's message chunks into one streaming Rafiq message per run of chunks.
 *
 * The first chunk of a run is a [EventPayload.Message] with `streaming` set, and later chunks are
 * [EventPayload.MessageDelta]s. A run ends when anything else happens on its thread, when the chunk
 * belongs to a different ACP message, or when the turn ends. The message is then sent once more under
 * the same id, complete and no longer streaming, so a reader that replaces a message by id ends up
 * with the whole text.
 */
internal class MessageStreams(
    private val newId: () -> String,
) {
    private class Open(
        val id: String,
        val role: MessageRole,
        val streamKey: String,
        val text: StringBuilder,
    )

    private val open = mutableMapOf<String, Open>()

    /**
     * Appends [text] to [threadId]'s open message, or starts one.
     *
     * @param streamKey identifies the ACP message the chunk belongs to. Chunks with different keys are
     *   different messages even when they arrive back to back.
     */
    fun append(
        threadId: String,
        role: MessageRole,
        streamKey: String,
        text: String,
        out: MutableList<EventPayload>,
    ) {
        val current = open[threadId]
        if (current != null && current.streamKey == streamKey && current.role == role) {
            current.text.append(text)
            out += EventPayload.MessageDelta(messageId = current.id, threadId = threadId, text = text)
            return
        }
        end(threadId, out)
        val started = Open(newId(), role, streamKey, StringBuilder(text))
        open[threadId] = started
        out += message(threadId, started, isStreaming = true)
    }

    /** Ends [threadId]'s open message, if it has one. */
    fun end(
        threadId: String,
        out: MutableList<EventPayload>,
    ) {
        val current = open.remove(threadId) ?: return
        out += message(threadId, current, isStreaming = false)
    }

    /** Ends every open message, for example when the turn is over. */
    fun endAll(out: MutableList<EventPayload>) {
        open.keys.toList().forEach { end(it, out) }
    }

    private fun message(
        threadId: String,
        current: Open,
        isStreaming: Boolean,
    ) = EventPayload.Message(
        id = current.id,
        threadId = threadId,
        role = current.role,
        // Coding agents write CommonMark, so agent text is rendered as Markdown.
        content = listOf(ContentBlock.Markdown(current.text.toString())),
        isStreaming = isStreaming,
    )
}
