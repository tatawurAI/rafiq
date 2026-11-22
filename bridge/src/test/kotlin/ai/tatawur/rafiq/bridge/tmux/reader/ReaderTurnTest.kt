package ai.tatawur.rafiq.bridge.tmux.reader

import ai.tatawur.rafiq.bridge.tmux.PaneInfo
import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.MessageRole
import ai.tatawur.rafiq.protocol.ThreadKind
import ai.tatawur.rafiq.protocol.TurnPhase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How hook events become a turn in Chat: order, the reply check, interrupts and the pane mapping. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderTurnTest {
    @TempDir
    lateinit var dir: Path

    private val log = FakeReaderLog()

    private fun TestScope.settle(ms: Long = 5_000) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun Path.append(text: String) {
        Files.writeString(this, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    @Test
    fun text_written_before_a_step_shows_before_its_card_even_when_its_hook_arrives_later() =
        runTest {
            val reader = reader(log)
            reader.onEvent(hook(event("UserPromptSubmit", "prompt" to "check the build")))
            reader.onEvent(
                hook(bashStep("PreToolUse", "toolu_1", "npm test")),
            )
            advanceTimeBy(5)
            reader.onEvent(
                hook(display("I will run the tests.")),
            )
            settle()

            val order =
                log.events.mapNotNull {
                    when {
                        it is EventPayload.Message && it.role == MessageRole.AGENT -> "text"
                        it is EventPayload.ToolCall -> "card"
                        else -> null
                    }
                }
            assertEquals(listOf("text", "card"), order)
        }

    @Test
    fun a_turn_that_ended_with_batches_missing_is_filled_from_the_record_after_a_first_attach() =
        runTest {
            val record = dir.resolve("record.jsonl")
            record.append(userPrompt("earlier question") + assistantText("Earlier reply."))
            val reader = reader(log)
            val path = "transcript_path" to record.toString()
            reader.onEvent(hook(event("UserPromptSubmit", "prompt" to "finish up", path)))
            settle(100)
            reader.onEvent(hook(event("Stop", "last_assistant_message" to "All done.", path)))
            // The record catches up a moment after the Stop hook.
            settle(300)
            record.append(userPrompt("finish up") + assistantText("All done."))
            settle()

            assertEquals(listOf("Earlier reply.", "All done."), log.agentTexts())
            assertTrue(log.all<EventPayload.ChatIncomplete>().isEmpty(), "the record filled the gap")
        }

    @Test
    fun a_reply_found_neither_in_the_batches_nor_the_record_is_shown_with_a_chat_incomplete_note() =
        runTest {
            val record = dir.resolve("record.jsonl").also { Files.createFile(it) }
            val reader = reader(log)
            val path = "transcript_path" to record.toString()
            reader.onEvent(hook(event("UserPromptSubmit", "prompt" to "finish up", path)))
            reader.onEvent(
                hook(display("Working on it.", path)),
            )
            reader.onEvent(hook(event("Stop", "last_assistant_message" to "Final words.", path)))
            settle()

            assertEquals(listOf("Working on it.", "Final words."), log.agentTexts())
            assertEquals(
                listOf(EventPayload.ChatIncomplete("main", "reply_mismatch")),
                log.all<EventPayload.ChatIncomplete>(),
            )
        }

    @Test
    fun an_esc_at_the_desk_ends_the_turn_although_no_stop_arrives() =
        runTest {
            val statuses = FakeStatuses(AgentStatus.BUSY)
            val reader = reader(log, statuses = statuses)
            reader.onEvent(hook(event("UserPromptSubmit", "prompt" to "count to a million")))
            reader.onEvent(
                hook(bashStep("PreToolUse", "toolu_1", "seq 1000000")),
            )
            settle(1_000)
            assertEquals(TurnPhase.RUNNING_STEP, log.all<EventPayload.TurnProgress>().last().phase)

            statuses.status = AgentStatus.IDLE
            settle(10_000)

            assertEquals(
                EventPayload.StatusChanged("main", AgentState.WAITING_ON_YOU, "Stopped."),
                log.all<EventPayload.StatusChanged>().last(),
            )
            assertEquals(TurnPhase.IDLE, log.all<EventPayload.TurnProgress>().last().phase)
        }

    @Test
    fun a_prompt_typed_at_the_desk_is_shown_and_a_background_task_notice_is_not() =
        runTest {
            val reader = reader(log)
            reader.onEvent(hook(event("UserPromptSubmit", "prompt" to "what changed today?")))
            val notice = "<task-notification>\n<status>completed</status>"
            reader.onEvent(hook(event("UserPromptSubmit", "prompt" to notice)))
            settle()

            val prompts = log.all<EventPayload.Message>().filter { it.role == MessageRole.USER }
            assertEquals(1, prompts.size, prompts.toString())
        }

    @Test
    fun the_permission_mode_from_the_hooks_is_shown_on_the_session() =
        runTest {
            val reader = reader(log)
            reader.onEvent(hook(event("UserPromptSubmit", "prompt" to "plan it", "permission_mode" to "plan")))
            settle()

            assertEquals(
                "plan",
                log
                    .all<EventPayload.SessionUpdated>()
                    .single()
                    .session.permissionMode,
            )
            assertEquals("plan", reader.permissionMode(SESSION))
        }

    @Test
    fun a_restart_backfills_exactly_what_the_record_gained_while_the_bridge_was_down() =
        runTest {
            val record = dir.resolve("record.jsonl")
            record.append(userPrompt("old question") + assistantText("Old reply."))
            val offsets = dir.resolve("offsets.json")
            val path = "transcript_path" to record.toString()
            val first = reader(log, offsets = ReaderOffsets(offsets))
            first.onEvent(hook(event("SessionStart", "source" to "startup", path)))
            settle()
            first.close()
            assertEquals(emptyList(), log.agentTexts(), "a session that just started has nothing to fill")

            record.append(userPrompt("asked while the bridge was down") + assistantText("Answered while it was down."))
            val second = reader(log, offsets = ReaderOffsets(offsets))
            second.onEvent(hook(event("Notification", "message" to "Claude is waiting for your input", path)))
            settle()

            assertEquals(listOf("Answered while it was down."), log.agentTexts())
            val prompts = log.all<EventPayload.Message>().filter { it.role == MessageRole.USER }
            assertEquals(1, prompts.size)
        }

    @Test
    fun a_prompt_shown_live_keeps_its_id_when_a_restart_backfills_it_from_the_record() =
        runTest {
            val record = dir.resolve("record.jsonl").also { Files.createFile(it) }
            val offsets = dir.resolve("offsets.json")
            val path = "transcript_path" to record.toString()
            val first = reader(log, offsets = ReaderOffsets(offsets))
            first.onEvent(hook(event("SessionStart", "source" to "startup", path)))
            first.onEvent(hook(event("UserPromptSubmit", "prompt" to "what changed?", "prompt_id" to "p-1", path)))
            settle()
            first.close()
            // The record caught up after the bridge stopped, so the saved offset is before the prompt.
            record.append(userPrompt("what changed?", promptId = "p-1") + assistantText("Nothing yet."))

            val second = reader(log, offsets = ReaderOffsets(offsets))
            second.onEvent(hook(event("Notification", "message" to "Claude is waiting for your input", path)))
            settle()

            val ids = log.userMessages().map { it.first }.toSet()
            assertEquals(setOf("user:00000000-0000-4000-8000-000000000001:p-1"), ids)
        }

    @Test
    fun clear_starts_a_new_record_without_showing_what_it_already_holds() =
        runTest {
            val old = dir.resolve("old.jsonl").also { it.append(assistantText("From before the clear.")) }
            val fresh = dir.resolve("new.jsonl").also { it.append(assistantText("Written by the clear itself.")) }
            val reader = reader(log)
            reader.onEvent(hook(event("SessionStart", "source" to "startup", "transcript_path" to old.toString())))
            reader.onEvent(
                hook(
                    event(
                        "SessionStart",
                        "source" to "clear",
                        "transcript_path" to fresh.toString(),
                        sessionId = "00000000-0000-4000-8000-000000000002",
                    ),
                ),
            )
            settle()

            assertEquals(emptyList(), log.agentTexts())
        }

    @Test
    fun a_scan_follows_every_claude_pane_in_the_session_on_its_own_thread() =
        runTest {
            val io = FakePaneIo()
            val reader = reader(log, io = io)
            val panes =
                listOf(
                    PaneInfo("weather-app", PANE, 100, "/work/weather-app", true),
                    PaneInfo("weather-app", "%5", 200, "/work/weather-app", false),
                )
            val processes = mapOf(4242 to (100 to "claude"), 4343 to (200 to "claude"))
            reader.onScan(panes, processes)
            settle()
            reader.onEvent(
                hook(display("Second pane."), pane = "%5", pid = 4343),
            )
            settle()

            val worker = log.all<EventPayload.ThreadUpdated>().single().thread
            assertEquals(ThreadKind.WORKER, worker.kind)
            assertEquals(worker.id, log.all<EventPayload.Message>().single().threadId)
            // Prompts still go to the session's main pane.
            reader.prompt(SESSION, "hello", "c-1")
            assertEquals(listOf("hello"), io.pasted)
            assertEquals(listOf(PANE), io.enters)
        }

    @Test
    fun a_claude_that_dies_mid_turn_without_a_session_end_ends_the_turn() =
        runTest {
            val statuses = FakeStatuses(AgentStatus.BUSY)
            val reader = reader(log, statuses = statuses)
            reader.onEvent(hook(event("UserPromptSubmit", "prompt" to "go")))
            settle(1_000)
            statuses.present = false
            settle(10_000)

            assertEquals(
                EventPayload.StatusChanged("main", AgentState.DONE, "Claude Code stopped."),
                log.all<EventPayload.StatusChanged>().last(),
            )
        }
}
