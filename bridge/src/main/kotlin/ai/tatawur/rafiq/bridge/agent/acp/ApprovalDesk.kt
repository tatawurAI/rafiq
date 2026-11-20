package ai.tatawur.rafiq.bridge.agent.acp

import com.agentclientprotocol.model.PermissionOptionId
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap

/**
 * The agent's open permission requests, between the two places that handle each one.
 *
 * The session's consumer maps a request, in wire order, and files it here with [filed]. The SDK's
 * request handler waits in [answer] until the request is filed and then until you choose, and returns
 * the JSON-RPC result. Either side can get there first, so they meet on the request's JSON-RPC id.
 */
internal class ApprovalDesk {
    /** One request waiting on you. */
    class Pending(
        val approvalId: String,
        val acpSessionId: String,
        val threadId: String,
        val optionIds: Set<String>,
    ) {
        val choice = CompletableDeferred<RequestPermissionOutcome>()
    }

    private val byRequest = ConcurrentHashMap<String, CompletableDeferred<Pending>>()
    private val byApproval = ConcurrentHashMap<String, Pending>()

    /** Files a mapped request under its JSON-RPC id, see [requestKey]. */
    fun filed(
        requestKey: String,
        pending: Pending,
    ) {
        byApproval[pending.approvalId] = pending
        meeting(requestKey).complete(pending)
    }

    /** A request that could not be mapped. Its handler answers the agent with an error. */
    fun rejected(
        requestKey: String,
        reason: Exception,
    ) {
        meeting(requestKey).completeExceptionally(reason)
    }

    /**
     * Waits for the request with [requestKey] to be filed and answered, and returns the result to send.
     *
     * @param onSelected called when you chose an option rather than the request being cancelled.
     */
    suspend fun answer(
        requestKey: String,
        onSelected: (Pending) -> Unit,
    ): JsonElement {
        var filed: Pending? = null
        val outcome =
            try {
                val pending = meeting(requestKey).await().also { filed = it }
                pending.choice.await().also { if (it is RequestPermissionOutcome.Selected) onSelected(pending) }
            } finally {
                // Also when the agent withdraws the request, which cancels the handler.
                byRequest.remove(requestKey)
                filed?.let { byApproval.remove(it.approvalId) }
            }
        return ACPJson.encodeToJsonElement(RequestPermissionResponse.serializer(), RequestPermissionResponse(outcome))
    }

    /** Your choice for [approvalId]. */
    fun choose(
        approvalId: String,
        optionId: String,
    ) {
        val pending = byApproval[approvalId] ?: throw IllegalArgumentException("No pending approval $approvalId.")
        require(optionId in pending.optionIds) { "Approval $approvalId has no option $optionId." }
        pending.choice.complete(RequestPermissionOutcome.Selected(PermissionOptionId(optionId)))
    }

    /** Answers the requests that [matches] picks as cancelled. ACP wants an answer to each one. */
    fun cancelWhere(matches: (Pending) -> Boolean) {
        byApproval.values.filter(matches).forEach { it.choice.complete(RequestPermissionOutcome.Cancelled) }
    }

    /**
     * Ends every open request: one already filed is answered as cancelled, and a handler still
     * waiting for its request to be filed fails with [reason], which the SDK sends back as an error.
     * Used when the session's consumer stops, so no handler waits forever.
     */
    fun closeAll(reason: Exception) {
        cancelWhere { true }
        byRequest.values.forEach { it.completeExceptionally(reason) }
    }

    private fun meeting(requestKey: String) = byRequest.computeIfAbsent(requestKey) { CompletableDeferred() }
}
