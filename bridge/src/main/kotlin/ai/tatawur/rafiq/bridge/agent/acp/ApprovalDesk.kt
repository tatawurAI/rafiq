package ai.tatawur.rafiq.bridge.agent.acp

import ai.tatawur.rafiq.protocol.ApprovalOutcome
import com.agentclientprotocol.model.PermissionOptionId
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The agent's open permission requests, between the two places that handle each one.
 *
 * The session's consumer maps a request, in wire order, and files it here with [filed]. The SDK's
 * request handler waits in [answer] until the request is filed and then until it is resolved, and
 * returns the JSON-RPC result. Either side can get there first, so they meet on the request's
 * JSON-RPC id.
 *
 * A request is resolved once, by whoever gets there first: your choice, a cancel, the turn ending,
 * the agent withdrawing it, or the session ending. It is then reported once, see [claimReport], so
 * the app gets exactly one [ai.tatawur.rafiq.protocol.EventPayload.ApprovalResolved] per approval.
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
        private val resolution = AtomicReference<Resolution?>(null)
        private val reported = AtomicBoolean(false)

        private class Resolution(
            val outcome: ApprovalOutcome,
            val optionId: String?,
        )

        /** How it was resolved, or null while it is still open. */
        val outcome: ApprovalOutcome? get() = resolution.get()?.outcome

        /** The option you chose, when [outcome] is [ApprovalOutcome.SELECTED]. */
        val optionId: String? get() = resolution.get()?.optionId

        /** Resolves it as [outcome] and answers the agent, unless it is already resolved. */
        fun resolve(
            outcome: ApprovalOutcome,
            answer: RequestPermissionOutcome,
        ): Boolean {
            val optionId = (answer as? RequestPermissionOutcome.Selected)?.optionId?.value
            if (!resolution.compareAndSet(null, Resolution(outcome, optionId))) return false
            choice.complete(answer)
            return true
        }

        /** True for the one caller that gets to report it. */
        fun claimReport(): Boolean = reported.compareAndSet(false, true)
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
     * Waits for the request with [requestKey] to be filed and resolved, and returns the result to
     * send. When the agent withdraws the request, which cancels this handler, it is resolved as
     * withdrawn.
     *
     * @param onResolved called once the request is resolved, however that happened, to report it.
     */
    suspend fun answer(
        requestKey: String,
        onResolved: (Pending) -> Unit,
    ): JsonElement {
        var filed: Pending? = null
        val outcome =
            try {
                val pending = meeting(requestKey).await().also { filed = it }
                pending.choice.await()
            } finally {
                byRequest.remove(requestKey)
                filed?.let {
                    it.resolve(ApprovalOutcome.WITHDRAWN, RequestPermissionOutcome.Cancelled)
                    onResolved(it)
                }
            }
        return ACPJson.encodeToJsonElement(RequestPermissionResponse.serializer(), RequestPermissionResponse(outcome))
    }

    /** Your choice for [approvalId]. */
    fun choose(
        approvalId: String,
        optionId: String,
    ) {
        val pending =
            byApproval[approvalId]?.takeIf { it.outcome == null }
                ?: throw IllegalArgumentException("No pending approval $approvalId.")
        require(optionId in pending.optionIds) { "Approval $approvalId has no option $optionId." }
        pending.resolve(ApprovalOutcome.SELECTED, RequestPermissionOutcome.Selected(PermissionOptionId(optionId)))
    }

    /**
     * Resolves the open requests that [matches] picks as [outcome], answering the agent with
     * cancelled, as ACP wants for each one. Returns the ones this call resolved.
     */
    fun resolveWhere(
        outcome: ApprovalOutcome,
        matches: (Pending) -> Boolean,
    ): List<Pending> =
        byApproval.values.filter { matches(it) && it.resolve(outcome, RequestPermissionOutcome.Cancelled) }

    /**
     * Claims the one report of a resolved request. Returns false when it is still open or was already
     * reported, so a caller emits its event only on true.
     */
    fun claimReport(pending: Pending): Boolean {
        if (pending.outcome == null || !pending.claimReport()) return false
        byApproval.remove(pending.approvalId)
        return true
    }

    /**
     * Ends every open request as cancelled, and fails a handler still waiting for its request to be
     * filed with [reason], which the SDK sends back as an error. Returns every request not reported
     * yet, so the session can report them before it ends.
     */
    fun closeAll(reason: Exception): List<Pending> {
        resolveWhere(ApprovalOutcome.CANCELLED) { true }
        byRequest.values.forEach { it.completeExceptionally(reason) }
        return byApproval.values.toList()
    }

    private fun meeting(requestKey: String) = byRequest.computeIfAbsent(requestKey) { CompletableDeferred() }
}
