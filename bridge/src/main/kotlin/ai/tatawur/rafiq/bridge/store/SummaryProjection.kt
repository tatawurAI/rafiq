package ai.tatawur.rafiq.bridge.store

import ai.tatawur.rafiq.protocol.ContentBlock
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.MessageRole
import ai.tatawur.rafiq.protocol.protocolJson
import java.sql.Connection

/**
 * Keeps what `GET /sessions/summaries` reads in two small tables, so answering it costs two queries
 * however long the logs are. It is a projection of the events, written in the same transaction as
 * each event, like the session and thread tables.
 *
 * - `session_previews` holds the start of the newest agent or user message on the session's main
 *   thread: the message id and up to [PREVIEW_STORED_CHARS] characters, with deltas appended.
 * - `open_approvals` holds each approval request until its `approval_resolved` arrives.
 *
 * The caller owns the connection and the transaction.
 */
@Suppress("MagicNumber") // JDBC addresses parameters and columns by position.
internal object SummaryProjection {
    /** The tables. Created with the rest of the schema. */
    val schema =
        listOf(
            """CREATE TABLE IF NOT EXISTS session_previews (
                session_id TEXT PRIMARY KEY REFERENCES sessions(id),
                message_id TEXT NOT NULL,
                text TEXT NOT NULL
            )""",
            """CREATE TABLE IF NOT EXISTS open_approvals (
                session_id TEXT NOT NULL REFERENCES sessions(id),
                approval_id TEXT NOT NULL,
                seq INTEGER NOT NULL,
                payload TEXT NOT NULL,
                PRIMARY KEY (session_id, approval_id)
            )""",
        )

    /** Applies one event just stored as [seq] of [sessionId]. */
    fun apply(
        connection: Connection,
        sessionId: String,
        seq: Long,
        payload: EventPayload,
    ) {
        when (payload) {
            is EventPayload.Message -> preview(connection, sessionId, payload)
            is EventPayload.MessageDelta -> appendToPreview(connection, sessionId, payload)
            is EventPayload.ApprovalRequest -> openApproval(connection, sessionId, seq, payload)
            is EventPayload.ApprovalResolved -> closeApproval(connection, sessionId, payload.id)
            else -> Unit
        }
    }

