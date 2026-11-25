package ai.tatawur.rafiq.client.push

import ai.tatawur.rafiq.client.ApprovalCard
import ai.tatawur.rafiq.client.ApprovalUnlock
import ai.tatawur.rafiq.client.CommandResult
import ai.tatawur.rafiq.client.Unlock
import ai.tatawur.rafiq.client.sync.BridgeApi
import ai.tatawur.rafiq.client.toCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the Approve screen shows. */
sealed interface ApproveState {
    data object Loading : ApproveState

    /**
     * The approval, read fresh from your server, with its full request.
     *
     * @property answering true while an answer is on its way; every option is disabled until it lands.
     */
    data class Ready(
        val sessionLabel: String,
        val approval: ApprovalCard,
        val answering: Boolean = false,
    ) : ApproveState

    /** Someone answered it first, or it went away. Nothing was sent. */
    data object AlreadyAnswered : ApproveState

    /** Your server could not be reached. Nothing was sent. */
    data object Unreachable : ApproveState

    /** Your server refused the answer, with its reason for a person. */
    data class Refused(
        val message: String,
    ) : ApproveState

    /** The answer reached your server. The screen closes. */
    data object Done : ApproveState

    /** The approval lock turned off because the phone's biometrics changed. Nothing was sent. */
    data object LockTurnedOff : ApproveState
}

/**
 * The Approve screen opened from a notification. It reads the approval from your server instead of trusting
 * what the notification held, so the request you confirm is the one your server will act on, shown in full.
 * Answers go through the approval lock (issue #14).
 *
 * @param lockOn reads the approval lock setting at the moment you answer.
 */
@Suppress("LongParameterList") // The screen's inputs: where to ask, what to ask about, and the lock.
class ApproveModel(
    private val api: BridgeApi,
    private val unlock: ApprovalUnlock,
    private val lockOn: suspend () -> Boolean,
    private val approvalId: String,
    private val sessionId: String,
    private val scope: CoroutineScope,
) {
    private val current = MutableStateFlow<ApproveState>(ApproveState.Loading)

    val state: StateFlow<ApproveState> = current.asStateFlow()

    /** Reads the approval from your server. */
    fun load() {
        current.value = ApproveState.Loading
        scope.launch {
            current.value =
                when (val result = api.summaries()) {
                    is CommandResult.Ok -> {
                        val summary = result.value.find { it.session.id == sessionId }
                        val request = summary?.openApprovals?.find { it.id == approvalId }
                        if (summary == null || request == null) {
                            ApproveState.AlreadyAnswered
                        } else {
                            ApproveState.Ready(summary.session.notificationLabel(), request.toCard(sessionId, null))
                        }
                    }

                    is CommandResult.Failed -> {
                        ApproveState.Refused(result.message)
                    }

                    else -> {
                        ApproveState.Unreachable
                    }
                }
        }
    }

    /**
     * Answers with [optionId], after the approval lock's check when it is on.
     *
     * @param askBiometric shows the strong biometric prompt and returns true when it passed.
     */
    fun answer(
        optionId: String,
        askBiometric: suspend () -> Boolean,
    ) {
        val ready = current.value as? ApproveState.Ready ?: return
        if (ready.answering) return
        current.value = ready.copy(answering = true)
        scope.launch {
            when (unlock.check(lockOn(), askBiometric)) {
                Unlock.REFUSED -> current.update { (it as? ApproveState.Ready)?.copy(answering = false) ?: it }
                Unlock.LOCK_TURNED_OFF -> current.value = ApproveState.LockTurnedOff
                Unlock.ALLOWED -> current.value = sent(api.approve(approvalId, optionId))
            }
        }
    }

    private fun sent(result: CommandResult<Unit>): ApproveState =
        when (result) {
            is CommandResult.Ok -> ApproveState.Done
            CommandResult.Gone, CommandResult.NotFound -> ApproveState.AlreadyAnswered
            is CommandResult.Failed -> ApproveState.Refused(result.message)
            else -> ApproveState.Unreachable
        }
}
