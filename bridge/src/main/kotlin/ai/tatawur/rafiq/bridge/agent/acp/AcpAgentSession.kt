// PromptRequest's constructor takes an optional messageId that the SDK marks unstable. It is left
// unset; the opt-in is only for calling the constructor.
@file:OptIn(UnstableApi::class)

package ai.tatawur.rafiq.bridge.agent.acp

import ai.tatawur.rafiq.bridge.agent.AgentSession
import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.EventPayload
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.CancelNotification
import com.agentclientprotocol.model.CancelRequestNotification
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.PermissionOptionId
import com.agentclientprotocol.model.PromptRequest
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.sendNotification
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.IOException

/**
 * One ACP session, driven through the SDK's JSON-RPC [Protocol] over the agent process's stdio.
 *
 * The SDK's higher-level `Client` is not used, because it serves only sessions this client opened:
 * it drops `session/update` for a child session id unless an unstable global handler is set, and it
 * answers a child's `session/request_permission` with an error. Helper threads need both, so this
 * class registers its own handlers on the [Protocol], which see every session id.
 *
 * Built by [AcpAgentAdapter], which has already sent `initialize`. [attach] wires the handlers before
 * the root session exists, and the adapter calls [openedRoot] once `session/new`, `session/load` or
 * `session/resume` names it.
 */
