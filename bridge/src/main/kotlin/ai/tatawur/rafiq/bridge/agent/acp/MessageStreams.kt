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
 *
 * Some ends are a guess: the agent's usage report for a finished reply, or a quiet period with no
 * new chunk. A message ended that way can be resumed. If the next chunk on its thread belongs to the
 * same ACP message, the message is sent again in full under the same id, streaming once more, so a
 * guess that came too early never splits one reply in two.
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

    // Messages ended by a guess, by thread, until anything else happens on the thread.
    private val resumable = mutableMapOf<String, Open>()

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
        if (current != null && current.sameStream(role, streamKey)) {
            current.text.append(text)
            out += EventPayload.MessageDelta(messageId = current.id, threadId = threadId, text = text)
            return
        }
        val paused = resumable[threadId]
        if (current == null && paused != null && paused.sameStream(role, streamKey) && streamKey !in UNKEYED) {
            resumable.remove(threadId)
            paused.text.append(text)
            open[threadId] = paused
            out += message(threadId, paused, isStreaming = true)
            return
        }
        end(threadId, out)
        val started = Open(newId(), role, streamKey, StringBuilder(text))
        open[threadId] = started
        out += message(threadId, started, isStreaming = true)
    }

    /**
     * Ends [threadId]'s open message, if it has one.
     *
     * @param canResume true when the end is a guess, so the same ACP message may still continue.
     */
    fun end(
        threadId: String,
        out: MutableList<EventPayload>,
        canResume: Boolean = false,
    ) {
        resumable.remove(threadId)
        val current = open.remove(threadId) ?: return
        if (canResume) resumable[threadId] = current
        out += message(threadId, current, isStreaming = false)
    }

    /** Ends every open message, for example when the turn is over. */
    fun endAll(out: MutableList<EventPayload>) {
        open.keys.toList().forEach { end(it, out) }
        resumable.clear()
    }

    private fun Open.sameStream(
        role: MessageRole,
        streamKey: String,
    ) = this.role == role && this.streamKey == streamKey

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

// The stream keys of chunks whose agent gave no message id. They cannot tell one message from the
// next, so a message ended by a guess is never resumed on one of them.
private val UNKEYED = setOf("agent:null", "session:null")
