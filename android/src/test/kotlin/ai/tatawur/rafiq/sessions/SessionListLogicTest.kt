package ai.tatawur.rafiq.sessions

import ai.tatawur.rafiq.client.ApprovalCard
import ai.tatawur.rafiq.client.SessionListItem
import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.ApprovalOption
import ai.tatawur.rafiq.protocol.ApprovalOptionKind
import ai.tatawur.rafiq.protocol.Session
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SessionListLogicTest {
    private fun item(
        id: String,
        status: AgentState,
        approvals: List<ApprovalCard> = emptyList(),
    ) = SessionListItem(Session(id, "claude-code", "/srv/$id", id, status, 0, 0), null, approvals)

    private fun approval(vararg options: ApprovalOption) =
        ApprovalCard("a-1", "s-1", "t-1", "c-1", "Run the tests?", options.toList(), expiresAtMs = null)

    // Each row sits under one status chip, so the chip counts never show a session twice.
    @Test
    fun a_working_session_with_an_open_approval_counts_as_needs_you_and_not_as_working() {
        val items =
            listOf(
                item("weather-app", AgentState.WORKING, listOf(approval())),
                item("api-server", AgentState.WORKING),
                item("docs-site", AgentState.DONE),
                item("broken", AgentState.ERROR),
            )

        val counts = items.countsByFilter()

        assertEquals(4, counts[SessionFilter.ALL])
        assertEquals(1, counts[SessionFilter.NEEDS_YOU])
        assertEquals(1, counts[SessionFilter.WORKING])
        assertEquals(2, counts[SessionFilter.DONE], "a session that ended in an error is finished too")
        assertEquals(listOf("weather-app"), items.filter(SessionFilter.NEEDS_YOU::matches).map { it.session.id })
    }

    @Test
    fun a_row_never_offers_allow_always() {
        val choices =
            InlineChoices.of(
                approval(
                    ApprovalOption("always", ApprovalOptionKind.ALLOW_ALWAYS, "Always allow"),
                    ApprovalOption("deny", ApprovalOptionKind.DENY, "Deny"),
                ),
            )

        assertNull(choices.approve)
        assertEquals("deny", choices.deny?.id)
    }

    @Test
    fun a_row_with_only_unknown_options_sends_you_to_the_session() {
        val choices = InlineChoices.of(approval(ApprovalOption("x", ApprovalOptionKind.UNKNOWN, "Skip this step")))

        assertFalse(choices.canAnswerInline)
    }
}
