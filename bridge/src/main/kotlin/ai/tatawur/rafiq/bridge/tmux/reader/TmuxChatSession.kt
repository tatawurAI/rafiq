package ai.tatawur.rafiq.bridge.tmux.reader

import ai.tatawur.rafiq.bridge.hook.HookEnvelope
import ai.tatawur.rafiq.bridge.hook.HookReply
import ai.tatawur.rafiq.bridge.server.ConnectionRegistry
import ai.tatawur.rafiq.bridge.sessions.SessionException
import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.AgentThread
import ai.tatawur.rafiq.protocol.ApprovalOutcome
import ai.tatawur.rafiq.protocol.ContentBlock
import ai.tatawur.rafiq.protocol.DeliveryState
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.MessageRole
import ai.tatawur.rafiq.protocol.Session
import ai.tatawur.rafiq.protocol.ThreadKind
import ai.tatawur.rafiq.protocol.api.SendRefusal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The waits of the reader and the send guard, in one place so tests can name them. */
data class ReaderTiming(
    /** How long a step's card waits for the text written before it. */
    val orderWindow: Duration = 250.milliseconds,
    /** How long after a `Stop` the reply is checked, so late batches and the record can catch up. */
    val stopWait: Duration = 1.seconds,
    /** How often the agent's status is read while a turn runs or a prompt waits. */
    val poll: Duration = 1500.milliseconds,
    /** How long without a hook before the status alone may end or start a turn. */
    val quiet: Duration = 2.seconds,
    /** How long the bridge waits for a sign that a typed prompt landed. */
    val confirmWait: Duration = 5.seconds,
    /** How often the input box is read while confirming a prompt that starts no turn. */
    val boxPoll: Duration = 300.milliseconds,
    /** How long a phone may take to answer an approval. The installed hook waits 900 s, so this ends first. */
    val phoneDeadline: Duration = 600.seconds,
    /** How long a prompt may wait for a busy agent before it is given up as not delivered. */
    val queueMaxAge: Duration = 10.minutes,
)

/** What every session's Chat shares: the reader's collaborators and its waits. */
@Suppress("LongParameterList") // A bundle of collaborators, so each session takes one parameter instead of ten.
internal class ReaderContext(
    val log: ReaderLog,
    val guard: SendGuard,
    val statuses: AgentStatusSource,
    val approvals: PhoneApprovals,
    val offsets: ReaderOffsets,
    val hooksInstalled: suspend (projectPath: String) -> Boolean,
    val scope: CoroutineScope,
    val nowMs: () -> Long,
    val ioDispatcher: CoroutineDispatcher,
    val timing: ReaderTiming,
    /** Claude Code's folder of session records. A record path outside it is never read. Null reads none. */
    val recordRoot: Path?,
    /** Where a queued prompt registers with its device, so revoking the device drops it. */
    val registry: ConnectionRegistry?,
    /** Adds the session fields the log does not store, such as `terminalAllowed`, to a `SessionUpdated`. */
    val present: (Session) -> Session = { it },
)

/** A Claude pane the tmux scan found, with what `claude agents --json` said about its process. */
data class FoundPane(
    val paneId: String,
    val agentPid: Long,
    val isCurrent: Boolean,
    val claudeSessionId: String?,
    val recordPath: Path?,
    /** Every `claude` process under the pane. The hook's own pid wins when it is one of them. */
    val candidates: List<Long> = listOf(agentPid),
)

/**
 * One tmux session's Chat: every Claude pane in it, the approvals they raise and the prompts you send.
 *
 * Hook events, timers and status reads go through one inbox and are handled one at a time, in order, by one
 * coroutine. That keeps a turn's events in the order the agent produced them. Prompts are typed by the caller's
 * coroutine under [sendLock], so a slow confirmation never holds up the events that confirm it.
 *
 * The first Claude pane found is the main thread, and prompts go to it. Every other Claude pane in the session
 * is followed too, on a thread of its own.
 */
