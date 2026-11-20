package ai.tatawur.rafiq.bridge.agent.acp

import ai.tatawur.rafiq.bridge.agent.AgentUnavailableException
import ai.tatawur.rafiq.bridge.agent.TurnInProgressException
import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.ContentBlock
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.MessageRole
import com.agentclientprotocol.model.AcpMethod
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory

/**
 * The one place a session's state changes: a single consumer of [Item]s that owns the mapper.
 *
 * Everything that touches the mapper arrives here as an item, in the order it happened: what the
 * agent wrote (see [WireOrder]), and what the bridge asked for, such as a prompt or an answered
 * approval. Items are handled one at a time, so the mapper needs no lock and the [updates] keep the
 * agent's wire order. Callers that need an answer, such as a prompt that may be refused, pass a
 * reply that the consumer completes.
 */
internal class SessionActor(
    private val mapper: AcpEventMapper,
    private val approvals: ApprovalDesk,
    private val newId: () -> String,
) {
    /** A change to the session, queued for the consumer. */
    sealed interface Item {
        data class Wire(
            val inbound: WireOrder.Inbound,
        ) : Item

        /** The root session is known. While [replay] is set, root updates are history and are dropped. */
        data class OpenedRoot(
            val acpSessionId: String,
            val replay: Boolean,
        ) : Item

        /** A prompt for [threadId]. The reply is the ACP session to send it to. */
        data class Prompt(
            val threadId: String,
            val reply: CompletableDeferred<String>,
        ) : Item

        /** A cancel for [threadId]. The reply is the ACP session to cancel. */
        data class Cancel(
            val threadId: String,
            val reply: CompletableDeferred<String>,
        ) : Item

        /** You chose an option on [threadId]'s approval. */
        data class Answered(
            val threadId: String,
        ) : Item

        /**
         * The session is over.
         *
         * @param exitCode the agent's exit code, or null when it is unknown.
         * @param askedToClose true when the bridge closed the session, so the exit is not an error.
         */
        data class Finish(
            val exitCode: Int?,
            val askedToClose: Boolean,
        ) : Item
    }

    private val items = Channel<Item>(Channel.UNLIMITED)
    private val events = Channel<EventPayload>(Channel.UNLIMITED)

    // Updates that arrive before the root session id is known, such as the command list a new
    // session announces. Mapped once the id is known.
    private var early: MutableList<Pair<String, JsonObject>>? = mutableListOf()
    private var replaying = false

    // From a prompt until the agent answers it. ACP takes one prompt at a time per session.
    private var turnInFlight = false

    /** What the session reports, in order. Completes after [Item.Finish]. */
    val updates: Flow<EventPayload> = events.receiveAsFlow()

    /** Completes when the agent's last line has been handled. */
    val outputDrained = CompletableDeferred<Unit>()

    /** Completes when [Item.Finish] has been handled. */
    val finished = CompletableDeferred<Unit>()

    /** Queues [item]. Returns false once the session has finished. */
    fun offer(item: Item): Boolean = items.trySend(item).isSuccess

    /** Handles items until [Item.Finish]. Run it once, in the session's scope. */
    suspend fun run() {
        for (item in items) handle(item)
    }

    private fun handle(item: Item) {
        when (item) {
            is Item.Wire -> {
                onWire(item.inbound)
            }

            is Item.OpenedRoot -> {
                mapper.threads.rootAcpSessionId = item.acpSessionId
                replaying = item.replay
                val queued = early.orEmpty()
                early = null
                if (!item.replay) queued.forEach { (session, params) -> onSessionUpdate(session, params) }
            }

            is Item.Prompt -> {
                item.reply.answer { onPrompt(item.threadId) }
            }

            is Item.Cancel -> {
                item.reply.answer { onCancel(item.threadId) }
            }

            is Item.Answered -> {
                events.sendAll(mapper.approvalAnswered(item.threadId))
            }

            is Item.Finish -> {
                finish(item)
            }
        }
    }

    private fun onWire(inbound: WireOrder.Inbound) {
        when (inbound) {
            is WireOrder.Inbound.Notification -> {
                val params = inbound.params
                val acpSessionId = params?.string("sessionId")
                if (inbound.method == SESSION_UPDATE && acpSessionId != null) onSessionUpdate(acpSessionId, params)
            }

            is WireOrder.Inbound.Request -> {
                if (inbound.method == REQUEST_PERMISSION) onPermissionRequest(inbound.requestKey, inbound.params)
            }

            is WireOrder.Inbound.Response -> {
                onResponse(inbound)
            }

            WireOrder.Inbound.Dropped -> {
                events.sendAll(listOf(systemMessage(newId(), LINE_TOO_LONG)))
            }

            WireOrder.Inbound.End -> {
                outputDrained.complete(Unit)
            }
        }
    }

    /** A turn ends, or a load's replay is over, when the agent answers the request that started it. */
    private fun onResponse(response: WireOrder.Inbound.Response) {
        when (response.method) {
            PROMPT -> {
                turnInFlight = false
                if (response.error != null) logger.warn("the agent failed a turn: {}", response.error)
                val stopReason = (response.result as? JsonObject)?.string("stopReason")
                val failure = response.error?.let { TURN_FAILED }
                events.sendAll(mapper.turnEnded(MAIN_THREAD_ID, stopReason, failure))
            }

            LOAD -> {
                replaying = false
            }
        }
    }

    private fun onPrompt(threadId: String): String {
        val target = mapper.threads.target(threadId)
        require(target is ThreadRegistry.Target.Root) {
            if (target == null) "Unknown thread $threadId." else "ACP agents take prompts only on the main thread."
        }
        if (turnInFlight) throw TurnInProgressException(threadId)
        turnInFlight = true
        events.sendAll(mapper.promptSent(threadId))
        return target.acpSessionId
    }

    private fun onCancel(threadId: String): String =
        when (val target = mapper.threads.target(threadId)) {
            is ThreadRegistry.Target.Root -> {
                target.acpSessionId
            }

            is ThreadRegistry.Target.Child -> {
                if (!target.canCancel) throw UnsupportedOperationException(CHILD_NOT_CANCELLABLE)
                target.acpSessionId
            }

            ThreadRegistry.Target.Grouped -> {
                throw UnsupportedOperationException(GROUPED_NOT_CANCELLABLE)
            }

            null -> {
                throw IllegalArgumentException("Unknown thread $threadId.")
            }
        }

    private fun onSessionUpdate(
        acpSessionId: String,
        params: JsonObject,
    ) {
        val queue = early
        val update = params["update"] as? JsonObject
        when {
            queue != null -> queue += acpSessionId to params
            replaying && acpSessionId == mapper.threads.rootAcpSessionId -> Unit
            update != null -> events.sendAll(mapper.sessionUpdate(acpSessionId, update, params["_meta"]))
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
        val approval = mapper.permissionRequested(acpSessionId, params, approvalId)
        val request = approval.events.filterIsInstance<EventPayload.ApprovalRequest>().single()
        events.sendAll(approval.events)
        approvals.filed(
            requestKey,
            ApprovalDesk.Pending(approvalId, acpSessionId, approval.threadId, request.options.map { it.id }.toSet()),
        )
    }

    /**
     * Ends the session: finishes open messages, reports an exit you did not ask for, and completes
     * [updates]. Items still queued behind it are refused, so no caller waits on a reply forever.
     */
    private fun finish(item: Item.Finish) {
        events.sendAll(mapper.sessionEnded())
        if (!item.askedToClose) {
            val code = item.exitCode?.toString() ?: "unknown"
            events.sendAll(listOf(EventPayload.StatusChanged(state = AgentState.ERROR, detail = "$EXITED $code.")))
        }
        events.close()
        items.close()
        while (true) {
            val left = items.tryReceive().getOrNull() ?: break
            val ended = AgentUnavailableException("The agent session has ended.")
            when (left) {
                is Item.Prompt -> left.reply.completeExceptionally(ended)
                is Item.Cancel -> left.reply.completeExceptionally(ended)
                else -> Unit
            }
        }
        finished.complete(Unit)
    }

    private companion object {
        val logger = LoggerFactory.getLogger(SessionActor::class.java)
    }
}

/** Sends every payload. The channel is unlimited, so this never suspends. */
private fun Channel<EventPayload>.sendAll(payloads: List<EventPayload>) {
    payloads.forEach { trySend(it) }
}

/** A note from the bridge on the main thread. */
private fun systemMessage(
    id: String,
    text: String,
) = EventPayload.Message(
    id = id,
    threadId = MAIN_THREAD_ID,
    role = MessageRole.SYSTEM,
    content = listOf(ContentBlock.Text(text)),
)

/** Completes with [block]'s result, or with what it threw, so the caller sees the refusal. */
private inline fun <T> CompletableDeferred<T>.answer(block: () -> T) {
    try {
        complete(block())
    } catch (e: IllegalArgumentException) {
        completeExceptionally(e)
    } catch (e: UnsupportedOperationException) {
        completeExceptionally(e)
    } catch (e: TurnInProgressException) {
        completeExceptionally(e)
    }
}

/** The start of the status detail when the agent process exits on its own. */
internal const val EXITED = "The agent process exited with code"

private const val TURN_FAILED = "The agent could not finish the turn."
private const val LINE_TOO_LONG = "The agent sent a line too long to read. It was left out."
private const val CHILD_NOT_CANCELLABLE = "This helper cannot be stopped on its own."
private const val GROUPED_NOT_CANCELLABLE = "This agent cannot stop a helper without stopping the main thread."

private val SESSION_UPDATE = AcpMethod.ClientMethods.V1.SessionUpdate.methodName.name
private val REQUEST_PERMISSION = AcpMethod.ClientMethods.V1.SessionRequestPermission.methodName.name
private val PROMPT = AcpMethod.AgentMethods.V1.SessionPrompt.methodName.name
private val LOAD = AcpMethod.AgentMethods.V1.SessionLoad.methodName.name
