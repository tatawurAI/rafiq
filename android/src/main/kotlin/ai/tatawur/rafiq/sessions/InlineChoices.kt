package ai.tatawur.rafiq.sessions

import ai.tatawur.rafiq.client.ApprovalCard
import ai.tatawur.rafiq.protocol.ApprovalOption
import ai.tatawur.rafiq.protocol.ApprovalOptionKind

/**
 * The two buttons a session row has room for: approve once and deny.
 *
 * "Allow always" never sits on a row, because a single tap there would widen what the agent may
 * do from then on. It stays in the session view, where you see the full request.
 *
 * @property approve the option behind "Approve", or null when the agent offered no one-off allow.
 * @property deny the option behind "Deny", or null when the agent offered no deny.
 */
data class InlineChoices(
    val approve: ApprovalOption?,
    val deny: ApprovalOption?,
) {
    /** False when the row cannot answer the approval itself and has to open the session instead. */
    val canAnswerInline: Boolean
        get() = approve != null || deny != null

    companion object {
        /** Picks the row buttons for [card], taking the first option of each kind in the agent's order. */
        fun of(card: ApprovalCard): InlineChoices =
            InlineChoices(
                approve = card.options.firstOrNull { it.kind == ApprovalOptionKind.ALLOW_ONCE },
                deny = card.options.firstOrNull { it.kind == ApprovalOptionKind.DENY },
            )
    }
}