@Suppress("TooManyFunctions", "LargeClass") // The reader's event loop: one handler per input, kept together.
internal class TmuxChatSession(
    val sessionId: String,
    context: ReaderContext,
) {
    private val log = context.log
    private val present = context.present
    private val guard = context.guard
    private val statuses = context.statuses
    private val approvals = context.approvals
    private val offsets = context.offsets
    private val hooksInstalled = context.hooksInstalled
    private val scope = context.scope
    private val nowMs = context.nowMs
    private val ioDispatcher = context.ioDispatcher
    private val timing = context.timing
    private val recordRoot = context.recordRoot
    private val registry = context.registry

    private sealed interface Input {
        class Hook(
            val envelope: HookEnvelope,
        ) : Input

        class Permission(
            val envelope: HookEnvelope,
            val raised: CompletableDeferred<PendingPhoneApproval?>,
        ) : Input

        class FlushCard(
            val paneId: String,
            val toolUseId: String,
        ) : Input

        class StopDue(
            val paneId: String,
            val generation: Int,
        ) : Input

        class Statuses(
            val statuses: Map<Long, AgentStatus>?,
        ) : Input

        class Found(
            val panes: List<FoundPane>,
        ) : Input

        class Refresh(
            val paneId: String?,
        ) : Input

        data object Tick : Input

        data object DrainDone : Input
    }

    /** A prompt the bridge typed and is waiting to see land. */
    private class ExpectedPrompt(
        val paneId: String,
        val text: String,
        val clientId: String,
    ) {
        val confirmed = CompletableDeferred<Unit>()

        /** Set by whoever writes the prompt's message, so it is written once. */
        val logged = AtomicBoolean(false)

        /** True once it was marked not delivered, so a late landing can still deliver it. */
        @Volatile var failed = false

        @Volatile var finishedAtMs = 0L
    }

    private class QueuedPrompt(
        val text: String,
        val clientId: String,
        val queuedAtMs: Long,
    ) {
        /** Its registration with the device that sent it, closed when it leaves the queue. */
        @Volatile var registration: AutoCloseable? = null
    }

    private val inbox = Channel<Input>(Channel.UNLIMITED)
    private val panes = LinkedHashMap<String, PaneConversation>()
    private val sendLock = Mutex()
    private val expected = AtomicReference<ExpectedPrompt?>(null)
    private val queue = ConcurrentLinkedQueue<QueuedPrompt>()

    /**
     * Prompts whose wait just ended, newest last: one marked not delivered may still land, and one confirmed by
     * the box clearing may still send its hook. Either way its message is written once.
     */
    private val recent = ConcurrentLinkedDeque<ExpectedPrompt>()
    private var polling = false
    private var tickScheduled = false
    private var draining = false
    private var nextWorker = 1

    @Volatile private var mainTarget: PaneTarget? = null

    /** The main pane's permission mode, as its hooks last reported it. */
    @Volatile var permissionMode: String? = null
        private set

    private val loop =
        scope.launch {
            for (input in inbox) {
                @Suppress("TooGenericExceptionCaught") // One bad event must not stop the session's Chat.
                try {
                    handle(input)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    logger.warn("The reader skipped an input for {}: {}", sessionId, failure.toString())
                }
            }
        }

    /** An observer event from one of this session's panes. Returns at once. */
    fun onEvent(envelope: HookEnvelope) {
        inbox.trySend(Input.Hook(envelope))
    }

    /** What the tmux scan found in this session: every pane that runs Claude now. */
    fun onFound(found: List<FoundPane>) {
        inbox.trySend(Input.Found(found))
    }

    /**
     * Holds a permission request until a phone answers it, the desk does, the deadline passes or the hook goes
     * away. Null means "ask at the desk".
     */
    suspend fun onPermission(envelope: HookEnvelope): HookReply? {
        val raised = CompletableDeferred<PendingPhoneApproval?>()
        inbox.send(Input.Permission(envelope, raised))
        val approval =
            try {
                raised.await()
            } catch (cancelled: CancellationException) {
                // The hook left before the request was raised. The loop sees the cancel and settles it.
                raised.cancel()
                throw cancelled
            } ?: return null
        try {
            withTimeoutOrNull(timing.phoneDeadline) { approval.reply.await() }
            if (!approval.reply.isCompleted) approvals.settle(approval.id, ApprovalOutcome.ASK_AT_DESK)
            // Whoever settled it completes the reply right after, so wait for it rather than read it.
            return approval.reply.await()
        } catch (cancelled: CancellationException) {
            // The hook disconnected: release the approval at once.
            withContext(NonCancellable) { approvals.settle(approval.id, ApprovalOutcome.ASK_AT_DESK) }
            throw cancelled
        }
    }

    /** An approval settled, so the pane's status and progress may change. */
    fun onApprovalSettled(paneId: String) {
        inbox.trySend(Input.Refresh(paneId))
    }

    /**
     * Your prompt from a phone, through the send guard. See [TmuxChat.prompt].
     */
    suspend fun prompt(
        text: String,
        clientId: String?,
        deviceId: String? = null,
    ) {
        val clean = sanitizePrompt(text)
        if (clean.isBlank()) throw SessionException.Invalid("empty_prompt", "The prompt text is empty.")
        val refusal = sendLock.withLock { send(clean, clientId ?: ("c-" + UUID.randomUUID()), deviceId) }
        if (refusal != null) throw SessionException.Conflict(refusal.code, refusal.message)
    }

    /** The guard, then typing or queueing. Returns why the prompt was refused, or null when it was taken. */
    private suspend fun send(
        text: String,
        clientId: String,
        deviceId: String?,
    ): Refusal? {
        val target = mainTarget ?: return Refusal.NOT_RUNNING
        val project = log.session(sessionId)?.projectPath
        val hooksMissing = project != null && !hooksPresent(project)
        val ready = if (hooksMissing) null else guard.readiness(target, approvals.hasPendingFor(target.paneId))
        return when (ready) {
            null -> {
                Refusal.NO_HOOKS
            }

            is Readiness.Refused -> {
                ready.reason
            }

            Readiness.Busy -> {
                enqueue(text, clientId, deviceId)
                null
            }

            // Prompts go in the order you sent them: one already waiting goes first.
            Readiness.Ready -> {
                if (queue.isNotEmpty()) {
                    enqueue(text, clientId, deviceId)
                    null
                } else {
                    val expectation = ExpectedPrompt(target.paneId, text, clientId)
                    typeIt(target, expectation) {}.also { refused ->
                        if (refused == null) scope.launch { confirm(expectation, clientId) }
                    }
                }
            }
        }
    }

    /** A project the installer cannot read, for example one that is not a folder, has no hooks either. */
    private suspend fun hooksPresent(project: String): Boolean =
        try {
            hooksInstalled(project)
        } catch (_: SessionException) {
            false
        }

    /**
     * Stops the session's loop. Pending approvals go back to the desk, a request still in the inbox is answered
     * "ask at the desk", and waiting prompts are not delivered.
     */
    suspend fun close() {
        loop.cancel()
        inbox.close()
        while (true) {
            val input = inbox.tryReceive().getOrNull() ?: break
            if (input is Input.Permission) input.raised.complete(null)
        }
        for (paneId in panes.keys.toList()) approvals.withdrawForPane(paneId, ApprovalOutcome.ASK_AT_DESK)
        while (true) {
            val next = queue.poll() ?: break
            next.registration?.close()
            delivery(next.clientId, DeliveryState.NOT_DELIVERED, Refusal.NOT_RUNNING)
        }
    }

    private suspend fun handle(input: Input) {
        when (input) {
            is Input.Hook -> onHook(input.envelope)
            is Input.Permission -> raise(input.envelope, input.raised)
            is Input.FlushCard -> panes[input.paneId]?.let { it.flush(input.toolUseId) }
            is Input.StopDue -> panes[input.paneId]?.let { pane -> checkStop(pane, input.generation) }
            is Input.Statuses -> onStatuses(input.statuses)
            is Input.Found -> onFoundPanes(input.panes)
            is Input.Refresh -> input.paneId?.let(panes::get)?.let { refreshState(it) }
            Input.Tick -> onTick()
            Input.DrainDone -> draining = false
        }
        schedulePollIfNeeded()
    }

    @Suppress("CyclomaticComplexMethod") // One branch per hook event.
    private suspend fun onHook(envelope: HookEnvelope) {
        val event = envelope.event
        val name = event.str("hook_event_name") ?: return
        val pane = paneFor(envelope.pane)
        pane.lastHookAtMs = envelope.receivedAtMs
        envelope.agentPid?.let {
            pane.agentPid = it
            pane.hookPid = it
        }
        bindRecord(pane, event.str("session_id"), event.str("transcript_path"), event.str("source"), name)
        event.str("permission_mode")?.let { updatePermissionMode(pane, it) }
        updateMainTarget()
        when (name) {
            ClaudeHook.USER_PROMPT_SUBMIT -> {
                onPrompt(pane, event.str("prompt").orEmpty(), event.str("prompt_id"), envelope.receivedAtMs)
            }

            ClaudeHook.MESSAGE_DISPLAY -> {
                onDisplay(pane, event, envelope.receivedAtMs)
            }

            ClaudeHook.PRE_TOOL_USE -> {
                onPreTool(pane, event, envelope.receivedAtMs)
            }

            ClaudeHook.POST_TOOL_USE -> {
                onPostTool(pane, event, failed = false)
            }

            ClaudeHook.POST_TOOL_USE_FAILURE -> {
                onPostTool(pane, event, failed = true)
            }

            ClaudeHook.POST_TOOL_BATCH -> {
                onBatch(pane, event)
            }

            ClaudeHook.STOP -> {
                onStop(pane, event.str("last_assistant_message"))
            }

            ClaudeHook.STOP_FAILURE -> {
                endTurn(pane, AgentState.ERROR, "The turn ended with an error.")
            }

            ClaudeHook.SUBAGENT_STOP -> {
                pane.onSubagentStop(event.str("agent_type"), event.str("last_assistant_message").orEmpty())
            }

            ClaudeHook.SESSION_END -> {
                if (event.str("reason") != ClaudeHook.END_REASON_CLEAR) {
                    endTurn(pane, AgentState.DONE, "Claude Code exited.", ApprovalOutcome.ASK_AT_DESK)
                }
            }

            else -> {
                Unit
            }
        }
        pane.progress(approvals.hasPendingFor(pane.paneId), nowMs())
    }

    /**
     * A prompt reached the agent, from your phone or your desk. Either way it becomes a message with your text
     * and a stable id (see [PaneConversation.promptMessageId]). A phone prompt that was waiting to land is
     * delivered now, and one already marked not delivered is delivered late.
     */
    private suspend fun onPrompt(
        pane: PaneConversation,
        rawPrompt: String,
        promptId: String?,
        atMs: Long,
    ) {
        // A reply check still waiting from the last turn runs now, before the next turn begins.
        pane.stopCheck?.let { checkStop(pane, it.generation) }
        val prompt = unwrapPaste(rawPrompt)
        val waiting = expected.get()?.takeIf { it.paneId == pane.paneId && sameText(it.text, prompt) }
        val earlier = if (waiting == null) recentPrompt(pane.paneId, prompt) else null
        val phone = waiting ?: earlier
        pane.startTurn(atMs)
        val shown = prompt.isNotBlank() && !prompt.startsWith(TASK_NOTICE)
        if (shown && phone?.logged?.compareAndSet(false, true) != false) {
            val fallback = phone?.let { "user:${it.clientId}" } ?: ("user:" + UUID.randomUUID())
            pane.userMessage(prompt, pane.promptMessageId(promptId) ?: fallback)
        }
        landed(waiting, earlier)
        approvals.withdrawForPane(pane.paneId)
        pane.state(AgentState.WORKING, WORKING_DETAIL)
    }

    /** A phone prompt reached the agent: the one waiting now is confirmed, one already given up on is delivered. */
    private suspend fun landed(
        waiting: ExpectedPrompt?,
        earlier: ExpectedPrompt?,
    ) {
        if (waiting != null) {
            expected.compareAndSet(waiting, null)
            waiting.confirmed.complete(Unit)
        }
        if (earlier != null && earlier.failed && recent.remove(earlier)) {
            delivery(earlier.clientId, DeliveryState.DELIVERED)
        }
    }

    /** A phone prompt whose wait ended in the last minute with this text, in this pane. */
    private fun recentPrompt(
        paneId: String,
        prompt: String,
    ): ExpectedPrompt? {
        val now = nowMs()
        return recent.firstOrNull {
            it.paneId == paneId && now - it.finishedAtMs < RECENT_MS &&
                sameText(it.text, prompt)
        }
    }

    private suspend fun onDisplay(
        pane: PaneConversation,
        event: kotlinx.serialization.json.JsonObject,
        atMs: Long,
    ) {
        val messageId = event.str("message_id") ?: return
        val startsTurn = !pane.inTurn && pane.stopCheck == null
        pane.onDisplay(messageId, event.str("delta").orEmpty(), event.bool("final") == true, atMs)
        if (startsTurn) pane.state(AgentState.WORKING, WORKING_DETAIL)
        // The writing phase ends by itself once no batch came for a while.
        schedule(PaneConversation.WRITING_MS.milliseconds, Input.Refresh(pane.paneId))
    }

    private suspend fun onPreTool(
        pane: PaneConversation,
        event: kotlinx.serialization.json.JsonObject,
        atMs: Long,
    ) {
        val id = event.str("tool_use_id") ?: return
        if (!pane.inTurn) {
            pane.startTurn(atMs)
            pane.state(AgentState.WORKING, WORKING_DETAIL)
        }
        val input = event.obj("tool_input") ?: EMPTY_OBJECT
        pane.onPreTool(id, event.str("tool_name").orEmpty(), input, event.str("agent_type"))
        schedule(timing.orderWindow, Input.FlushCard(pane.paneId, id))
    }

    private suspend fun onPostTool(
        pane: PaneConversation,
        event: kotlinx.serialization.json.JsonObject,
        failed: Boolean,
    ) {
        val id = event.str("tool_use_id") ?: return
        val name = event.str("tool_name").orEmpty()
        val input = event.obj("tool_input") ?: EMPTY_OBJECT
        val output = PaneConversation.outputOf(name, event, failed)
        pane.onPostTool(id, name, input, event.str("agent_type"), output, failed)
        // The tool ran, so whoever answered, the desk did it first if a phone approval is still open.
        approvals.withdrawForTool(pane.paneId, id, name, input)
        refreshState(pane)
    }

    private suspend fun onBatch(
        pane: PaneConversation,
        event: kotlinx.serialization.json.JsonObject,
    ) {
        val calls = event.arr("tool_calls") ?: return
        val refused = pane.onBatch(calls)
        for (call in calls.mapNotNull { it as? kotlinx.serialization.json.JsonObject }) {
            val id = call.str("tool_use_id") ?: continue
            if (id in refused) approvals.withdrawForTool(pane.paneId, id, call.str("tool_name"), call.obj("tool_input"))
        }
    }

    private suspend fun onStop(
        pane: PaneConversation,
        lastAssistantMessage: String?,
    ) {
        approvals.withdrawForPane(pane.paneId)
        val generation = pane.endTurn(lastAssistantMessage)
        pane.state(AgentState.WAITING_ON_YOU, YOUR_TURN)
        if (generation != null) {
            schedule(timing.stopWait, Input.StopDue(pane.paneId, generation))
        } else {
            readRecord(pane)
        }
        drainQueue()
    }

    /**
     * The drift check (ADR 0013, "Risk"): the reply shown must be the agent's own final text. When batches
     * were missed, the record fills the gap, and failing that the `Stop` event's copy, with a note that Chat may
     * be incomplete. A record that disagrees with replies that did arrive whole is logged on your server, since
     * it means the record's shape changed and backfill would miss text.
     */
    private suspend fun checkStop(
        pane: PaneConversation,
        generation: Int,
    ) {
        val check = pane.stopCheck?.takeIf { it.generation == generation } ?: return
        pane.stopCheck = null
        val entries = readRecord(pane)
        val last = check.lastAssistantMessage
        val recordTexts = entries.filterIsInstance<RecordEntry.AssistantText>()
        if (pane.replyMatches(last, check.startedHere)) {
            val recordLast = recordTexts.lastOrNull()?.text
            if (recordLast != null && PaneConversation.normalize(recordLast) != PaneConversation.normalize(last)) {
                logger.warn("The session record of {} disagrees with the hooks. Backfill may miss text.", sessionId)
            }
            return
        }
        recordTexts.forEach { pane.fillReply(it.text, it.key) }
        if (!pane.replyMatches(last, check.startedHere)) {
            pane.fillReply(last)
            log.append(sessionId, EventPayload.ChatIncomplete(pane.threadId, REPLY_MISMATCH))
        }
    }

    private suspend fun raise(
        envelope: HookEnvelope,
        raised: CompletableDeferred<PendingPhoneApproval?>,
    ) {
        val event = envelope.event
        val pane = paneFor(envelope.pane)
        pane.lastHookAtMs = envelope.receivedAtMs
        val name = event.str("tool_name").orEmpty()
        val input = event.obj("tool_input") ?: EMPTY_OBJECT
        val toolCallId = pane.matchOpenTool(name, input)
        val approval =
            if (approvals.hasRoomFor(sessionId)) {
                approvals.build(sessionId, pane.paneId, name, input, toolCallId)
            } else {
                null
            }
        if (approval == null || raised.isCancelled) {
            raised.complete(null)
            return
        }
        if (toolCallId != null) pane.flush(toolCallId) else pane.pendingCard(approval.toolCallId, name, input)
        log.append(sessionId, approvalRequest(approval, nowMs() + timing.phoneDeadline.inWholeMilliseconds))
        approvals.hold(approval)
        pane.state(AgentState.WAITING_ON_YOU, APPROVAL_DETAIL)
        pane.progress(approvalOpen = true, nowMs())
        if (!raised.complete(approval)) approvals.settle(approval.id, ApprovalOutcome.ASK_AT_DESK)
    }

    /** Ends the pane's turn without a `Stop`: an error, an exit or an interrupt. */
    private suspend fun endTurn(
        pane: PaneConversation,
        state: AgentState,
        detail: String,
        outcome: ApprovalOutcome = ApprovalOutcome.WITHDRAWN,
    ) {
        approvals.withdrawForPane(pane.paneId, outcome)
        pane.endTurn(null)
        pane.backfill(readRecord(pane))
        pane.state(state, detail)
        pane.progress(approvalOpen = false, nowMs())
        drainQueue()
    }

    /** The pane's state and progress after something outside its hooks changed, such as an approval. */
    private suspend fun refreshState(pane: PaneConversation) {
        val approvalOpen = approvals.hasPendingFor(pane.paneId)
        if (pane.inTurn && !approvalOpen && pane.status?.status != AgentStatus.WAITING) {
            pane.state(AgentState.WORKING, WORKING_DETAIL)
        }
        pane.progress(approvalOpen, nowMs())
    }

    /**
     * What the agent's own status says that hooks do not: an Esc at the desk ends a turn with no `Stop`, a
     * dialog with no phone approval waits on you, and a process that died ends its turn.
     */
    @Suppress("CyclomaticComplexMethod") // One rule per status, each guarded by the same quiet check.
    private suspend fun onStatuses(found: Map<Long, AgentStatus>?) {
        polling = false
        if (found == null) return
        val now = nowMs()
        for (pane in panes.values) {
            val pid = pane.agentPid ?: continue
            val status = found[pid]
            pane.status = status
            val quiet = now - pane.lastHookAtMs > timing.quiet.inWholeMilliseconds
            val approvalOpen = approvals.hasPendingFor(pane.paneId)
            when {
                !quiet -> {
                    Unit
                }

                status == null && pane.inTurn -> {
                    endTurn(pane, AgentState.DONE, "Claude Code stopped.", ApprovalOutcome.ASK_AT_DESK)
                }

                status == null -> {
                    Unit
                }

                pane.inTurn && status.status == AgentStatus.IDLE && !approvalOpen -> {
                    endTurn(pane, AgentState.WAITING_ON_YOU, "Stopped.")
                }

                pane.inTurn && status.status == AgentStatus.WAITING && !approvalOpen -> {
                    pane.state(AgentState.WAITING_ON_YOU, deskWaiting(status))
                }

                pane.inTurn && status.status == AgentStatus.BUSY && !approvalOpen -> {
                    pane.state(AgentState.WORKING, WORKING_DETAIL)
                }

                !pane.inTurn && pane.stopCheck == null && status.status == AgentStatus.BUSY -> {
                    pane.startTurn(now)
                    pane.state(AgentState.WORKING, WORKING_DETAIL)
                }
            }
            pane.progress(approvals.hasPendingFor(pane.paneId), now)
        }
        drainQueue()
    }

    private fun onTick() {
        tickScheduled = false
        if (polling || !needsPoll()) return
        polling = true
        scope.launch { inbox.send(Input.Statuses(statuses.statuses())) }
    }

    private fun needsPoll(): Boolean =
        queue.isNotEmpty() || expected.get() != null ||
            panes.values.any { it.inTurn || it.status?.status == AgentStatus.WAITING }

    private fun schedulePollIfNeeded() {
        if (tickScheduled || !needsPoll()) return
        tickScheduled = true
        schedule(timing.poll, Input.Tick)
    }

    private suspend fun onFoundPanes(found: List<FoundPane>) {
        val byId = found.associateBy(FoundPane::paneId)
        for (pane in found) {
            val conversation = paneFor(pane.paneId)
            conversation.agentPid = conversation.hookPid?.takeIf { it in pane.candidates } ?: pane.agentPid
            if (pane.claudeSessionId != null) {
                bindRecord(conversation, pane.claudeSessionId, pane.recordPath?.toString(), null, null)
            }
        }
        for (conversation in panes.values.filter { it.agentPid != null && it.paneId !in byId }) {
            if (conversation.inTurn) {
                endTurn(conversation, AgentState.DONE, "Claude Code exited.", ApprovalOutcome.ASK_AT_DESK)
            }
            conversation.agentPid = null
        }
        promoteMain(found)
        updateMainTarget()
    }

    /** When the main pane has no Claude any more and another pane does, that pane becomes the main thread. */
    private fun promoteMain(found: List<FoundPane>) {
        val main = panes.values.firstOrNull { it.threadId == MAIN_THREAD }
        if (main?.agentPid != null) return
        val next =
            found.sortedByDescending(FoundPane::isCurrent).firstNotNullOfOrNull { panes[it.paneId] } ?: return
        main?.threadId = next.threadId
        next.threadId = MAIN_THREAD
    }

    private fun updateMainTarget() {
        val main = panes.values.firstOrNull { it.threadId == MAIN_THREAD }
        mainTarget = main?.agentPid?.let { PaneTarget(main.paneId, it) }
    }

    private suspend fun paneFor(paneId: String): PaneConversation {
        panes[paneId]?.let { return it }
        val main = panes.values.none { it.threadId == MAIN_THREAD }
        val threadId = if (main) MAIN_THREAD else "pane-" + nextWorker++
        val pane = PaneConversation(paneId, threadId) { log.append(sessionId, it) }
        pane.seed(log.recentAgentTexts(sessionId))
        panes[paneId] = pane
        if (!main) {
            log.append(
                sessionId,
                EventPayload.ThreadUpdated(
                    AgentThread(
                        id = threadId,
                        sessionId = sessionId,
                        parentThreadId = MAIN_THREAD,
                        kind = ThreadKind.WORKER,
                        title = "Claude in pane $paneId",
                        status = AgentState.DONE,
                    ),
                ),
            )
        }
        return pane
    }

    /**
     * Ties the pane to the agent session it now runs. A new session id means `/clear`, a resume, a fork or a
     * new process. The record is read from where the bridge stopped reading it last time, so a restart fills
     * exactly the gap. A record the bridge never read starts near its end (first attach), and a session that
     * just started has nothing to fill.
     */
    private suspend fun bindRecord(
        pane: PaneConversation,
        claudeSessionId: String?,
        recordPath: String?,
        source: String?,
        eventName: String?,
    ) {
        if (claudeSessionId == null || claudeSessionId == pane.claudeSessionId) return
        if (pane.inTurn && eventName == ClaudeHook.SESSION_START) pane.endTurn(null)
        pane.claudeSessionId = claudeSessionId
        val path = recordPath?.let { SessionRecord.safePath(it, recordRoot) } ?: return
        pane.recordPath = path
        val fresh = eventName == ClaudeHook.SESSION_START && source in NEW_RECORD_SOURCES
        val offset = withContext(ioDispatcher) { startOffset(path, fresh) }
        pane.recordOffset = offset
        withContext(ioDispatcher) { offsets.put(path, offset) }
        if (!fresh) pane.backfill(readRecord(pane))
    }

    /** Where reading [path] starts: where it stopped last time, at its end when it is new, or near its end. */
    private fun startOffset(
        path: Path,
        fresh: Boolean,
    ): Long = offsets.get(path) ?: if (fresh) SessionRecord.size(path) ?: 0 else SessionRecord.tailOffset(path) ?: 0

    /** Reads what the record gained since the last read, and remembers how far it got. */
    private suspend fun readRecord(pane: PaneConversation): List<RecordEntry> {
        val path = pane.recordPath ?: return emptyList()
        val chunk = withContext(ioDispatcher) { SessionRecord.read(path, pane.recordOffset ?: 0) }
        if (chunk != null) {
            pane.recordOffset = chunk.nextOffset
            withContext(ioDispatcher) { offsets.put(path, chunk.nextOffset) }
        }
        return chunk?.entries.orEmpty()
    }

    private suspend fun updatePermissionMode(
        pane: PaneConversation,
        mode: String,
    ) {
        pane.permissionMode = mode
        if (pane.threadId == MAIN_THREAD && permissionMode != mode) {
            permissionMode = mode
            log.session(sessionId)?.let { session ->
                val updated = present(session.copy(permissionMode = mode, terminal = true))
                log.append(sessionId, EventPayload.SessionUpdated(updated))
            }
        }
    }

    private suspend fun enqueue(
        text: String,
        clientId: String,
        deviceId: String?,
    ) {
        if (queue.size >= MAX_QUEUED) {
            throw SessionException.Conflict(
                SendRefusal.QUEUE_FULL,
                "Three messages are already waiting. Try again when the agent is free.",
            )
        }
        val queued = QueuedPrompt(text, clientId, nowMs())
        queue.add(queued)
        if (deviceId != null && registry != null) {
            queued.registration = registry.register(deviceId, QUEUED_KIND) { drop(queued, Refusal.REVOKED) }
        }
        delivery(clientId, DeliveryState.QUEUED)
        inbox.trySend(Input.Tick)
    }

    /** Takes [queued] out of the queue as not delivered, unless it already left it. */
    private suspend fun drop(
        queued: QueuedPrompt,
        refusal: Refusal,
    ) {
        if (!queue.remove(queued)) return
        queued.registration?.close()
        delivery(queued.clientId, DeliveryState.NOT_DELIVERED, refusal)
    }

    /** Types the waiting prompts, oldest first, once the agent is idle. Runs on its own, one drain at a time. */
    private fun drainQueue() {
        if (draining || queue.isEmpty()) return
        draining = true
        scope.launch {
            try {
                sendLock.withLock { deliverQueued() }
            } finally {
                inbox.trySend(Input.DrainDone)
            }
        }
    }

    private suspend fun deliverQueued() {
        while (deliverNext()) Unit
    }

    /** Delivers or settles the oldest waiting prompt. False when there is none, or it has to wait longer. */
    private suspend fun deliverNext(): Boolean {
        val next = queue.peek() ?: return false
        val target = mainTarget
        val expired = nowMs() - next.queuedAtMs > timing.queueMaxAge.inWholeMilliseconds
        val ready =
            when {
                expired -> Readiness.Refused(Refusal.EXPIRED)
                target == null -> Readiness.Refused(Refusal.NOT_RUNNING)
                else -> guard.readiness(target, approvals.hasPendingFor(target.paneId))
            }
        return when {
            ready == Readiness.Busy -> {
                false
            }

            // A dialog closes on its own. Anything else will not get better by waiting.
            ready is Readiness.Refused && ready.reason.code == Refusal.WAITING.code -> {
                false
            }

            ready is Readiness.Refused -> {
                drop(next, ready.reason)
                true
            }

            else -> {
                // A revoke may have taken it out of the queue a moment ago.
                if (queue.remove(next)) typeQueued(next, checkNotNull(target))
                true
            }
        }
    }

    private suspend fun typeQueued(
        next: QueuedPrompt,
        target: PaneTarget,
    ) {
        next.registration?.close()
        val expectation = ExpectedPrompt(target.paneId, next.text, next.clientId)
        val refusal = typeIt(target, expectation) {}
        if (refusal != null) {
            delivery(next.clientId, DeliveryState.NOT_DELIVERED, refusal)
        } else {
            confirm(expectation, next.clientId)
        }
    }

    private suspend fun typeIt(
        target: PaneTarget,
        expectation: ExpectedPrompt,
        beforeEnter: suspend () -> Unit,
    ): Refusal? {
        val refusal =
            guard.type(target, expectation.text, { approvals.hasPendingFor(target.paneId) }) {
                expected.set(expectation)
                beforeEnter()
            }
        if (refusal != null) expected.compareAndSet(expectation, null)
        return refusal
    }

    /**
     * Waits for a sign that the prompt landed: its `UserPromptSubmit`, or for a `!` line or a slash command,
     * which may start no turn, the input box clearing.
     */
    private suspend fun confirm(
        expectation: ExpectedPrompt,
        clientId: String,
    ) {
        val local = expectation.text.startsWith("!") || expectation.text.startsWith("/")
        val landed =
            withTimeoutOrNull(timing.confirmWait) {
                if (local) {
                    while (!expectation.confirmed.isCompleted) {
                        delay(timing.boxPoll)
                        if (guard.boxIsEmpty(expectation.paneId)) break
                    }
                } else {
                    expectation.confirmed.await()
                }
            } != null
        expected.compareAndSet(expectation, null)
        expectation.failed = !landed
        expectation.finishedAtMs = nowMs()
        recent.addLast(expectation)
        while (recent.size > MAX_RECENT) recent.pollFirst()
        if (landed) {
            // A shell line or a local command starts no turn, so no hook writes its message.
            if (expectation.logged.compareAndSet(false, true)) userMessage(expectation.text, clientId)
            delivery(clientId, DeliveryState.DELIVERED)
        } else {
            delivery(clientId, DeliveryState.NOT_DELIVERED, Refusal.NOT_CONFIRMED)
        }
    }

    private suspend fun userMessage(
        text: String,
        clientId: String,
    ) {
        log.append(
            sessionId,
            EventPayload.Message("user:$clientId", MAIN_THREAD, MessageRole.USER, listOf(ContentBlock.Text(text))),
        )
    }

    private suspend fun delivery(
        clientId: String,
        state: DeliveryState,
        refusal: Refusal? = null,
    ) {
        val payload = EventPayload.PromptDelivery(clientId, MAIN_THREAD, state, refusal?.code, refusal?.message)
        log.append(sessionId, payload)
    }

    private fun schedule(
        after: Duration,
        input: Input,
    ) {
        scope.launch {
            delay(after)
            inbox.trySend(input)
        }
    }

    private fun deskWaiting(status: AgentStatus) =
        status.waitingFor?.let { "Waiting at the desk: $it." } ?: "Waiting at the desk."

    companion object {
        const val MAIN_THREAD = "main"
        const val MAX_QUEUED = 3
        private const val QUEUED_KIND = "queued-prompt"

        private const val MAX_RECENT = 5
        private const val RECENT_MS = 60_000L

        /** The [EventPayload.ChatIncomplete] reason when the reply shown does not match the agent's own. */
        const val REPLY_MISMATCH = "reply_mismatch"
        private const val WORKING_DETAIL = "Working"
        private const val YOUR_TURN = "Your turn"
        private const val APPROVAL_DETAIL = "Waiting for your approval"
        private const val TASK_NOTICE = "<task-notification>"
        private val NEW_RECORD_SOURCES = setOf("startup", "clear")
        private val PASTED = Regex("^<pasted_content[^>]*>(.*)</pasted_content[^>]*>$", RegexOption.DOT_MATCHES_ALL)
        private val logger = LoggerFactory.getLogger(TmuxChatSession::class.java)

        /** The text inside the wrapper Claude Code puts around pasted text, or the prompt as it is. */
        fun unwrapPaste(prompt: String): String = PASTED.find(prompt.trim())?.groupValues?.get(1) ?: prompt

        /** Whether a prompt the agent received is the one the bridge typed. Claude turns a tab into spaces. */
        fun sameText(
            typed: String,
            received: String,
        ): Boolean = PaneConversation.normalize(received).contains(PaneConversation.normalize(typed))
    }
}