internal class AcpAgentSession(
    override val sessionId: String,
    private val protocol: Protocol,
    private val process: AgentProcess,
    private val scope: CoroutineScope,
    private val mapper: AcpEventMapper,
    private val newId: () -> String,
) : AgentSession {
    private val lock = Any()
    private val events = Channel<EventPayload>(Channel.UNLIMITED)
    private val pendingApprovals = mutableMapOf<String, PendingApproval>()

    // Updates that arrive before the root session id is known, such as the command list a new
    // session announces. Replayed through the mapper once the id is known.
    private var early: MutableList<Pair<String, JsonObject>>? = mutableListOf()
    private var replaying = false

    @Volatile private var closing = false
    private var finished = false
    private var exitWatcher: Job? = null

    // Completes once the protocol has handled every line the agent wrote, see [withEndOfOutput].
    private val outputDrained = CompletableDeferred<Unit>()

    // Parent of the coroutines that wait for a prompt's answer, so the end of the session can let
    // them report the turn's end first.
    private val turns = SupervisorJob(scope.coroutineContext[Job])
    private val turnScope = CoroutineScope(scope.coroutineContext + turns)

    override val agentSessionRef: String
        get() = mapper.threads.rootAcpSessionId ?: error("The ACP session has not opened yet.")

    override val mainThreadId: String = MAIN_THREAD_ID

    override val updates: Flow<EventPayload> = events.receiveAsFlow()

    /** Registers the handlers and starts watching the process. Called once, before `initialize`. */
    fun attach() {
        protocol.setNotificationHandlerRaw(AcpMethod.ClientMethods.V1.SessionUpdate) { notification ->
            onSessionUpdate(notification.params as? JsonObject ?: return@setNotificationHandlerRaw)
        }
        protocol.setRequestHandlerRaw(AcpMethod.ClientMethods.V1.SessionRequestPermission) { request ->
            onPermissionRequest(request.params as? JsonObject ?: JsonObject(emptyMap()))
        }
        protocol.setNotificationHandlerRaw(EndOfOutput) { outputDrained.complete(Unit) }
        exitWatcher =
            scope.launch {
                val exitCode = process.awaitExit()
                // The agent's last lines can still be on their way through the protocol. Handlers run
                // in wire order, so once the end marker is handled, everything before it has been.
                outputDrained.await()
                // Closing fails any prompt still waiting for an answer that will never come.
                protocol.close()
                turns.children.forEach { it.join() }
                finish(exitCode)
            }
    }

    /**
     * Records the root session id and maps any updates that came before it.
     *
     * @param replay true while `session/load` replays history the event log already has. Root session
     *   updates are dropped until this is called again with false.
     */
    fun openedRoot(
        acpSessionId: String,
        replay: Boolean,
    ) {
        synchronized(lock) {
            mapper.threads.rootAcpSessionId = acpSessionId
            replaying = replay
            val queued = early.orEmpty()
            early = null
            if (!replay) {
                queued.forEach { (session, params) ->
                    mapper.notification(session, params).forEach { events.trySend(it) }
                }
            }
        }
    }

    override suspend fun prompt(
        threadId: String,
        text: String,
    ) {
        check(!closing) { "The session is closed." }
        val target = synchronized(lock) { mapper.threads.target(threadId) }
        require(target is ThreadRegistry.Target.Root) {
            if (target == null) "Unknown thread $threadId." else "ACP agents take prompts only on the main thread."
        }
        emit(mapper.promptSent(threadId))
        val request = PromptRequest(SessionId(target.acpSessionId), listOf(ContentBlock.Text(text)))
        val params = ACPJson.encodeToJsonElement(PromptRequest.serializer(), request)
        // Undispatched, so the request is written before this returns. The answer comes when the
        // turn ends, which is reported as a status change rather than awaited here.
        turnScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val ended =
                try {
                    val result = protocol.sendRequestRaw(AcpMethod.AgentMethods.V1.SessionPrompt.methodName, params)
                    mapper.turnEnded(threadId, (result as? JsonObject)?.string("stopReason"), failure = null)
                } catch (e: JsonRpcException) {
                    mapper.turnEnded(threadId, stopReason = null, failure = e.message)
                } catch (e: IllegalStateException) {
                    // The connection closed under the request. The process exit is reported separately.
                    mapper.turnEnded(threadId, stopReason = null, failure = e.message ?: "The connection closed.")
                }
            emit(ended)
        }
    }

    override suspend fun cancel(threadId: String) {
        val acpSessionId =
            when (val target = synchronized(lock) { mapper.threads.target(threadId) }) {
                is ThreadRegistry.Target.Root -> {
                    target.acpSessionId
                }

                is ThreadRegistry.Target.Child -> {
                    if (!target.canCancel) {
                        throw UnsupportedOperationException(
                            "This helper cannot be stopped on its own.",
                        )
                    }
                    target.acpSessionId
                }

                ThreadRegistry.Target.Grouped -> {
                    throw UnsupportedOperationException(
                        "This agent cannot stop a helper without stopping the main thread.",
                    )
                }

                null -> {
                    throw IllegalArgumentException("Unknown thread $threadId.")
                }
            }
        protocol.sendNotification(AcpMethod.AgentMethods.V1.SessionCancel, CancelNotification(SessionId(acpSessionId)))
        // ACP requires the client to answer a cancelled session's open permission requests.
        pendingApprovals.cancelWhere(lock) { it.acpSessionId == acpSessionId }
    }

    override suspend fun respondToApproval(
        approvalId: String,
        optionId: String,
    ) {
        val pending =
            synchronized(lock) { pendingApprovals[approvalId] }
                ?: throw IllegalArgumentException("No pending approval $approvalId.")
        require(optionId in pending.optionIds) { "Approval $approvalId has no option $optionId." }
        pending.answer.complete(RequestPermissionOutcome.Selected(PermissionOptionId(optionId)))
    }

    override suspend fun close() {
        if (closing) return
        closing = true
        pendingApprovals.cancelWhere(lock) { true }
        outputDrained.complete(Unit)
        protocol.close()
        process.stop()
        // The watcher sees the exit and completes the updates. Finishing here as well covers a
        // session whose watcher never started.
        exitWatcher?.join()
        finish(exitCode = null)
    }

    private fun onSessionUpdate(params: JsonObject) {
        val acpSessionId = params.string("sessionId") ?: return
        synchronized(lock) {
            val queue = early
            when {
                queue != null -> queue += acpSessionId to params
                replaying && acpSessionId == mapper.threads.rootAcpSessionId -> Unit
                else -> mapper.notification(acpSessionId, params).forEach { events.trySend(it) }
            }
        }
    }

    private suspend fun onPermissionRequest(params: JsonObject): JsonElement {
        val acpSessionId = params.string("sessionId") ?: throw IllegalArgumentException("sessionId is missing.")
        val approvalId = newId()
        val answer = CompletableDeferred<RequestPermissionOutcome>()
        val threadId =
            synchronized(lock) {
                val approval = mapper.permissionRequested(acpSessionId, params, approvalId)
                val optionIds = approval.events.filterIsInstance<EventPayload.ApprovalRequest>().flatMap { it.options }
                pendingApprovals[approvalId] =
                    PendingApproval(acpSessionId, approval.threadId, optionIds.map { it.id }.toSet(), answer)
                approval.events.forEach { events.trySend(it) }
                approval.threadId
            }
        val outcome =
            try {
                answer.await()
            } finally {
                // Also when the agent withdraws the request, which cancels this handler.
                synchronized(lock) { pendingApprovals.remove(approvalId) }
            }
        if (outcome is RequestPermissionOutcome.Selected) emit(mapper.approvalAnswered(threadId))
        return ACPJson.encodeToJsonElement(RequestPermissionResponse.serializer(), RequestPermissionResponse(outcome))
    }

    private fun emit(payloads: List<EventPayload>) {
        synchronized(lock) { payloads.forEach { events.trySend(it) } }
    }

    /** Completes [updates] once. An exit you did not ask for is reported first, so the app can say so. */
    private fun finish(exitCode: Int?) {
        synchronized(lock) {
            if (finished) return
            finished = true
            mapper.sessionEnded().forEach { events.trySend(it) }
            if (!closing && exitCode != null) {
                events.trySend(
                    EventPayload.StatusChanged(
                        threadId = null,
                        state = AgentState.ERROR,
                        detail = "The agent process exited with code $exitCode.",
                    ),
                )
            }
            events.close()
        }
        protocol.close()
        scope.cancel(CancellationException("The agent session ended."))
    }
}

