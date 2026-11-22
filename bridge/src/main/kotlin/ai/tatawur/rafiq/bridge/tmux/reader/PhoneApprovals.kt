package ai.tatawur.rafiq.bridge.tmux.reader

import ai.tatawur.rafiq.bridge.hook.HookDecision
import ai.tatawur.rafiq.bridge.hook.HookReply
import ai.tatawur.rafiq.bridge.server.ConnectionRegistry
import ai.tatawur.rafiq.bridge.sessions.SessionException
import ai.tatawur.rafiq.protocol.ApprovalOption
import ai.tatawur.rafiq.protocol.ApprovalOptionKind
import ai.tatawur.rafiq.protocol.ApprovalOutcome
import ai.tatawur.rafiq.protocol.EventPayload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A permission request from a pane, held while a phone may answer it. */
@Suppress("LongParameterList") // A record of one request. Every field is read when it is shown or answered.
internal class PendingPhoneApproval(
    val id: String,
    val sessionId: String,
    val paneId: String,
    val toolName: String,
    val toolInput: JsonObject,
    val toolCallId: String,
    val rule: String?,
    /** The question, for an `AskUserQuestion` request. Its options are the answer labels. */
    val question: JsonObject?,
    val options: List<ApprovalOption>,
    val reply: CompletableDeferred<HookReply?>,
) {
    /** Registrations with the connection registry, closed when the approval settles. */
    val revocations = mutableListOf<AutoCloseable>()
}

/**
 * The approvals a phone can answer for tmux sessions (ADR 0013 "Approvals", guards 6 and 9, issue #51).
 *
 * Each approval settles exactly once, and whoever settles it first wins: you on a phone, the desk (the tool
 * ran, or the turn moved on), the deadline, the hook going away, or a revoked device. Removing the entry from
 * [pending] is the arbiter, so two of them racing cannot both write an [EventPayload.ApprovalResolved].
 *
 * Revocation: an approval is not tied to one phone. Every paired device can answer it, so revoking any of them
 * settles every pending approval as [ApprovalOutcome.ASK_AT_DESK]. The desk dialog stays open the whole time,
 * so nothing is lost but the card.
 *
 * @param devices the ids of the devices that are paired now, for the revocation rule.
 * @param onSettled runs after an approval settled, so the session can update its status.
 */
internal class PhoneApprovals(
    private val log: ReaderLog,
    private val registry: ConnectionRegistry?,
    private val devices: suspend () -> List<String>,
    private val onSettled: suspend (PendingPhoneApproval) -> Unit,
    private val maxPerSession: Int = MAX_PER_SESSION,
    private val maxTotal: Int = MAX_TOTAL,
) {
    private val pending = ConcurrentHashMap<String, PendingPhoneApproval>()

    fun owns(approvalId: String): Boolean = pending.containsKey(approvalId)

    fun hasPendingFor(paneId: String): Boolean = pending.values.any { it.paneId == paneId }

    fun pendingFor(paneId: String): List<PendingPhoneApproval> = pending.values.filter { it.paneId == paneId }

    /** False when another approval would go over a cap, in which case the request is asked at the desk. */
    fun hasRoomFor(sessionId: String): Boolean =
        pending.size < maxTotal && pending.values.count { it.sessionId == sessionId } < maxPerSession

    /**
     * Builds an approval for a request, or null when a phone cannot answer it: a question with several parts or
     * several choices goes to the desk.
     */
    fun build(
        sessionId: String,
        paneId: String,
        toolName: String,
        toolInput: JsonObject,
        toolCallId: String?,
        suggestions: kotlinx.serialization.json.JsonArray?,
    ): PendingPhoneApproval? {
        val id = "tap_" + UUID.randomUUID().toString().replace("-", "")
        val question = if (toolName == ASK_USER_QUESTION) singleQuestion(toolInput) else null
        val rule = sessionRuleFor(toolName, toolInput, suggestions)
        val options = if (toolName == ASK_USER_QUESTION) answerOptions(question) else toolOptions(rule)
        return options?.let {
            PendingPhoneApproval(
                id = id,
                sessionId = sessionId,
                paneId = paneId,
                toolName = toolName,
                toolInput = toolInput,
                toolCallId = toolCallId ?: "perm-$id",
                rule = rule,
                question = question,
                options = it,
                reply = CompletableDeferred(),
            )
        }
    }

    /** Starts holding [approval], after its request is in the log. */
    suspend fun hold(approval: PendingPhoneApproval) {
        pending[approval.id] = approval
        if (registry == null) return
        for (device in devices()) {
            approval.revocations +=
                registry.register(device, REGISTRY_KIND) { settle(approval.id, ApprovalOutcome.ASK_AT_DESK) }
        }
    }

    /**
     * Your answer from a phone.
     *
     * @throws SessionException.NotFound when no approval has this id.
     * @throws SessionException.Invalid when [optionId] is not one of its options.
     * @throws SessionException.Conflict when it settled while the answer was on its way.
     */
    suspend fun answer(
        approvalId: String,
        optionId: String,
    ) {
        val approval = pending[approvalId]
        // The settle runs only once the answer is known to be valid, and it is what can still lose a race.
        val refusal =
            when {
                approval == null -> {
                    SessionException.NotFound("approval_not_found", "No pending approval '$approvalId'.")
                }

                approval.options.none { it.id == optionId } -> {
                    SessionException.Invalid("invalid_option", "That is not one of the approval's options.")
                }

                !settle(approvalId, ApprovalOutcome.SELECTED, optionId, replyFor(approval, optionId)) -> {
                    SessionException.Conflict("approval_gone", "That approval is already resolved.")
                }

                else -> {
                    null
                }
            }
        if (refusal != null) throw refusal
    }

    /**
     * Settles [approvalId] once. [reply] goes to the waiting hook, and null tells it to ask at the desk.
     *
     * @return false when it had already settled.
     */
    suspend fun settle(
        approvalId: String,
        outcome: ApprovalOutcome,
        optionId: String? = null,
        reply: HookReply? = null,
    ): Boolean {
        val approval = pending.remove(approvalId) ?: return false
        approval.reply.complete(reply)
        approval.revocations.forEach(AutoCloseable::close)
        log.append(approval.sessionId, EventPayload.ApprovalResolved(approvalId, outcome, optionId))
        onSettled(approval)
        return true
    }

    /** The desk answered first: the tool ran or was refused. Matched by tool use id, or by name and input. */
    suspend fun withdrawForTool(
        paneId: String,
        toolUseId: String?,
        toolName: String?,
        toolInput: JsonObject?,
    ) {
        pending.values
            .filter {
                it.paneId == paneId &&
                    (it.toolCallId == toolUseId || (it.toolName == toolName && it.toolInput == toolInput))
            }.forEach { settle(it.id, ApprovalOutcome.WITHDRAWN) }
    }

    /** The agent in [paneId] stopped waiting: its turn ended, it was interrupted, or it exited. */
    suspend fun withdrawForPane(
        paneId: String,
        outcome: ApprovalOutcome = ApprovalOutcome.WITHDRAWN,
    ) {
        pendingFor(paneId).forEach { settle(it.id, outcome) }
    }

    companion object {
        const val MAX_PER_SESSION = 3
        const val MAX_TOTAL = 10
        private const val REGISTRY_KIND = "approval"
    }
}

