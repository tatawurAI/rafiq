package ai.tatawur.rafiq.sessions

import ai.tatawur.rafiq.client.ApprovalCard
import ai.tatawur.rafiq.client.TimelineItem
import ai.tatawur.rafiq.protocol.ToolCallKind
import ai.tatawur.rafiq.protocol.ToolCallStatus
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AnswersBelowTest {
    private fun card(approvalId: String) =
        TimelineItem.Tool(
            "c-$approvalId",
            1,
            ToolCallKind.FETCH,
            "https://api.example.com",
            null,
            ToolCallStatus.PENDING,
            ApprovalCard(approvalId, "s-1", "t-main", "c-$approvalId", "Fetch?", emptyList(), null),
        )

    // Half folded, the pinned approval's buttons live in the bottom pane and must not repeat above.
    @Test
    fun only_the_pinned_card_leaves_its_buttons_below_and_only_while_the_pane_shows_them() {
        val pinned = card("a-new")
        val older = card("a-old")

        assertEquals(
            listOf(true, false, false),
            listOf(
                answersBelow(pinnedAnsweredBelow = true, pinned, "a-new"),
                answersBelow(pinnedAnsweredBelow = true, older, "a-new"),
                answersBelow(pinnedAnsweredBelow = false, pinned, "a-new"),
            ),
        )
    }
}
