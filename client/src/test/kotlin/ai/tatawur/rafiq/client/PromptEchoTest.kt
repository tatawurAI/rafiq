package ai.tatawur.rafiq.client

import ai.tatawur.rafiq.protocol.DeliveryState
import ai.tatawur.rafiq.protocol.Event
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.api.SendRefusal
import kotlin.test.Test
import kotlin.test.assertEquals

class PromptEchoTest {
    private val sent = listOf(SentPrompt("c-1", "Run the tests"), SentPrompt("c-2", "/compact"))

    private fun delivery(
        clientId: String,
        state: DeliveryState,
        code: String? = null,
    ) = EventPayload.PromptDelivery(
        clientId,
        "main",
        state,
        code,
        code?.let {
            "There is half-typed text in the terminal."
        },
    )

    @Test
    fun a_prompt_shows_as_sent_then_queued_and_goes_once_the_agent_has_it() {
        assertEquals(listOf(EchoState.SENT, EchoState.SENT), promptEchoes(sent, emptyMap()).map { it.state })

        val queued = promptEchoes(sent, mapOf("c-1" to delivery("c-1", DeliveryState.QUEUED)))
        assertEquals(listOf(EchoState.QUEUED, EchoState.SENT), queued.map { it.state })

        val delivered = promptEchoes(sent, mapOf("c-1" to delivery("c-1", DeliveryState.DELIVERED)))
        assertEquals(listOf("c-2"), delivered.map { it.clientId })
    }

    @Test
    fun a_prompt_that_was_not_delivered_keeps_its_text_and_the_reason() {
        val deliveries = mapOf("c-2" to delivery("c-2", DeliveryState.NOT_DELIVERED, SendRefusal.INPUT_NOT_EMPTY))

        val echo = promptEchoes(sent, deliveries).last()

        assertEquals(
            PromptEcho(
                "c-2",
                "/compact",
                EchoState.NOT_DELIVERED,
                SendRefusal.INPUT_NOT_EMPTY,
                "There is half-typed text in the terminal.",
            ),
            echo,
        )
    }

    @Test
    fun a_prompt_not_confirmed_that_lands_late_clears_its_echo() {
        // The reducer keeps the latest state per client id, so DELIVERED replaces NOT_DELIVERED.
        val events =
            listOf(
                delivery("c-1", DeliveryState.NOT_DELIVERED, SendRefusal.NOT_CONFIRMED),
                delivery("c-1", DeliveryState.DELIVERED),
            ).mapIndexed { index, payload -> Event("s-1", index + 1L, 0, payload) }

        val state = SessionReducer.fold("s-1", events)

        assertEquals(listOf("c-2"), promptEchoes(sent, state.deliveries).map { it.clientId })
    }

    @Test
    fun a_bang_line_written_as_its_own_message_replaces_the_echo_before_delivered_arrives() {
        // Your server writes `user:<client id>` for a `!` line, one event before DELIVERED.
        val echoes = promptEchoes(sent, emptyMap(), messageKeys = setOf("user:c-1"))

        assertEquals(listOf("c-2"), echoes.map { it.clientId })
    }
}