private const val ALLOW = "allow"
private const val ALLOW_SESSION = "allow_session"
private const val DENY = "deny"
private const val ANSWER = "answer:"

/** The event that shows [approval] on the phone. */
internal fun approvalRequest(
    approval: PendingPhoneApproval,
    expiresAtMs: Long,
) = EventPayload.ApprovalRequest(
    id = approval.id,
    toolCallId = approval.toolCallId,
    description = approvalDescription(approval.toolName, approval.toolInput),
    options = approval.options,
    expiresAtMs = expiresAtMs,
    detail = approvalDetail(approval.toolName, approval.toolInput, approval.rule),
)

private fun toolOptions(rule: String?): List<ApprovalOption> =
    listOfNotNull(
        ApprovalOption(ALLOW, ApprovalOptionKind.ALLOW_ONCE, "Allow"),
        rule?.let { ApprovalOption(ALLOW_SESSION, ApprovalOptionKind.ALLOW_ALWAYS, "Allow for this session") },
        ApprovalOption(DENY, ApprovalOptionKind.DENY, "Deny"),
    )

/** One option per answer of a single question. Null for a question a phone cannot answer, which goes to the desk. */
private fun answerOptions(question: JsonObject?): List<ApprovalOption>? {
    val labels =
        question
            ?.takeIf { it.bool("multiSelect") != true }
            ?.arr("options")
            .orEmpty()
            .mapNotNull { (it as? JsonObject)?.str("label") }
    return labels.takeIf { it.isNotEmpty() }?.let { found ->
        found.mapIndexed { index, label -> ApprovalOption("$ANSWER$index", ApprovalOptionKind.ALLOW_ONCE, label) } +
            ApprovalOption(DENY, ApprovalOptionKind.DENY, "Skip the question")
    }
}

private fun replyFor(
    approval: PendingPhoneApproval,
    optionId: String,
): HookReply =
    when {
        optionId == DENY && approval.question != null -> {
            HookReply(HookDecision.DENY, message = "The question was skipped from the phone.")
        }

        optionId == DENY -> {
            HookReply(HookDecision.DENY, message = "Denied from the phone.")
        }

        optionId == ALLOW_SESSION -> {
            HookReply(HookDecision.ALLOW_SESSION, rule = approval.rule)
        }

        optionId.startsWith(ANSWER) -> {
            val label = approval.options.first { it.id == optionId }.label
            HookReply(HookDecision.ANSWERS, answers = mapOf(approval.question?.str("question").orEmpty() to label))
        }

        else -> {
            HookReply(HookDecision.ALLOW)
        }
    }
