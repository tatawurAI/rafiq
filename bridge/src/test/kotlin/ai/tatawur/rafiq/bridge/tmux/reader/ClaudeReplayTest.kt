package ai.tatawur.rafiq.bridge.tmux.reader

import ai.tatawur.rafiq.bridge.hook.HookEnvelope
import ai.tatawur.rafiq.bridge.hook.HookReply
import ai.tatawur.rafiq.protocol.ApprovalOutcome
import ai.tatawur.rafiq.protocol.ContentBlock
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.MessageRole
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The canary: a session recorded from Claude Code 2.1.330 (scrubbed to placeholders), replayed through the reader
 * with its recorded timing. It has a long streamed reply, text between tool steps, hooks that arrived out of
 * order, a subagent, a question, approvals answered at the desk and a denied step. If a new Claude Code changes
 * the hook payloads or the record's shape, this is the test that breaks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClaudeReplayTest {
    @TempDir
    lateinit var dir: Path

    private fun resource(name: String): List<String> =
        checkNotNull(javaClass.getResourceAsStream("/claude-reader/$name")) { "missing fixture $name" }
            .bufferedReader()
            .readLines()
            .filter(String::isNotBlank)

    @Test
    fun the_recorded_session_replays_to_the_records_replies_and_steps_in_the_records_order() =
        runTest {
            val record = dir.resolve("record.jsonl")
            Files.write(record, resource("record-sample.jsonl"))
            val log = FakeReaderLog()
            val reader = reader(log)
            val permissions = mutableListOf<Deferred<HookReply?>>()
            var previousMs = 0L
            for (line in resource("hooks-sample.jsonl")) {
                val recorded = json(line)
                val atMs = (recorded["at_ms"] as JsonPrimitive).long
                // Hooks run side by side, so the recording is in arrival order and its clock can step back a little.
                advanceTimeBy(maxOf(0, atMs - previousMs))
                runCurrent()
                previousMs = maxOf(previousMs, atMs)
                val recordedEvent = recorded["event"] as JsonObject
                val event = JsonObject(recordedEvent + ("transcript_path" to JsonPrimitive(record.toString())))
                val envelope = HookEnvelope("claude-code", PANE, PID, event, testScheduler.currentTime)
                if (event.str("hook_event_name") == "PermissionRequest") {
                    permissions += async { reader.onPermission(envelope) }
                } else {
                    reader.onEvent(envelope)
                }
            }
            advanceTimeBy(5_000)
            runCurrent()

            assertEquals(expectedOrder(record), shownOrder(log, recordToolIds(record)))
            // No phone answered in the replay, so each approval was withdrawn when the desk's answer showed, and its
            // hook let go. The sixth waited 600.6 s in the recording, past the phone deadline, so it went back to the
            // desk first.
            assertEquals(9, permissions.size)
            assertTrue(permissions.all { it.isCompleted && it.getCompleted() == null })
            val outcomes = log.all<EventPayload.ApprovalResolved>().map { it.outcome }
            val expected =
                List(permissions.size) { if (it == 5) ApprovalOutcome.ASK_AT_DESK else ApprovalOutcome.WITHDRAWN }
            assertEquals(expected, outcomes)
            assertTrue(log.all<EventPayload.ChatIncomplete>().isEmpty(), "every reply arrived whole")
            assertTrue(
                log.all<EventPayload.Message>().filter { it.role == MessageRole.AGENT }.all {
                    it.content.single() is ContentBlock.Markdown
                },
                "agent text is Markdown",
            )
        }

    /** The record's text blocks and tool calls, in the order Claude wrote them. */
    private fun expectedOrder(record: Path): List<String> =
        SessionRecord.read(record, 0)!!.entries.mapNotNull {
            when (it) {
                is RecordEntry.AssistantText -> "text: " + it.text.take(40)
                is RecordEntry.ToolUse -> "step: " + it.id
                else -> null
            }
        }

    private fun recordToolIds(record: Path): Set<String> =
        SessionRecord
            .read(record, 0)!!
            .entries
            .filterIsInstance<RecordEntry.ToolUse>()
            .map { it.id }
            .toSet()

    /**
     * What Chat shows, in log order: each agent message where it first appeared, with its final text, and each
     * step of the main conversation where its card first appeared. A subagent's own steps and its summary are
     * not in the main record.
     */
    private fun shownOrder(
        log: FakeReaderLog,
        steps: Set<String>,
    ): List<String> {
        val finalText =
            log.all<EventPayload.Message>().filter { !it.isStreaming }.associate {
                it.id to (it.content.single() as? ContentBlock.Markdown)?.markdown
            }
        val seen = mutableSetOf<String>()
        return log.events.mapNotNull { event ->
            when {
                event is EventPayload.Message && event.role == MessageRole.AGENT && !event.id.startsWith(SUBAGENT) -> {
                    event.id.takeIf(seen::add)?.let { "text: " + finalText[it].orEmpty().take(40) }
                }

                event is EventPayload.ToolCall && event.id in steps -> {
                    event.id.takeIf(seen::add)?.let { "step: $it" }
                }

                else -> {
                    null
                }
            }
        }
    }

    private companion object {
        const val SUBAGENT = "claude:sub:"
    }
}