private class PendingApproval(
    val acpSessionId: String,
    val threadId: String,
    val optionIds: Set<String>,
    val answer: CompletableDeferred<RequestPermissionOutcome>,
)

/** Cancels the pending approvals that [matches] picks. ACP wants an answer to each one. */
private fun MutableMap<String, PendingApproval>.cancelWhere(
    lock: Any,
    matches: (PendingApproval) -> Boolean,
) {
    val toCancel = synchronized(lock) { values.filter(matches) }
    toCancel.forEach { it.answer.complete(RequestPermissionOutcome.Cancelled) }
}

/** Maps a `session/update` notification's params. */
private fun AcpEventMapper.notification(
    acpSessionId: String,
    params: JsonObject,
): List<EventPayload> {
    val update = params["update"] as? JsonObject ?: return emptyList()
    return sessionUpdate(acpSessionId, update, params["_meta"])
}

/**
 * The agent's stdout lines, followed by one end-of-output notification of the bridge's own.
 *
 * A process can exit while its last lines are still being parsed and dispatched. The marker goes
 * through the same path, so when its handler runs the session knows nothing the agent wrote is lost.
 * ACP keeps method names that start with an underscore for extensions, so no agent sends this one.
 */
internal fun withEndOfOutput(lines: Flow<String>): Flow<String> =
    lines
        // A broken pipe ends the output like a closed one. The process exit says what happened.
        .catch { if (it !is IOException) throw it }
        .onCompletion { cause -> if (cause == null) emit(END_OF_OUTPUT_LINE) }

private object EndOfOutput : AcpMethod.AcpNotificationMethod<CancelRequestNotification>(
    "_rafiq/end_of_output",
    // Required by the type, never used: the handler is raw and reads nothing.
    CancelRequestNotification.serializer(),
)

private val END_OF_OUTPUT_LINE = """{"jsonrpc":"2.0","method":"${EndOfOutput.methodName.name}"}"""
