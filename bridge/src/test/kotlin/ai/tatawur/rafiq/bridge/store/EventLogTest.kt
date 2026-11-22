package ai.tatawur.rafiq.bridge.store

import ai.tatawur.rafiq.bridge.TEST_SESSION
import ai.tatawur.rafiq.bridge.seed
import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.AgentThread
import ai.tatawur.rafiq.protocol.ApprovalOutcome
import ai.tatawur.rafiq.protocol.ContentBlock
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.MessageRole
import ai.tatawur.rafiq.protocol.ThreadKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

@Timeout(60)
class EventLogTest {
    @TempDir
    lateinit var dir: Path

    private val opened = mutableListOf<EventLog>()

    @AfterEach
    fun closeLogs() = opened.forEach(EventLog::close)

    private fun open() = EventLog.open(dir.resolve("data"), Dispatchers.IO.limitedParallelism(1)).also(opened::add)

    private fun delta(n: Int) = EventPayload.MessageDelta(messageId = "m", threadId = "main", text = "d$n")

    @Test
    fun concurrent_appends_get_every_seq_once_and_the_log_reads_back_in_order() =
        runBlocking {
            val log = open()
            log.seed()

            val returned =
                (1..4)
                    .map { writer ->
                        async(Dispatchers.Default) { (1..25).map { log.append("ses_1", delta(writer * 100 + it)).seq } }
                    }.awaitAll()
                    .flatten()

            // Seq 1 and 2 are the session and its main thread.
            assertEquals((3L..102L).toList(), returned.sorted())
            assertEquals((1L..102L).toList(), log.after("ses_1", 0, 1000).map { it.seq })
        }

    @Test
    fun events_sessions_and_threads_survive_a_restart_and_seq_carries_on() =
        runBlocking {
            val first = open()
            first.seed()
            val helper = AgentThread("sub-1", "ses_1", "main", ThreadKind.SUBAGENT, "Explore", AgentState.WORKING)
            first.append("ses_1", EventPayload.ThreadUpdated(helper))
            first.append("ses_1", delta(1))
            val written = first.after("ses_1", 0, 10)
            first.close()

            val second = open()

            assertEquals(written, second.after("ses_1", 0, 10))
            assertEquals(listOf("main", "sub-1"), second.threads("ses_1").map { it.id })
            assertEquals("ref", second.session("ses_1")?.agentSessionRef)
            assertEquals(5L, second.append("ses_1", delta(2)).seq)
            assertEquals(listOf(5L), second.after("ses_1", 4, 10).map { it.seq })
        }

    @Test
    fun a_working_main_thread_makes_the_session_working_and_a_helper_does_not() =
        runBlocking {
            val log = open()
            log.seed()
            val helper = AgentThread("sub-1", "ses_1", "main", ThreadKind.SUBAGENT, "Explore", AgentState.DONE)
            log.append("ses_1", EventPayload.ThreadUpdated(helper))

            log.append("ses_1", EventPayload.StatusChanged("sub-1", AgentState.WORKING))
            assertEquals(AgentState.DONE, log.sessions().single().status)
            log.append("ses_1", EventPayload.StatusChanged("main", AgentState.WORKING))

            assertEquals(AgentState.WORKING, log.sessions().single().status)
            assertEquals(TEST_SESSION.id, log.sessions().single().id)
        }

    @Test
    fun a_log_from_before_the_summary_tables_gets_its_summaries_rebuilt_on_open() =
        runBlocking {
            val old = open()
            old.seed()
            val reply =
                EventPayload.Message(
                    "m1",
                    "main",
                    MessageRole.AGENT,
                    listOf(ContentBlock.Markdown("Tests pass")),
                )
            old.append("ses_1", reply)
            old.append("ses_1", EventPayload.ApprovalRequest("apr_1", "tool_1", "Run tests", emptyList()))
            old.append("ses_1", EventPayload.ApprovalRequest("apr_2", "tool_2", "Edit file", emptyList()))
            old.append("ses_1", EventPayload.ApprovalResolved("apr_1", ApprovalOutcome.SELECTED, "allow"))
            old.close()
            opened.remove(old)
            // What a bridge without the summary tables left behind.
            DriverManager.getConnection("jdbc:sqlite:${dir.resolve("data/rafiq.db")}").use { db ->
                db.createStatement().use {
                    it.execute("DROP TABLE session_previews")
                    it.execute("DROP TABLE open_approvals")
                    it.execute("PRAGMA user_version = 0")
                }
            }

            val summary = open().summaries().single()

            assertEquals(6, summary.lastSeq)
            assertEquals("Tests pass", summary.lastLine)
            assertEquals(listOf("apr_2"), summary.openApprovals.map { it.id })
        }

    @Test
    fun the_data_folder_and_database_are_private_to_the_bridge_user() {
        val log = open()
        if ("posix" !in dir.fileSystem.supportedFileAttributeViews()) return

        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("data"))))
        assertEquals(
            "rw-------",
            PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("data/rafiq.db"))),
        )
        log.close()
    }
}
