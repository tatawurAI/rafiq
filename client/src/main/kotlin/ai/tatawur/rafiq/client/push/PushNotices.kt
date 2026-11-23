package ai.tatawur.rafiq.client.push

import ai.tatawur.rafiq.client.ApprovalCard
import ai.tatawur.rafiq.client.CommandResult
import ai.tatawur.rafiq.client.sync.BridgeApi
import ai.tatawur.rafiq.client.toCard
import ai.tatawur.rafiq.protocol.Session
import ai.tatawur.rafiq.protocol.api.PushKind
import ai.tatawur.rafiq.protocol.api.PushPayload
import ai.tatawur.rafiq.protocol.api.SessionSummary
import ai.tatawur.rafiq.protocol.protocolJson
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What the phone shows for one push. A push carries ids and a kind only (ADR 0010), so everything a
 * notification says comes from your server over the tailnet, never from the push itself.
 */
sealed interface PushNotice {
    /** An approval waits on you. The notification offers Deny, and Approve after you unlock the phone. */
    data class Approval(
        val session: Session,
        val approval: ApprovalCard,
    ) : PushNotice

    /** The agent finished its turn. The notification offers Reply. */
    data class TurnDone(
        val session: Session,
        val lastLine: String?,
    ) : PushNotice

    /** The session or one of its threads stopped with an error. */
    data class Failed(
        val session: Session,
    ) : PushNotice

    /**
     * Your server could not be reached to say more, so the notification says only that an agent needs you.
     *
     * @property approvalId the approval it stands for, so a later [Clear] still removes it. Null for other kinds.
     */
    data class Generic(
        val sessionId: String,
        val kind: PushKind,
        val approvalId: String?,
    ) : PushNotice

    /** The approval was answered somewhere, or went away. Its notification goes too. */
    data class Clear(
        val approvalId: String,
    ) : PushNotice

    /** Nothing to show. */
    data object Nothing : PushNotice
}

/**
 * Turns a push into a [PushNotice]. The push itself is not trusted: anyone who learns the endpoint and the
 * phone's public key can send one. So the phone ignores a push that did not decrypt, never shows anything
 * a push says, and shows a session or an approval only when your server lists it.
 *
 * @param isShowing true when the app is in the foreground on that session, which already shows what the push
 *   is about. Then nothing is shown, so you are not told twice.
 * @param fetchBudget how long to wait for your server before showing the generic notification. The UnifiedPush
 *   connector keeps the app's push service bound for five seconds after a push, and after that Android may
 *   freeze the process, so the fetch has to finish well inside that.
 */
class PushNotices(
    private val api: BridgeApi,
    private val isShowing: (sessionId: String) -> Boolean,
    private val fetchBudget: Duration = DEFAULT_FETCH_BUDGET,
) {
    /**
     * @param content the push's body after Web Push decryption.
     * @param decrypted false when the distributor could not decrypt it with this phone's key.
     */
    suspend fun noticeFor(
        content: ByteArray,
        decrypted: Boolean,
    ): PushNotice {
        val payload = if (decrypted) decode(content) else null
        return when (payload?.kind) {
            null, PushKind.UNKNOWN -> PushNotice.Nothing
            PushKind.APPROVAL_CLEARED -> payload.approvalId?.let(PushNotice::Clear) ?: PushNotice.Nothing
            PushKind.APPROVAL, PushKind.TURN_DONE, PushKind.ERROR -> fetched(payload)
        }
    }

    private suspend fun fetched(payload: PushPayload): PushNotice {
        if (isShowing(payload.sessionId)) return PushNotice.Nothing
        val generic = PushNotice.Generic(payload.sessionId, payload.kind, payload.approvalId)
        return when (val result = withTimeoutOrNull(fetchBudget) { api.summaries() }) {
            is CommandResult.Ok -> fromSummary(payload, result.value.find { it.session.id == payload.sessionId })

            // A phone your server no longer accepts shows nothing at all.
            CommandResult.Unauthorized -> PushNotice.Nothing

            // Too slow, offline, or any other failure.
            else -> generic
        }
    }

    private fun fromSummary(
        payload: PushPayload,
        summary: SessionSummary?,
    ): PushNotice {
        // Your server does not list the session for this phone, so there is nothing it could act on.
        if (summary == null) return PushNotice.Nothing
        return when (payload.kind) {
            PushKind.APPROVAL -> {
                val approvalId = payload.approvalId
                val request = summary.openApprovals.find { it.id == approvalId }
                when {
                    request != null -> PushNotice.Approval(summary.session, request.toCard(summary.session.id, null))

                    // Answered before the phone asked. Any notification already up for it goes too.
                    approvalId != null -> PushNotice.Clear(approvalId)

                    else -> PushNotice.Nothing
                }
            }

            PushKind.TURN_DONE -> {
                PushNotice.TurnDone(summary.session, summary.lastLine)
            }

            PushKind.ERROR -> {
                PushNotice.Failed(summary.session)
            }

            PushKind.APPROVAL_CLEARED, PushKind.UNKNOWN -> {
                PushNotice.Nothing
            }
        }
    }

    companion object {
        /** Four seconds: inside the connector's five-second binding, with room to post the notification. */
        val DEFAULT_FETCH_BUDGET: Duration = 4.seconds

        private fun decode(content: ByteArray): PushPayload? =
            try {
                protocolJson.decodeFromString(PushPayload.serializer(), content.toString(Charsets.UTF_8))
            } catch (_: IllegalArgumentException) {
                // SerializationException is an IllegalArgumentException.
                null
            }
    }
}

/** What a notification calls [this] session: its title, or its project folder's name when it has none. */
fun Session.notificationLabel(): String = title.ifBlank { projectPath.trimEnd('/').substringAfterLast('/') }
