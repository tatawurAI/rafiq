package ai.tatawur.rafiq.client

import ai.tatawur.rafiq.protocol.DeliveryState
import ai.tatawur.rafiq.protocol.EventPayload

/**
 * A prompt you sent from this screen to a session in tmux, before the agent has it. The bridge types such
 * a prompt into the pane only when that is safe, so for a while it is neither refused nor delivered.
 *
 * @property clientId the id sent with the prompt, which the bridge's `prompt_delivery` events carry back.
 */
data class SentPrompt(
    val clientId: String,
    val text: String,
)

/**
 * A sent prompt as the timeline shows it, under the last item, until the agent has it. Once the agent has
 * it, your message arrives as an event like any other and the echo goes.
 *
 * @property code why it was not delivered, one of the bridge's send refusal codes, or null.
 * @property message the bridge's reason in plain words, or null.
 */
data class PromptEcho(
    val clientId: String,
    val text: String,
    val state: EchoState,
    val code: String? = null,
    val message: String? = null,
)

/** Where a [PromptEcho] is on its way. */
enum class EchoState {
    /** Your server took the prompt and has not said more yet. */
    SENT,

    /** The agent is busy, and your server types the prompt when it is ready. */
    QUEUED,

    /** Your server did not type it, or could not confirm that it landed. */
    NOT_DELIVERED,
}

/**
 * The echoes for [sent], oldest first, from the latest delivery state of each. A delivered prompt has no
 * echo. A delivery state this build does not know keeps the echo as sent, so nothing vanishes unseen.
 */
fun promptEchoes(
    sent: List<SentPrompt>,
    deliveries: Map<String, EventPayload.PromptDelivery>,
): List<PromptEcho> =
    sent.mapNotNull { prompt ->
        val delivery = deliveries[prompt.clientId]
        when (delivery?.state) {
            DeliveryState.DELIVERED -> {
                null
            }

            DeliveryState.QUEUED -> {
                PromptEcho(prompt.clientId, prompt.text, EchoState.QUEUED)
            }

            DeliveryState.NOT_DELIVERED -> {
                PromptEcho(prompt.clientId, prompt.text, EchoState.NOT_DELIVERED, delivery.code, delivery.message)
            }

            DeliveryState.UNKNOWN, null -> {
                PromptEcho(prompt.clientId, prompt.text, EchoState.SENT)
            }
        }
    }