    /**
     * Rebuilds both tables from the events, for a log written before they existed. Runs once, when
     * the database's `user_version` says the tables are new.
     */
    fun rebuild(connection: Connection) {
        connection.createStatement().use {
            it.execute("DELETE FROM session_previews")
            it.execute("DELETE FROM open_approvals")
        }
        connection
            .prepareStatement(
                "SELECT session_id, seq, payload FROM events WHERE json_extract(payload, '\$.type') IN " +
                    "('message', 'message_delta', 'approval_request', 'approval_resolved') ORDER BY session_id, seq",
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    while (rows.next()) {
                        val payload = protocolJson.decodeFromString(EventPayload.serializer(), rows.getString(3))
                        apply(connection, rows.getString(1), rows.getLong(2), payload)
                    }
                }
            }
    }

    /** The preview line of every session that has one, by session id. */
    fun previews(connection: Connection): Map<String, String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT session_id, text FROM session_previews").use { rows ->
                buildMap {
                    while (rows.next()) {
                        previewLine(
                            rows.getString(2),
                        ).takeIf(String::isNotEmpty)?.let { put(rows.getString(1), it) }
                    }
                }
            }
        }

    /** Every open approval, by session id, oldest first. */
    fun openApprovals(connection: Connection): Map<String, List<EventPayload.ApprovalRequest>> =
        connection.createStatement().use { statement ->
            val rows =
                statement.executeQuery("SELECT session_id, payload FROM open_approvals ORDER BY seq").use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1) to rows.getString(2)) }
                }
            rows
                .mapNotNull { (sessionId, payload) ->
                    val request = protocolJson.decodeFromString(EventPayload.serializer(), payload)
                    (request as? EventPayload.ApprovalRequest)?.let { sessionId to it }
                }.groupBy({ it.first }, { it.second })
        }

    private fun preview(
        connection: Connection,
        sessionId: String,
        message: EventPayload.Message,
    ) {
        // A plan is a checklist the agent keeps replacing, not something it said.
        if (message.role !in PREVIEW_ROLES || message.id.startsWith("plan:")) return
        val text = message.content.joinToString("") { it.text() }
        // A streaming message can start empty and fill from its deltas. A complete one with no text,
        // such as an image, leaves the preview as it was.
        if (text.isBlank() && !message.isStreaming) return
        connection
            .prepareStatement(
                "INSERT INTO session_previews(session_id, message_id, text) SELECT ?, ?, ? WHERE EXISTS " +
                    "(SELECT 1 FROM threads WHERE session_id = ? AND id = ? AND kind = 'main') " +
                    "ON CONFLICT(session_id) DO UPDATE SET message_id = excluded.message_id, text = excluded.text",
            ).use {
                it.setString(1, sessionId)
                it.setString(2, message.id)
                it.setString(3, text.take(PREVIEW_STORED_CHARS))
                it.setString(4, sessionId)
                it.setString(5, message.threadId)
                it.executeUpdate()
            }
    }

    private fun appendToPreview(
        connection: Connection,
        sessionId: String,
        delta: EventPayload.MessageDelta,
    ) {
        connection
            .prepareStatement(
                "UPDATE session_previews SET text = substr(text || ?, 1, $PREVIEW_STORED_CHARS) " +
                    "WHERE session_id = ? AND message_id = ? AND length(text) < $PREVIEW_STORED_CHARS",
            ).use {
                it.setString(1, delta.text)
                it.setString(2, sessionId)
                it.setString(3, delta.messageId)
                it.executeUpdate()
            }
    }

    private fun openApproval(
        connection: Connection,
        sessionId: String,
        seq: Long,
        request: EventPayload.ApprovalRequest,
    ) {
        connection
            .prepareStatement(
                "INSERT INTO open_approvals(session_id, approval_id, seq, payload) VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT(session_id, approval_id) DO UPDATE SET payload = excluded.payload",
            ).use {
                it.setString(1, sessionId)
                it.setString(2, request.id)
                it.setLong(3, seq)
                it.setString(4, protocolJson.encodeToString(EventPayload.serializer(), request))
                it.executeUpdate()
            }
    }

    private fun closeApproval(
        connection: Connection,
        sessionId: String,
        approvalId: String,
    ) {
        connection.prepareStatement("DELETE FROM open_approvals WHERE session_id = ? AND approval_id = ?").use {
            it.setString(1, sessionId)
            it.setString(2, approvalId)
            it.executeUpdate()
        }
    }
}

/**
 * The text a session row shows: whitespace runs, line breaks included, become one space, and the result
 * is cut to [PREVIEW_LINE_CHARS] characters with an ellipsis.
 */
internal fun previewLine(text: String): String {
    val oneLine = text.trim().replace(WHITESPACE, " ")
    if (oneLine.length <= PREVIEW_LINE_CHARS) return oneLine
    // Do not split a surrogate pair, which would leave half an emoji.
    val cut = if (oneLine[PREVIEW_LINE_CHARS - 1].isHighSurrogate()) PREVIEW_LINE_CHARS - 1 else PREVIEW_LINE_CHARS
    return oneLine.take(cut).trimEnd() + "…"
}

private fun ContentBlock.text(): String =
    when (this) {
        is ContentBlock.Text -> text
        is ContentBlock.Markdown -> markdown
        else -> ""
    }

private val PREVIEW_ROLES = setOf(MessageRole.AGENT, MessageRole.USER)
private val WHITESPACE = Regex("\\s+")

/** How much of the newest message is kept. More than the line shows, so collapsed whitespace still fills it. */
private const val PREVIEW_STORED_CHARS = 512

/** How long the preview line may be. */
internal const val PREVIEW_LINE_CHARS = 140
