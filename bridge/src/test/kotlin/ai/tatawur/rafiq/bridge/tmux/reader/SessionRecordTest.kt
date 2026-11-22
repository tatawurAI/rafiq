package ai.tatawur.rafiq.bridge.tmux.reader

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Reading Claude Code's session record from a byte offset, tolerantly. */
class SessionRecordTest {
    @TempDir
    lateinit var dir: Path

    private val record by lazy { dir.resolve("record.jsonl") }

    private fun append(text: String) {
        Files.writeString(record, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    @Test
    fun a_read_takes_whole_lines_only_and_the_next_read_starts_where_it_stopped() {
        append(assistantText("First."))
        val half = assistantText("Second.")
        append(half.take(10))

        val first = SessionRecord.read(record, 0)!!
        assertEquals(listOf("First."), first.entries.map { (it as RecordEntry.AssistantText).text })

        append(half.drop(10))
        val second = SessionRecord.read(record, first.nextOffset)!!
        assertEquals(listOf("Second."), second.entries.map { (it as RecordEntry.AssistantText).text })
        assertEquals(Files.size(record), second.nextOffset)
    }

    @Test
    fun unknown_line_types_bad_lines_and_injected_prompts_are_skipped() {
        append("""{"type":"api-request-blob","blob":"..."}""" + "\n")
        append("not json at all\n")
        append(
            """{"type":"assistant","isSidechain":true,"message":{"content":[{"type":"text","text":"sub"}]}}""" + "\n",
        )
        val kept = """{"type":"assistant","message":{"content":[{"type":"thinking"},{"type":"text","text":"Kept"}]}}"""
        append(kept + "\n")
        append(userPrompt("<local-command-stdout>ok</local-command-stdout>"))
        append("""{"type":"assistant","message":{"content":"a string where a list belongs"}}""" + "\n")
        append(userPrompt("A real prompt."))

        val entries = SessionRecord.read(record, 0)!!.entries

        assertEquals(listOf(RecordEntry.AssistantText("Kept"), RecordEntry.UserPrompt("A real prompt.")), entries)
    }

    @Test
    fun a_first_attach_starts_near_the_end_on_a_line_boundary() {
        val line = assistantText("x".repeat(1_000))
        repeat(400) { append(line) }

        val offset = SessionRecord.tailOffset(record)!!

        assertTrue(Files.size(record) - offset <= SessionRecord.FIRST_ATTACH_TAIL_BYTES)
        assertEquals(0, offset % line.toByteArray().size, "the offset is at the start of a line")
        val entries = SessionRecord.read(record, offset)!!.entries
        assertTrue(entries.isNotEmpty() && entries.all { (it as RecordEntry.AssistantText).text == "x".repeat(1_000) })
    }
}
