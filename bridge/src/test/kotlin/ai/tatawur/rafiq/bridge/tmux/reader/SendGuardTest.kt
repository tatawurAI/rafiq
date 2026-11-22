package ai.tatawur.rafiq.bridge.tmux.reader

import ai.tatawur.rafiq.bridge.sessions.SessionException
import ai.tatawur.rafiq.protocol.DeliveryState
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.MessageRole
import ai.tatawur.rafiq.protocol.api.SendRefusal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The send guard's decisions, against a pane and an agent status the test controls (issue #50). */
@OptIn(ExperimentalCoroutinesApi::class)
class SendGuardTest {
    private val log = FakeReaderLog()
    private val io = FakePaneIo()
    private val statuses = FakeStatuses()

    private fun TestScope.settle(ms: Long = 10_000) {
        advanceTimeBy(ms)
        runCurrent()
    }

    /** A reader that has seen the pane's Claude start, so it knows where prompts go. */
    private suspend fun TestScope.attached(hooksInstalled: suspend (String) -> Boolean = { true }): ClaudeReader {
        val reader = reader(log, io = io, statuses = statuses, hooksInstalled = hooksInstalled)
        reader.onEvent(hook(event("SessionStart", "source" to "startup")))
        settle(10)
        return reader
    }

    private suspend fun ClaudeReader.refusal(text: String = "hello"): String =
        assertFailsWith<SessionException.Conflict> { prompt(SESSION, text, "c-1") }.code

    private fun deliveries() = log.all<EventPayload.PromptDelivery>().map { it.state to it.code }

    @Test
    fun a_prompt_while_claude_is_busy_is_held_and_typed_once_the_turn_ends() =
        runTest {
            val reader = attached()
            statuses.status = AgentStatus.BUSY

            reader.prompt(SESSION, "and then add tests", "c-1")
            assertEquals(listOf(DeliveryState.QUEUED to null), deliveries())
            assertTrue(io.pasted.isEmpty(), "nothing is typed while a turn runs")
            assertTrue(log.userMessages().isEmpty(), "the app shows its own echo until the prompt lands")

            statuses.status = AgentStatus.IDLE
            reader.onEvent(hook(event("Stop", "last_assistant_message" to "Done.")))
            settle(500)
            assertEquals(listOf("and then add tests"), io.pasted)
            reader.onEvent(hook(event("UserPromptSubmit", "prompt" to "and then add tests", "prompt_id" to "p-7")))
            settle()

            assertEquals(listOf(DeliveryState.QUEUED to null, DeliveryState.DELIVERED to null), deliveries())
            val sid = "00000000-0000-4000-8000-000000000001"
            assertEquals(listOf("user:$sid:p-7" to "and then add tests"), log.userMessages())
            val message = log.events.indexOfFirst { it is EventPayload.Message && it.role == MessageRole.USER }
            val delivered =
                log.events.indexOfFirst {
                    (it as? EventPayload.PromptDelivery)?.state ==
                        DeliveryState.DELIVERED
                }
            assertTrue(message < delivered, "your message is in the log before it is marked delivered")
        }

    @Test
    fun nothing_is_typed_while_a_dialog_is_open_at_the_desk() =
        runTest {
            val reader = attached()
            statuses.status = AgentStatus.WAITING

            assertEquals(SendRefusal.AGENT_WAITING, reader.refusal())
            assertTrue(io.pasted.isEmpty() && io.enters.isEmpty())
        }

    @Test
    fun nothing_is_typed_while_a_phone_approval_for_the_pane_is_open() =
        runTest {
            val reader = attached()
            async { reader.onPermission(hook(permission("Bash", bash("ls")))) }
            settle(10)

            assertEquals(SendRefusal.AGENT_WAITING, reader.refusal())
            assertTrue(io.pasted.isEmpty())
        }

    @Test
    fun half_typed_text_at_the_desk_is_refused() =
        runTest {
            val reader = attached()
            io.screen = FakePaneIo.box("desk half-written line")

            assertEquals(SendRefusal.INPUT_NOT_EMPTY, reader.refusal())
            assertTrue(io.pasted.isEmpty())
        }

    @Test
    fun a_screen_without_the_input_box_is_refused_as_unknown() =
        runTest {
            val reader = attached()
            io.screen = "Do you want to make this edit?\n❯ 1. Yes\n  2. No\n"

            assertEquals(SendRefusal.STATE_UNKNOWN, reader.refusal())
        }

    @Test
    fun claude_exiting_between_the_check_and_enter_leaves_enter_unpressed() =
        runTest {
            val reader = attached()
            io.onPaste = { io.agentRuns = false }

            assertEquals(SendRefusal.AGENT_NOT_RUNNING, reader.refusal())
            assertEquals(listOf("hello"), io.pasted)
            assertTrue(io.enters.isEmpty(), "Enter would have run the text in a shell")
        }

    @Test
    fun a_prompt_that_claude_never_reports_is_marked_not_delivered() =
        runTest {
            val reader = attached()

            reader.prompt(SESSION, "hello", "c-1")
            settle()

            assertEquals(listOf(DeliveryState.NOT_DELIVERED to SendRefusal.NOT_CONFIRMED), deliveries())
        }

    @Test
    fun a_shell_line_is_typed_as_sent_and_confirmed_by_the_box_clearing() =
        runTest {
            val reader = attached()

            reader.prompt(SESSION, "!ls", "c-1")
            settle()

            assertEquals(listOf("!ls"), io.pasted)
            assertEquals(listOf(DeliveryState.DELIVERED to null), deliveries())
            assertEquals(
                listOf("user:c-1" to "!ls"),
                log.userMessages(),
                "no hook reports a shell line, so the bridge does",
            )
        }

    @Test
    fun a_prompt_marked_not_delivered_that_lands_late_is_delivered_then_and_shown_once() =
        runTest {
            val reader = attached()
            reader.prompt(SESSION, "hello", "c-1")
            settle()
            assertEquals(listOf(DeliveryState.NOT_DELIVERED to SendRefusal.NOT_CONFIRMED), deliveries())

            reader.onEvent(hook(event("UserPromptSubmit", "prompt" to "hello", "prompt_id" to "p-1")))
            settle()

            assertEquals(DeliveryState.DELIVERED to null, deliveries().last())
            assertEquals(1, log.userMessages().size)
        }

    @Test
    fun control_characters_are_stripped_and_tabs_and_newlines_kept() =
        runTest {
            val reader = attached()

            reader.prompt(SESSION, "run\u001b[31m this\r\nthen\tthat\u0003\u007f", "c-1")

            assertEquals(listOf("run[31m this\nthen\tthat"), io.pasted)
        }

    @Test
    fun a_project_without_hooks_is_refused() =
        runTest {
            val reader = attached(hooksInstalled = { false })

            assertEquals(SendRefusal.HOOKS_NOT_INSTALLED, reader.refusal())
        }

    @Test
    fun the_input_box_is_read_from_the_screen() {
        assertEquals(InputBox.EMPTY, readInputBox(FakePaneIo.EMPTY_BOX))
        assertEquals(InputBox.EMPTY, readInputBox(FakePaneIo.box("Try \"fix lint errors\"")))
        assertEquals(InputBox.DRAFT, readInputBox(FakePaneIo.box("desk half-written line")))
        val rule = "─".repeat(30)
        assertEquals(InputBox.DRAFT, readInputBox("$rule\n❯ first line\n  second line\n$rule\n"))
        assertEquals(InputBox.ABSENT, readInputBox("Do you want to proceed?\n❯ 1. Yes\n  2. No\n"))
    }
}
