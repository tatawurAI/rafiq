// PromptRequest's constructor takes an optional messageId that the SDK marks unstable. It is left
// unset; the opt-in is only for calling the constructor.
@file:OptIn(UnstableApi::class)

package ai.tatawur.rafiq.bridge.agent.acp

import ai.tatawur.rafiq.bridge.agent.AgentSession
import ai.tatawur.rafiq.bridge.agent.TurnInProgressException
import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.EventPayload
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.CancelNotification
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.PromptRequest
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.sendNotification
import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.JsonRpcJson
import com.agentclientprotocol.rpc.RequestIdSerializer
import com.agentclientprotocol.transport.StdioTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * One ACP session, driven through the SDK's JSON-RPC [Protocol] over the agent process's stdio.
 *
 * The SDK's higher-level `Client` is not used, because it serves only sessions this client opened:
 * it drops `session/update` for a child session id unless an unstable global handler is set, and it
 * answers a child's `session/request_permission` with an error. Helper threads need both, so this
 * class registers its own handlers on the [Protocol], which see every session id.
 *
 * Mapping happens in one consumer that reads [WireOrder.messages], so [updates] keeps the agent's
 * wire order exactly, including where a turn's answer falls among the notifications around it. The
 * [Protocol] handlers only answer the agent: the permission handler waits on the [ApprovalDesk] for
 * the consumer to map the request, then for your choice.
 *
 * Built by [AcpAgentAdapter], which sends `initialize` through [protocol] after [attach]. The adapter
 * calls [openedRoot] once `session/new`, `session/load` or `session/resume` names the root session.
 */
