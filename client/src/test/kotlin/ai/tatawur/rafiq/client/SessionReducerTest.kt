package ai.tatawur.rafiq.client

import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.AgentThread
import ai.tatawur.rafiq.protocol.ApprovalOption
import ai.tatawur.rafiq.protocol.ApprovalOptionKind
import ai.tatawur.rafiq.protocol.ApprovalOutcome
import ai.tatawur.rafiq.protocol.ContentBlock
import ai.tatawur.rafiq.protocol.Event
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.MessageRole
import ai.tatawur.rafiq.protocol.Session
import ai.tatawur.rafiq.protocol.ThreadKind
import ai.tatawur.rafiq.protocol.ToolCallKind
import ai.tatawur.rafiq.protocol.ToolCallStatus
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SessionReducerTest {
    private val session = Session("ses_1", "fake", "/work/weather-app", "weather-app", AgentState.DONE, 1, 1)
    private val opening =
        listOf(
            EventPayload.SessionUpdated(session),
            EventPayload.ThreadUpdated(thread("main", ThreadKind.MAIN)),
        )

    @Test
    fun a_repeated_message_id_replaces_the_message_in_place() {
        val state =
            fold(
                opening +
                    agentMessage("plan:main", "- [ ] Read") +
                    agentMessage("msg_1", "Reading.") +
                    agentMessage("plan:main", "- [x] Read"),
            )

        val timeline = state.timelines.getValue("main")
        assertEquals(listOf("plan:main", "msg_1"), timeline.map { it.key })
        assertEquals(listOf(ContentBlock.Markdown("- [x] Read")), (timeline[0] as TimelineItem.Message).blocks)
    }

    @Test
    fun deltas_stream_into_the_message_and_the_final_snapshot_replaces_them() {
        val streaming =
            opening +
                agentMessage("msg_1", "Hel", streaming = true) +
                EventPayload.MessageDelta("msg_1", "main", "lo, ") +
                EventPayload.MessageDelta("msg_1", "main", "world")
        val midway = fold(streaming).timelines.getValue("main").single() as TimelineItem.Message
        assertEquals(listOf(ContentBlock.Markdown("Hello, world")), midway.blocks)
        assertEquals(true, midway.isStreaming)

        val done = fold(streaming + agentMessage("msg_1", "Hello, world.")).timelines.getValue("main").single()
        assertEquals(
            TimelineItem.Message("msg_1", 3, MessageRole.AGENT, listOf(ContentBlock.Markdown("Hello, world.")), false),
            done,
        )
    }

    @Test
    fun a_subagent_thread_appears_as_a_tab_after_the_main_thread() {
        // The helper's thread arrives first here, but the main thread always leads the tabs.
        val state =
            fold(
                listOf(
                    EventPayload.SessionUpdated(session),
                    EventPayload.ThreadUpdated(thread("t_helper", ThreadKind.SUBAGENT, parent = "main")),
                    EventPayload.ThreadUpdated(thread("main", ThreadKind.MAIN)),
                    EventPayload.StatusChanged(threadId = "t_helper", state = AgentState.DONE),
                ),
            )

        assertEquals(listOf("main", "t_helper"), state.threads.map { it.id })
        assertEquals("main", state.threads[1].parentThreadId)
        assertEquals(AgentState.DONE, state.threads[1].status)
    }

    @Test
    fun an_approval_attaches_to_its_tool_card_until_it_is_resolved() {
        val requested =
            opening +
                EventPayload.ToolCall(
                    "call_1",
                    "main",
                    ToolCallKind.EDIT,
                    "src/app.kt",
                    status = ToolCallStatus.PENDING,
                ) +
                EventPayload.ApprovalRequest(
                    "appr_1",
                    "call_1",
                    "Edit src/app.kt",
                    listOf(
                        ApprovalOption("no", ApprovalOptionKind.DENY, "Deny"),
                        ApprovalOption("yes", ApprovalOptionKind.ALLOW_ONCE, "Allow"),
                    ),
                )
        val open = fold(requested)
        val card = open.openApprovals.single()
        assertEquals("main", card.threadId)
        assertEquals(listOf("yes", "no"), card.options.map { it.id })
        assertEquals(card, (open.timelines.getValue("main").single() as TimelineItem.Tool).approval)
        assertEquals(1, open.mainThread?.openApprovals)

        val resolved = fold(requested + EventPayload.ApprovalResolved("appr_1", ApprovalOutcome.SELECTED, "yes"))
        assertEquals(emptyList(), resolved.openApprovals)
        assertNull((resolved.timelines.getValue("main").single() as TimelineItem.Tool).approval)
        assertEquals(0, resolved.mainThread?.openApprovals)
    }

    @Test
    fun an_unknown_payload_becomes_a_notice_on_the_main_thread() {
        val raw = buildJsonObject { put("type", JsonPrimitive("terminal_output")) }

        val state = fold(opening + EventPayload.Unknown(raw))

        val notice = state.timelines.getValue("main").single()
        assertEquals(TimelineItem.Notice("event:3", 3, NoticeKind.UNKNOWN_EVENT, "terminal_output", null), notice)
    }

    @Test
    fun replaying_events_already_applied_changes_nothing() {
        val events =
            events(
                opening +
                    agentMessage(
                        "msg_1",
                        "Hi",
                        streaming = true,
                    ) + EventPayload.MessageDelta("msg_1", "main", "!"),
            )
        val once = SessionReducer.fold("ses_1", events)

        val twice = events.fold(once) { state, event -> SessionReducer.reduce(state, event).state }

        assertEquals(once, twice)
    }

    @Test
    fun an_event_past_the_next_seq_is_reported_as_a_gap_and_not_applied() {
        val state = fold(opening)

        val reduction = SessionReducer.reduce(state, Event("ses_1", 4, 0, agentMessage("msg_1", "Hi")))

        assertIs<Reduction.Gap>(reduction)
        assertEquals(state, reduction.state)
    }

    private fun fold(payloads: List<EventPayload>) = SessionReducer.fold("ses_1", events(payloads))

    private fun events(payloads: List<EventPayload>) =
        payloads.mapIndexed { index, payload -> Event("ses_1", index + 1L, 0, payload) }

    private fun thread(
        id: String,
        kind: ThreadKind,
        parent: String? = null,
    ) = AgentThread(id, "ses_1", parent, kind, id, AgentState.WORKING)

    private fun agentMessage(
        id: String,
        markdown: String,
        streaming: Boolean = false,
    ) = EventPayload.Message(id, "main", MessageRole.AGENT, listOf(ContentBlock.Markdown(markdown)), streaming)
}
