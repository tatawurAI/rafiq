package ai.tatawur.rafiq.bridge.tmux.reader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** What the reader takes from one line of Claude Code's session record. Every other line type is skipped. */
internal sealed interface RecordEntry {
    /** A prompt you typed. [promptId] is the same id the prompt's `UserPromptSubmit` hook carries. */
    data class UserPrompt(
        val text: String,
        val promptId: String? = null,
    ) : RecordEntry

    /**
     * One text block of an assistant message. [key] is stable for the block: the record line's uuid and the block's
     * place in the line, so a reply filled from the record twice is one message.
     */
    data class AssistantText(
        val text: String,
        val key: String? = null,
    ) : RecordEntry

    data class ToolUse(
        val id: String,
        val name: String,
        val input: JsonObject,
    ) : RecordEntry

    data class ToolResult(
        val toolUseId: String,
        val text: String?,
        val isError: Boolean,
    ) : RecordEntry
}

/**
 * A read of the record from a byte offset: the entries of every whole line after it, and the offset just past
 * the last whole line, where the next read starts. A line the agent is still writing is left for that read.
 */
internal class RecordChunk(
    val entries: List<RecordEntry>,
    val nextOffset: Long,
)

/**
 * Reads Claude Code's session record, a JSON Lines file the agent appends to, from a saved byte offset. The
 * record is only for filling gaps (ADR 0013): it lags the hooks, and one turn can add hundreds of kilobytes of
 * request data, so it is never parsed from the start. A line that is not JSON, a line type the reader does not
 * know and a field with an unexpected shape are all skipped.
 */
internal object SessionRecord {
    /** The most bytes one read takes. A longer gap is read over several calls. */
    const val MAX_READ_BYTES = 8 * 1024 * 1024

    /** How much of the end of a record a first attach shows, so a long session is not parsed whole. */
    const val FIRST_ATTACH_TAIL_BYTES = 256L * 1024

    private val json = Json { ignoreUnknownKeys = true }

