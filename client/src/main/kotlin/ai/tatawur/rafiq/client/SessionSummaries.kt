package ai.tatawur.rafiq.client

import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.api.SessionSummary

/**
 * The session-list row for one `GET /sessions/summaries` entry. A summary does not say which thread an
 * approval's tool call is on, so its cards have no thread.
 */
fun SessionSummary.toListItem(): SessionListItem =
    SessionListItem(
        session = session,
        lastLine = lastLine,
        openApprovals = openApprovals.map { it.toCard(session.id, threadId = null) },
    )

/** The session list's order: newest update first. */
fun List<SessionSummary>.toListItems(): List<SessionListItem> =
    sortedByDescending { it.session.updatedAtMs }.map { it.toListItem() }

internal fun EventPayload.ApprovalRequest.toCard(
    sessionId: String,
    threadId: String?,
): ApprovalCard =
    ApprovalCard(
        id = id,
        sessionId = sessionId,
        threadId = threadId,
        toolCallId = toolCallId,
        description = description,
        options = ApprovalCard.ordered(options),
        expiresAtMs = expiresAtMs,
    )
