package ai.tatawur.rafiq.client

import ai.tatawur.rafiq.protocol.DeliveryState
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
}
