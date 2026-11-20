// PromptRequest's constructor takes an optional messageId that the SDK marks unstable. It is left
// unset; the opt-in is only for calling the constructor.
@file:OptIn(UnstableApi::class)

package ai.tatawur.rafiq.bridge.agent.acp

import ai.tatawur.rafiq.bridge.agent.AgentSession
import ai.tatawur.rafiq.bridge.agent.AgentUnavailableException
import ai.tatawur.rafiq.protocol.ApprovalOutcome
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One ACP session, driven through the SDK's JSON-RPC [Protocol] over the agent process's stdio.
 *
 * The SDK's higher-level `Client` is not used, because it serves only sessions this client opened:
 * it drops `session/update` for a child session id unless an unstable global handler is set, and it
 * answers a child's `session/request_permission` with an error. Helper threads need both, so this
 * class registers its own handlers on the [Protocol], which see every session id.
 *
 * All state lives in a [SessionActor], whose single consumer reads what [WireOrder] takes from the
 * raw stdout lines and what the bridge asks for. So [updates] keeps the agent's wire order, including
 * where a turn's answer falls among the notifications around it. The [Protocol] handlers only answer
 * the agent: the permission handler waits on the [ApprovalDesk] for the consumer to map the request,
 * then for your choice.
 *
 * Built by [AcpAgentAdapter], which sends `initialize` through [protocol] after [attach]. The adapter
 * calls [openedRoot] once `session/new`, `session/load` or `session/resume` names the root session.
 *
 * @param drainTimeout how long the session waits, after the process exits, for its last lines to be
 *   mapped before it reports the exit.
 */
internal class AcpAgentSession(
    override val sessionId: String,
    private val process: AgentProcess,
    private val scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
    mapper: AcpEventMapper,
    newId: () -> String,
    private val drainTimeout: Duration = 5.seconds,
) : AgentSession {
    private val approvals = ApprovalDesk()
    private val actor = SessionActor(mapper, approvals, newId)
    private val wire = WireOrder { actor.offer(SessionActor.Item.Wire(it)) }

    /** The JSON-RPC connection to the agent. */
    val protocol =
        Protocol(
            scope,
            StdioTransport(scope, ioDispatcher, wire.inbound(process.lines), wire.outbound(process::write), "acp"),
        )

    @Volatile private var rootRef: String? = null
    private val closeStarted = AtomicBoolean(false)
    private val closed = CompletableDeferred<Unit>()
    private var exitWatcher: Job? = null

    override val agentSessionRef: String
        get() = rootRef ?: error("The ACP session has not opened yet.")

    override val mainThreadId: String = MAIN_THREAD_ID

    override val updates: Flow<EventPayload> = actor.updates

    /** Registers the handlers, starts the consumer and the protocol, and watches the process. Called once. */
    fun attach() {
        // Updates are mapped by the consumer. The handler is here so the SDK does not log them as unhandled.
        protocol.setNotificationHandlerRaw(AcpMethod.ClientMethods.V1.SessionUpdate) {}
        protocol.setRequestHandlerRaw(AcpMethod.ClientMethods.V1.SessionRequestPermission) { request ->
            val key = requestKey(JsonRpcJson.encodeToJsonElement(RequestIdSerializer, request.id))
            approvals.answer(key) { actor.offer(SessionActor.Item.Resolved(it)) }
        }
        scope.launch { actor.run() }
        exitWatcher =
            scope.launch {
                val exitCode = process.awaitExit()
                // The agent's last lines can still be queued. Report the exit after them, but do not
                // wait forever on a reader that never finishes.
                withTimeoutOrNull(drainTimeout) { actor.outputDrained.await() }
                actor.offer(SessionActor.Item.Finish(exitCode, askedToClose = closeStarted.get()))
                actor.finished.await()
                protocol.close()
                scope.cancel(CancellationException("The agent session ended."))
            }
        protocol.start()
    }

    /**
     * Records the root session id.
     *
     * @param replay true while `session/load` replays history the event log already has. Root session
     *   updates are dropped until the agent answers the load.
     */
    fun openedRoot(
        acpSessionId: String,
        replay: Boolean,
    ) {
        rootRef = acpSessionId
        actor.offer(SessionActor.Item.OpenedRoot(acpSessionId, replay))
    }

    /**
     * Sends [text] as a prompt. The consumer accepts it first, which marks the turn as running, so the
     * accept and the write must not be split: a caller cancelled in between would leave a turn that
     * never ends and refuse every later prompt. Both run as one non-cancellable step, and a write that
     * fails ends the turn again.
     */
    override suspend fun prompt(
        threadId: String,
        text: String,
    ) = withContext(NonCancellable) {
        val acpSessionId = ask { SessionActor.Item.Prompt(threadId, it) }
        val request = PromptRequest(SessionId(acpSessionId), listOf(ContentBlock.Text(text)))
        val params = ACPJson.encodeToJsonElement(PromptRequest.serializer(), request)
        // Undispatched, so the request is written before this returns. The consumer reports the turn's
        // end when the answer comes, at its place in the wire order, so the result is not used here.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                protocol.sendRequestRaw(AcpMethod.AgentMethods.V1.SessionPrompt.methodName, params)
            } catch (_: JsonRpcException) {
                // An error answer: the consumer reports it as the turn failing.
            } catch (_: IllegalStateException) {
                // The request could not be written, for example because the connection closed.
                actor.offer(SessionActor.Item.PromptNotSent)
            }
        }
        Unit
    }

    override suspend fun cancel(threadId: String) {
        val acpSessionId = ask { SessionActor.Item.Cancel(threadId, it) }
        protocol.sendNotification(AcpMethod.AgentMethods.V1.SessionCancel, CancelNotification(SessionId(acpSessionId)))
        // ACP requires the client to answer a cancelled session's open permission requests.
        approvals.resolveWhere(ApprovalOutcome.CANCELLED) { it.acpSessionId == acpSessionId }
    }

    override suspend fun respondToApproval(
        approvalId: String,
        optionId: String,
    ) = approvals.choose(approvalId, optionId)

    /** Ends the session. A second caller waits for the first one's shutdown to finish. */
    override suspend fun close() {
        if (!closeStarted.compareAndSet(false, true)) {
            closed.await()
            return
        }
        try {
            approvals.resolveWhere(ApprovalOutcome.CANCELLED) { true }
            protocol.close()
            process.stop()
            exitWatcher?.join()
            // Covers a session whose watcher never ran. Refused when the watcher already finished it.
            actor.offer(SessionActor.Item.Finish(exitCode = null, askedToClose = true))
            actor.finished.await()
            scope.cancel(CancellationException("The agent session ended."))
        } finally {
            closed.complete(Unit)
        }
    }

    /** Sends a request to the consumer and waits for its answer, or for what it threw. */
    private suspend fun ask(item: (CompletableDeferred<String>) -> SessionActor.Item): String {
        if (closeStarted.get()) throw AgentUnavailableException("The agent session is closed.")
        val reply = CompletableDeferred<String>()
        if (!actor.offer(item(reply))) throw AgentUnavailableException("The agent session has ended.")
        return reply.await()
    }
}