    /** The record's size in bytes, or null when it cannot be read. */
    fun size(path: Path): Long? =
        runCatching {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).size()
        }.getOrNull()

    /**
     * [raw], a record path from a hook, when it is safe to read: an absolute path to a regular file, or one that does
     * not exist yet, whose folder resolves inside [root], Claude Code's folder of records. A Claude Code that keeps
     * its settings elsewhere (`CLAUDE_CONFIG_DIR`) writes records to `<config>/projects/<project>/<id>.jsonl`, so
     * such a path inside [home] is accepted too. A link is refused, so a hook cannot point the bridge at another
     * file. Null otherwise.
     */
    @Suppress("ReturnCount") // One early refusal per check.
    fun safePath(
        raw: String,
        root: Path?,
        home: Path? = null,
    ): Path? {
        val path = runCatching { Path.of(raw) }.getOrNull()?.takeIf { it.isAbsolute }?.normalize() ?: return null
        val folder = path.parent?.let { runCatching { it.toRealPath() }.getOrNull() } ?: return null
        val file = path.fileName ?: return null
        if (!file.toString().endsWith(".jsonl")) return null
        val realRoot = root?.let { runCatching { it.toRealPath() }.getOrNull() }
        val realHome = home?.let { runCatching { it.toRealPath() }.getOrNull() }
        val inRoot = realRoot != null && folder.startsWith(realRoot)
        val inConfig =
            realHome != null && folder.parent?.fileName?.toString() == "projects" && folder.startsWith(realHome)
        if (!inRoot && !inConfig) return null
        val resolved = folder.resolve(file)
        val usable =
            !Files.exists(resolved, LinkOption.NOFOLLOW_LINKS) ||
                Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)
        return resolved.takeIf { usable }
    }

    /**
     * Where a first read of [path] starts: [FIRST_ATTACH_TAIL_BYTES] from the end, moved forward to the start
     * of the next whole line. Null when the file cannot be read.
     */
    @Suppress("ReturnCount") // One early answer per unreadable or short file.
    fun tailOffset(path: Path): Long? {
        val size = size(path) ?: return null
        if (size <= FIRST_ATTACH_TAIL_BYTES) return 0
        val start = size - FIRST_ATTACH_TAIL_BYTES
        val bytes = readBytes(path, start, FIRST_ATTACH_TAIL_BYTES.toInt()) ?: return null
        val newline = bytes.indexOf('\n'.code.toByte())
        return if (newline < 0) size else start + newline + 1
    }

    /**
     * Reads the whole lines after [offset]. A record that shrank below [offset] was replaced, and is read from
     * its new end on the next call. Null when the file cannot be read.
     */
    @Suppress("ReturnCount") // One early answer per unreadable, replaced or unfinished record.
    fun read(
        path: Path,
        offset: Long,
        maxBytes: Int = MAX_READ_BYTES,
    ): RecordChunk? {
        val size = size(path) ?: return null
        if (size < offset) return RecordChunk(emptyList(), size)
        val bytes = readBytes(path, offset, maxBytes) ?: return null
        val lastNewline = bytes.lastIndexOf('\n'.code.toByte())
        if (lastNewline < 0) {
            // A full buffer with no line end is one oversized line: step over it rather than stall here.
            return RecordChunk(emptyList(), if (bytes.size >= maxBytes) offset + bytes.size else offset)
        }
        val text = String(bytes, 0, lastNewline, Charsets.UTF_8)
        val entries = text.lineSequence().flatMap(::parseLine).toList()
        return RecordChunk(entries, offset + lastNewline + 1)
    }

    /** The entries of one record line. Empty for anything the reader does not use or cannot parse. */
    @Suppress("ReturnCount") // Tolerant parsing: each shape the reader does not use is skipped right away.
    fun parseLine(line: String): List<RecordEntry> {
        if (line.isBlank()) return emptyList()
        val o = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return emptyList()
        if (o.bool("isSidechain") == true || o.bool("isMeta") == true) return emptyList()
        val content = o.obj("message")?.get("content")
        return when (o.str("type")) {
            "assistant" -> {
                val uuid = o.str("uuid")
                (content as? JsonArray).orEmpty().mapIndexedNotNull { index, block ->
                    assistantBlock(block, uuid?.let { "$it:$index" })
                }
            }

            "user" -> {
                userContent(content, o.str("promptId"))
            }

            else -> {
                emptyList()
            }
        }
    }

    private fun assistantBlock(
        element: kotlinx.serialization.json.JsonElement,
        key: String?,
    ): RecordEntry? {
        val block = element as? JsonObject ?: return null
        return when (block.str("type")) {
            "text" -> {
                block.str("text")?.let { RecordEntry.AssistantText(it, key) }
            }

            "tool_use" -> {
                val id = block.str("id")
                val name = block.str("name")
                val input = block.obj("input") ?: EMPTY_OBJECT
                if (id != null && name != null) RecordEntry.ToolUse(id, name, input) else null
            }

            else -> {
                null
            }
        }
    }

    private fun userContent(
        content: kotlinx.serialization.json.JsonElement?,
        promptId: String?,
    ): List<RecordEntry> =
        when (content) {
            // Text in angle brackets is something the agent wrote into the prompt slot (a command's output, a
            // background task's notice), not a prompt you typed.
            is JsonPrimitive -> {
                content.takeIf { it.isString && !it.content.startsWith("<") }?.let {
                    listOf(RecordEntry.UserPrompt(it.content, promptId))
                } ?: emptyList()
            }

            is JsonArray -> {
                content.mapNotNull { element ->
                    val block = element as? JsonObject
                    val id = block?.str("tool_use_id")
                    if (block?.str("type") == "tool_result" && id != null) {
                        RecordEntry.ToolResult(id, resultText(block["content"]), block.bool("is_error") == true)
                    } else {
                        null
                    }
                }
            }

            else -> {
                emptyList()
            }
        }

    private fun resultText(element: kotlinx.serialization.json.JsonElement?): String? =
        when (element) {
            is JsonPrimitive -> element.takeIf { it.isString }?.content
            is JsonArray -> element.mapNotNull { (it as? JsonObject)?.str("text") }.joinToString("\n")
            else -> null
        }?.take(TOOL_OUTPUT_CHARS)

    private fun readBytes(
        path: Path,
        offset: Long,
        maxBytes: Int,
    ): ByteArray? =
        try {
            FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.allocate(minOf(maxBytes.toLong(), maxOf(0, channel.size() - offset)).toInt())
                while (buffer.hasRemaining() && channel.read(buffer, offset + buffer.position()) > 0) Unit
                buffer.array().copyOf(buffer.position())
            }
        } catch (_: IOException) {
            null
        }
}
