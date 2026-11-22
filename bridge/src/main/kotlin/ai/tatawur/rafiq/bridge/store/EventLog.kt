package ai.tatawur.rafiq.bridge.store

import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.AgentThread
import ai.tatawur.rafiq.protocol.Event
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.Session
import ai.tatawur.rafiq.protocol.ThreadKind
import ai.tatawur.rafiq.protocol.api.SessionSummary
import ai.tatawur.rafiq.protocol.protocolJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** A stored session and the agent's own handle for it, which `AgentAdapter.resume` needs. */
data class StoredSession(
    val session: Session,
    val agentSessionRef: String,
)

/**
 * The bridge's SQLite store: sessions, threads and the append-only event log.
 *
 * Every event is stored with a `seq` that the log assigns, in the same transaction as the insert, so
 * `seq` is gapless and increases by one per session. The session and thread tables are projections
 * of the events, updated in that same transaction, so the lists the app shows never disagree with
 * the log.
 *
 * Everything runs on [dispatcher] over one connection, one task at a time. That is what makes
 * concurrent appends safe, and it is also the ordering [subscribe] relies on, so the dispatcher
 * must not run tasks in parallel.
 *
 * @param jdbcUrl the SQLite JDBC URL. Use [open] to build one from a data folder.
 * @param dispatcher runs all database work, one task at a time. Tests may pass their own.
 */
