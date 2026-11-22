package ai.tatawur.rafiq.bridge.tmux.reader

/**
 * Chat for tmux sessions, as the session manager sees it: prompts through the send guard, and answers to the
 * approvals the reader raised. Every refusal is a [ai.tatawur.rafiq.bridge.sessions.SessionException], so the
 * routes turn it into the usual error body.
 */
interface TmuxChat {
    /**
     * Types [text] into the session's Claude pane, or holds it until Claude is idle. Returns once it was typed
     * or queued. What happens next comes as [ai.tatawur.rafiq.protocol.EventPayload.PromptDelivery] events with
     * [clientId].
     *
     * @throws ai.tatawur.rafiq.bridge.sessions.SessionException.Conflict with a
     *   [ai.tatawur.rafiq.protocol.api.SendRefusal] code when the bridge will not type it.
     */
    suspend fun prompt(
        sessionId: String,
        text: String,
        clientId: String?,
    )

    /** True when [approvalId] is a pending approval of a tmux session. */
    fun ownsApproval(approvalId: String): Boolean

    /** Your answer to an approval for which [ownsApproval] is true. Returns the approval's session id. */
    suspend fun respondToApproval(
        approvalId: String,
        optionId: String,
    ): String

    /** The agent's permission mode in the session, as last reported, or null when not known. */
    fun permissionMode(sessionId: String): String?
}