internal class AcpAgentSession(
    override val sessionId: String,
    private val process: AgentProcess,
    private val scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
    private val mapper: AcpEventMapper,
    private val newId: () -> String,
) : AgentSession {
    private val wire = WireOrder()

    /** The JSON-RPC connection to the agent. */
    val protocol =
        Protocol(
            scope,
            StdioTransport(scope, ioDispatcher, wire.inbound(process.lines), wire.outbound(process::write), "acp"),
        )

    private val lock = Any()
    private val events = Channel<EventPayload>(Channel.UNLIMITED)
    private val approvals = ApprovalDesk()

    // Updates that arrive before the root session id is known, such as the command list a new
    // session announces. Replayed through the mapper once the id is known.
    private var early: MutableList<Pair<String, JsonObject>>? = mutableListOf()
    private var replaying = false

    // True from a prompt until the agent answers it. ACP takes one prompt at a time per session.
    private var turnInFlight = false

    @Volatile private var closing = false
    private var finished = false
    private var exitWatcher: Job? = null

    // Completes once the consumer has mapped every message the agent wrote.
    private val outputDrained = CompletableDeferred<Unit>()

    override val agentSessionRef: String
        get() = mapper.threads.rootAcpSessionId ?: error("The ACP session has not opened yet.")

    override val mainThreadId: String = MAIN_THREAD_ID

    override val updates: Flow<EventPayload> = events.receiveAsFlow()

    /** Registers the handlers, starts the consumer and the protocol, and watches the process. Called once. */
    fun attach() {
        // Updates are mapped by the consumer. The handler is here so the SDK does not log them as unhandled.
        protocol.setNotificationHandlerRaw(AcpMethod.ClientMethods.V1.SessionUpdate) {}
        protocol.setRequestHandlerRaw(AcpMethod.ClientMethods.V1.SessionRequestPermission) { request ->
            val key = requestKey(JsonRpcJson.encodeToJsonElement(RequestIdSerializer, request.id))
            approvals.answer(key) { events.sendAll(lock, mapper.approvalAnswered(it.threadId)) }
        }
        scope.launch {
            for (message in wire.messages) consume(message)
        }
        exitWatcher =
            scope.launch {
                val exitCode = process.awaitExit()
                // The agent's last lines can still be in the queue. Report the exit after them.
                outputDrained.await()
                finish(exitCode)
            }
        protocol.start()
    }

    /**
     * Records the root session id and maps any updates that came before it.
     *
     * @param replay true while `session/load` replays history the event log already has. Root session
     *   updates are dropped until the agent answers the load.
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
            if (!replay) queued.forEach { (session, params) -> events.sendAll(mapper.notification(session, params)) }
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
        synchronized(lock) {
            if (turnInFlight) throw TurnInProgressException(threadId)
            turnInFlight = true
            events.sendAll(mapper.promptSent(threadId))
        }
        val request = PromptRequest(SessionId(target.acpSessionId), listOf(ContentBlock.Text(text)))
        val params = ACPJson.encodeToJsonElement(PromptRequest.serializer(), request)
        // Undispatched, so the request is written before this returns. The consumer reports the turn's
        // end when the answer comes, at its place in the wire order, so the result is not used here.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                protocol.sendRequestRaw(AcpMethod.AgentMethods.V1.SessionPrompt.methodName, params)
            } catch (_: JsonRpcException) {
                // An error answer: the consumer reports it as the turn failing.
            } catch (_: IllegalStateException) {
                // The connection closed under the request. The process exit is reported instead.
            }
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
        approvals.cancelWhere { it.acpSessionId == acpSessionId }
    }

    override suspend fun respondToApproval(
        approvalId: String,
        optionId: String,
    ) = approvals.choose(approvalId, optionId)

    override suspend fun close() {
        if (closing) return
        closing = true
        approvals.cancelWhere { true }
        outputDrained.complete(Unit)
        protocol.close()
        process.stop()
        // The watcher sees the exit and completes the updates. Finishing here as well covers a
        // session whose watcher never started.
        exitWatcher?.join()
        finish(exitCode = null)
    }

    /** Maps one message from the agent. Runs on the single consumer, in wire order. */
    private fun consume(message: WireOrder.Inbound) {
        when (message) {
            is WireOrder.Inbound.Notification -> {
                if (message.method == SESSION_UPDATE) message.params?.let(::onSessionUpdate)
            }

            is WireOrder.Inbound.Request -> {
                if (message.method == REQUEST_PERMISSION) onPermissionRequest(message.requestKey, message.params)
            }

            // A turn ends, or a load's replay is over, when the agent answers the request that started it.
            is WireOrder.Inbound.Response -> {
                when (message.method) {
                    PROMPT -> {
                        val stopReason = (message.result as? JsonObject)?.string("stopReason")
                        synchronized(lock) {
                            turnInFlight = false
                            events.sendAll(mapper.turnEnded(MAIN_THREAD_ID, stopReason, failure = message.error))
                        }
                    }

                    LOAD -> {
                        synchronized(lock) { replaying = false }
                    }
                }
            }

            WireOrder.Inbound.End -> {
                outputDrained.complete(Unit)
            }
        }
    }

    private fun onSessionUpdate(params: JsonObject) {
        val acpSessionId = params.string("sessionId") ?: return
        synchronized(lock) {
            val queue = early
            when {
                queue != null -> queue += acpSessionId to params
                replaying && acpSessionId == mapper.threads.rootAcpSessionId -> Unit
                else -> events.sendAll(mapper.notification(acpSessionId, params))
            }
        }
    }

    /** Maps a permission request and files it for the SDK handler to answer. */
    private fun onPermissionRequest(
        requestKey: String,
        params: JsonObject?,
    ) {
        val acpSessionId = params?.string("sessionId")
        if (params == null || acpSessionId == null) {
            approvals.rejected(requestKey, IllegalArgumentException("sessionId is missing."))
            return
        }
        val approvalId = newId()
        val pending =
            synchronized(lock) {
                val approval = mapper.permissionRequested(acpSessionId, params, approvalId)
                val request = approval.events.filterIsInstance<EventPayload.ApprovalRequest>().single()
                events.sendAll(approval.events)
                ApprovalDesk.Pending(approvalId, acpSessionId, approval.threadId, request.options.map { it.id }.toSet())
            }
        approvals.filed(requestKey, pending)
    }

    /** Completes [updates] once. An exit you did not ask for is reported first, so the app can say so. */
    private fun finish(exitCode: Int?) {
        synchronized(lock) {
            if (finished) return
            finished = true
            events.sendAll(mapper.sessionEnded())
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

/** Sends every payload. The channel is unlimited, so this never suspends. Call it under the session lock. */
private fun Channel<EventPayload>.sendAll(payloads: List<EventPayload>) {
    payloads.forEach { trySend(it) }
}

/** Takes [lock] and sends every payload, so events from different callers do not interleave. */
private fun Channel<EventPayload>.sendAll(
    lock: Any,
    payloads: List<EventPayload>,
) {
    synchronized(lock) { sendAll(payloads) }
}

/** Maps a `session/update` notification's params. */
private fun AcpEventMapper.notification(
    acpSessionId: String,
    params: JsonObject,
): List<EventPayload> {
    val update = params["update"] as? JsonObject ?: return emptyList()
    return sessionUpdate(acpSessionId, update, params["_meta"])
}

private val SESSION_UPDATE = AcpMethod.ClientMethods.V1.SessionUpdate.methodName.name
private val REQUEST_PERMISSION = AcpMethod.ClientMethods.V1.SessionRequestPermission.methodName.name
private val PROMPT = AcpMethod.AgentMethods.V1.SessionPrompt.methodName.name
private val LOAD = AcpMethod.AgentMethods.V1.SessionLoad.methodName.name