@Suppress("MagicNumber", "TooManyFunctions") // JDBC addresses columns by position; one class owns the one connection.
class EventLog(
    jdbcUrl: String,
    private val dispatcher: CoroutineDispatcher,
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    private val connection: Connection = DriverManager.getConnection(jdbcUrl)
    private val subscribers = ConcurrentHashMap<String, CopyOnWriteArrayList<Subscription>>()

    init {
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys = ON")
            statement.execute("PRAGMA journal_mode = WAL")
            // With WAL, NORMAL can lose the last few commits in a power cut but never corrupts the log,
            // and it saves an fsync on every event.
            statement.execute("PRAGMA synchronous = NORMAL")
            (SCHEMA + SummaryProjection.schema).forEach(statement::execute)
        }
        migrate()
    }

    /**
     * Brings a database written by an older bridge up to date. `user_version` counts the steps
     * applied: step 1 added the summary tables, which are rebuilt from the events once.
     */
    private fun migrate() {
        val version =
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { rows -> if (rows.next()) rows.getInt(1) else 0 }
            }
        if (version >= SCHEMA_VERSION) return
        transaction {
            SummaryProjection.rebuild(connection)
            connection.createStatement().use { it.execute("PRAGMA user_version = $SCHEMA_VERSION") }
        }
    }

    /**
     * Creates a session with its first two events, [EventPayload.SessionUpdated] and
     * [EventPayload.ThreadUpdated] for its main thread, in one transaction, so a session never exists
     * without its main thread.
     */
    suspend fun createSession(
        session: Session,
        agentSessionRef: String,
        mainThread: AgentThread,
    ) {
        onDispatcher {
            val events =
                transaction {
                    connection
                        .prepareStatement(
                            "INSERT INTO sessions(id, agent, project_path, title, status, agent_session_ref, " +
                                "created_at_ms, updated_at_ms, last_seq) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)",
                        ).use {
                            it.setString(1, session.id)
                            it.setString(2, session.agent)
                            it.setString(3, session.projectPath)
                            it.setString(4, session.title)
                            it.setString(5, wireName(session.status))
                            it.setString(6, agentSessionRef)
                            it.setLong(7, session.createdAtMs)
                            it.setLong(8, session.updatedAtMs)
                            it.executeUpdate()
                        }
                    listOf(
                        insertEvent(session.id, EventPayload.SessionUpdated(session)),
                        insertEvent(session.id, EventPayload.ThreadUpdated(mainThread)),
                    )
                }
            events.forEach(::publish)
        }
    }

    /** Records the handle the agent gave a resumed session, which can differ from the one it had before. */
    suspend fun updateAgentSessionRef(
        sessionId: String,
        agentSessionRef: String,
    ) {
        onDispatcher {
            transaction {
                connection.prepareStatement("UPDATE sessions SET agent_session_ref = ? WHERE id = ?").use {
                    it.setString(1, agentSessionRef)
                    it.setString(2, sessionId)
                    it.executeUpdate()
                }
            }
        }
    }

    /**
     * Appends [payload] to the session's log and applies it to the session and thread tables, in one
     * transaction. Live subscribers see the event before the next append starts, so they see events in
     * `seq` order.
     *
     * @throws IllegalArgumentException when the session does not exist.
     */
    suspend fun append(
        sessionId: String,
        payload: EventPayload,
    ): Event =
        onDispatcher {
            val event = transaction { insertEvent(sessionId, payload) }
            publish(event)
            event
        }

    /** Returns up to [limit] events of the session with `seq` greater than [afterSeq], in `seq` order. */
    suspend fun after(
        sessionId: String,
        afterSeq: Long,
        limit: Int,
    ): List<Event> =
        onDispatcher {
            connection
                .prepareStatement(
                    "SELECT session_id, seq, ts_ms, payload FROM events WHERE session_id = ? AND seq > ? " +
                        "ORDER BY seq LIMIT ?",
                ).use {
                    it.setString(1, sessionId)
                    it.setLong(2, afterSeq)
                    it.setInt(3, limit)
                    it.executeQuery().use { rows -> rows.collect(::readEvent) }
                }
        }

    /**
     * Starts delivering the session's new events to the returned [Subscription]. Events appended before
     * this call are not delivered, so a caller that wants history reads [after] once subscribed, and
     * drops delivered events it has already read.
     *
     * @param capacity how many events may wait unread before the subscription is closed as too slow.
     */
    suspend fun subscribe(
        sessionId: String,
        capacity: Int,
    ): Subscription =
        onDispatcher {
            val subscription =
                Subscription(Channel(capacity)) { closed ->
                    subscribers.computeIfPresent(sessionId) { _, list ->
                        list.remove(closed)
                        list.takeIf { it.isNotEmpty() }
                    }
                }
            subscribers.computeIfAbsent(sessionId) { CopyOnWriteArrayList() }.add(subscription)
            subscription
        }

    /** The ids of the session's approval requests that have no [EventPayload.ApprovalResolved] in its log. */
    suspend fun unresolvedApprovals(sessionId: String): List<String> =
        onDispatcher {
            connection
                .prepareStatement(
                    "SELECT json_extract(payload, '\$.id') FROM events WHERE session_id = ? " +
                        "AND json_extract(payload, '\$.type') = 'approval_request' " +
                        "AND json_extract(payload, '\$.id') NOT IN (SELECT json_extract(payload, '\$.id') " +
                        "FROM events WHERE session_id = ? " +
                        "AND json_extract(payload, '\$.type') = 'approval_resolved') " +
                        "ORDER BY seq",
                ).use {
                    it.setString(1, sessionId)
                    it.setString(2, sessionId)
                    it.executeQuery().use { rows -> rows.collect { row -> row.getString(1) } }
                }
        }

    /** The session and its agent handle, or null when no such session exists. */
    suspend fun session(sessionId: String): StoredSession? =
        onDispatcher {
            connection.prepareStatement("SELECT $SESSION_COLUMNS FROM sessions WHERE id = ?").use {
                it.setString(1, sessionId)
                it.executeQuery().use { rows -> rows.collect(::readSession).firstOrNull() }
            }
        }

    /** Every session, most recently updated first. */
    suspend fun sessions(): List<Session> =
        onDispatcher {
            connection.createStatement().use {
                it
                    .executeQuery("SELECT $SESSION_COLUMNS FROM sessions ORDER BY updated_at_ms DESC, id")
                    .use { rows -> rows.collect(::readSession).map(StoredSession::session) }
            }
        }

    /**
     * Every session with its newest `seq`, preview line and open approvals, most recently updated
     * first. Reads the summary tables kept by [SummaryProjection], never the events.
     */
    suspend fun summaries(): List<SessionSummary> =
        onDispatcher {
            val previews = SummaryProjection.previews(connection)
            val approvals = SummaryProjection.openApprovals(connection)
            connection.createStatement().use { statement ->
                statement
                    .executeQuery("SELECT $SESSION_COLUMNS, last_seq FROM sessions ORDER BY updated_at_ms DESC, id")
                    .use { rows ->
                        rows.collect { row ->
                            val session = readSession(row).session
                            SessionSummary(
                                session = session,
                                lastSeq = row.getLong(9),
                                lastLine = previews[session.id],
                                openApprovals = approvals[session.id].orEmpty(),
                            )
                        }
                    }
            }
        }

    /** The session's threads, oldest first. */
    suspend fun threads(sessionId: String): List<AgentThread> =
        onDispatcher {
            connection
                .prepareStatement(
                    "SELECT id, session_id, parent_thread_id, kind, title, status FROM threads " +
                        "WHERE session_id = ? ORDER BY rowid",
                ).use {
                    it.setString(1, sessionId)
                    it.executeQuery().use { rows -> rows.collect(::readThread) }
                }
        }

    override fun close() {
        subscribers.values.flatten().forEach(Subscription::close)
        connection.close()
    }

    private suspend fun <T> onDispatcher(block: () -> T): T = withContext(dispatcher) { block() }

    @Suppress("TooGenericExceptionCaught") // Rolled back and rethrown, never swallowed.
    private fun <T> transaction(block: () -> T): T {
        connection.autoCommit = false
        try {
            val result = block()
            connection.commit()
            return result
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    private fun insertEvent(
        sessionId: String,
        payload: EventPayload,
    ): Event {
        val now = clock.millis()
        val seq =
            connection.prepareStatement("SELECT last_seq FROM sessions WHERE id = ?").use {
                it.setString(1, sessionId)
                it.executeQuery().use { rows ->
                    require(rows.next()) { "No session with id '$sessionId'." }
                    rows.getLong(1) + 1
                }
            }
        connection.prepareStatement("UPDATE sessions SET last_seq = ?, updated_at_ms = ? WHERE id = ?").use {
            it.setLong(1, seq)
            it.setLong(2, now)
            it.setString(3, sessionId)
            it.executeUpdate()
        }
        connection.prepareStatement("INSERT INTO events(session_id, seq, ts_ms, payload) VALUES (?, ?, ?, ?)").use {
            it.setString(1, sessionId)
            it.setLong(2, seq)
            it.setLong(3, now)
            it.setString(4, protocolJson.encodeToString(EventPayload.serializer(), payload))
            it.executeUpdate()
        }
        project(sessionId, payload)
        SummaryProjection.apply(connection, sessionId, seq, payload)
        return Event(sessionId = sessionId, seq = seq, timestampMs = now, payload = payload)
    }

    /** Keeps the session and thread tables in step with the event just stored. */
    private fun project(
        sessionId: String,
        payload: EventPayload,
    ) {
        when (payload) {
            is EventPayload.SessionUpdated -> setSessionFields(sessionId, payload.session.title, payload.session.status)
            is EventPayload.ThreadUpdated -> upsertThread(sessionId, payload.thread)
            is EventPayload.StatusChanged -> setStatus(sessionId, payload.threadId, payload.state)
            else -> Unit
        }
    }

    private fun setSessionFields(
        sessionId: String,
        title: String,
        status: AgentState,
    ) {
        connection.prepareStatement("UPDATE sessions SET title = ?, status = ? WHERE id = ?").use {
            it.setString(1, title)
            it.setString(2, wireName(status))
            it.setString(3, sessionId)
            it.executeUpdate()
        }
    }

    private fun setStatus(
        sessionId: String,
        threadId: String?,
        state: AgentState,
    ) {
        val sql =
            if (threadId == null) {
                "UPDATE sessions SET status = ? WHERE id = ?"
            } else {
                "UPDATE threads SET status = ? WHERE session_id = ? AND id = ?"
            }
        connection.prepareStatement(sql).use {
            it.setString(1, wireName(state))
            it.setString(2, sessionId)
            if (threadId != null) it.setString(3, threadId)
            it.executeUpdate()
        }
        if (threadId != null) mirrorMainThreadStatus(sessionId, threadId, state)
    }

    /** The session's status is its main thread's status, because real agents report thread-level status only. */
    private fun mirrorMainThreadStatus(
        sessionId: String,
        threadId: String,
        state: AgentState,
    ) {
        connection
            .prepareStatement(
                "UPDATE sessions SET status = ? WHERE id = ? AND EXISTS " +
                    "(SELECT 1 FROM threads WHERE session_id = ? AND id = ? AND kind = ?)",
            ).use {
                it.setString(1, wireName(state))
                it.setString(2, sessionId)
                it.setString(3, sessionId)
                it.setString(4, threadId)
                it.setString(5, wireName(ThreadKind.MAIN))
                it.executeUpdate()
            }
    }

    private fun upsertThread(
        sessionId: String,
        thread: AgentThread,
    ) {
        connection
            .prepareStatement(
                "INSERT INTO threads(session_id, id, parent_thread_id, kind, title, status) " +
                    "VALUES (?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT(session_id, id) DO UPDATE SET parent_thread_id = excluded.parent_thread_id, " +
                    "kind = excluded.kind, title = excluded.title, status = excluded.status",
            ).use {
                it.setString(1, sessionId)
                it.setString(2, thread.id)
                it.setString(3, thread.parentThreadId)
                it.setString(4, wireName(thread.kind))
                it.setString(5, thread.title)
                it.setString(6, wireName(thread.status))
                it.executeUpdate()
            }
        mirrorMainThreadStatus(sessionId, thread.id, thread.status)
    }

    /** Hands [event] to each subscriber without ever waiting: one that has no room is closed as too slow. */
    private fun publish(event: Event) {
        subscribers[event.sessionId]?.forEach { it.offer(event) }
    }

    private fun readEvent(rows: ResultSet): Event =
        Event(
            sessionId = rows.getString(1),
            seq = rows.getLong(2),
            timestampMs = rows.getLong(3),
            payload = protocolJson.decodeFromString(EventPayload.serializer(), rows.getString(4)),
        )

    private fun readSession(rows: ResultSet): StoredSession =
        StoredSession(
            session =
                Session(
                    id = rows.getString(1),
                    agent = rows.getString(2),
                    projectPath = rows.getString(3),
                    title = rows.getString(4),
                    status = fromWireName(rows.getString(5)),
                    createdAtMs = rows.getLong(7),
                    updatedAtMs = rows.getLong(8),
                ),
            agentSessionRef = rows.getString(6),
        )

    private fun readThread(rows: ResultSet): AgentThread =
        AgentThread(
            id = rows.getString(1),
            sessionId = rows.getString(2),
            parentThreadId = rows.getString(3),
            kind = fromWireName<ThreadKind>(rows.getString(4)),
            title = rows.getString(5),
            status = fromWireName(rows.getString(6)),
        )

    companion object {
        /** The schema steps this build applies, see [migrate]. */
        private const val SCHEMA_VERSION = 1

        private const val SESSION_COLUMNS =
            "id, agent, project_path, title, status, agent_session_ref, created_at_ms, updated_at_ms"

        private val SCHEMA =
            listOf(
                """CREATE TABLE IF NOT EXISTS sessions (
                    id TEXT PRIMARY KEY,
                    agent TEXT NOT NULL,
                    project_path TEXT NOT NULL,
                    title TEXT NOT NULL,
                    status TEXT NOT NULL,
                    agent_session_ref TEXT NOT NULL,
                    created_at_ms INTEGER NOT NULL,
                    updated_at_ms INTEGER NOT NULL,
                    last_seq INTEGER NOT NULL
                )""",
                """CREATE TABLE IF NOT EXISTS threads (
                    session_id TEXT NOT NULL REFERENCES sessions(id),
                    id TEXT NOT NULL,
                    parent_thread_id TEXT,
                    kind TEXT NOT NULL,
                    title TEXT NOT NULL,
                    status TEXT NOT NULL,
                    PRIMARY KEY (session_id, id)
                )""",
                """CREATE TABLE IF NOT EXISTS events (
                    session_id TEXT NOT NULL REFERENCES sessions(id),
                    seq INTEGER NOT NULL,
                    ts_ms INTEGER NOT NULL,
                    payload TEXT NOT NULL,
                    PRIMARY KEY (session_id, seq)
                ) WITHOUT ROWID""",
            )

        /**
         * Opens the log in [dataDir], creating the folder and the `rafiq.db` file if needed. With no
         * folder it opens an in-memory database that is gone when the bridge stops.
         */
        fun open(
            dataDir: Path?,
            dispatcher: CoroutineDispatcher,
            clock: Clock = Clock.systemUTC(),
        ): EventLog {
            val url =
                if (dataDir == null) {
                    "jdbc:sqlite::memory:"
                } else {
                    val file = dataDir.resolve("rafiq.db")
                    createPrivate(dataDir, file)
                    "jdbc:sqlite:$file"
                }
            return EventLog(url, dispatcher, clock)
        }
    }
}

/**
 * Makes [dir] readable by this user only and creates [file] the same way. SQLite gives its `-wal` and
 * `-shm` files the database's mode.
 */
private fun createPrivate(
    dir: Path,
    file: Path,
) {
    if ("posix" !in dir.fileSystem.supportedFileAttributeViews()) {
        Files.createDirectories(dir)
        return
    }
    val folderMode = PosixFilePermissions.fromString("rwx------")
    val fileMode = PosixFilePermissions.fromString("rw-------")
    if (!Files.isDirectory(dir)) Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(folderMode))
    Files.setPosixFilePermissions(dir, folderMode)
    if (!Files.exists(file)) Files.createFile(file, PosixFilePermissions.asFileAttribute(fileMode))
    Files.setPosixFilePermissions(file, fileMode)
}

private fun <T> ResultSet.collect(read: (ResultSet) -> T): List<T> {
    val rows = mutableListOf<T>()
    while (next()) rows += read(this)
    return rows
}

/** The wire name of an enum value, so stored text never depends on the Kotlin constant's name. */
private inline fun <reified T : Enum<T>> wireName(value: T): String = protocolJson.encodeToString(value).trim('"')

private inline fun <reified T : Enum<T>> fromWireName(name: String): T = protocolJson.decodeFromString("\"$name\"")
