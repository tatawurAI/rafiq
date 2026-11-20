package ai.tatawur.rafiq.bridge.sessions

import ai.tatawur.rafiq.bridge.BridgeFixture
import ai.tatawur.rafiq.bridge.FakeAdapter
import ai.tatawur.rafiq.bridge.eventAfter
import ai.tatawur.rafiq.bridge.status
import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.EventPayload
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@Timeout(60)
class SessionManagerTest {
    @TempDir
    lateinit var dir: Path

    private val opened = mutableListOf<BridgeFixture>()

    @AfterEach
    fun closeFixtures() = opened.forEach(BridgeFixture::close)

    private fun fixture(adapter: FakeAdapter = FakeAdapter()) =
        BridgeFixture(dir.resolve("data"), adapter).also(opened::add)

    private fun project(): String = Files.createDirectory(dir.resolve("weather-app")).toString()

    @Test
    fun the_session_shows_working_while_its_main_thread_does_and_a_logging_failure_marks_it_errored() {
        val fixture = fixture()
        val session = runBlocking { fixture.sessions.create("fake", project(), null) }
        val agent = fixture.adapter.started.single()

        runBlocking {
            // Like real ACP, the agent reports thread-level status only.
            fixture.log.eventAfter(session.id, trigger = { agent.emit(status(AgentState.WORKING, "main")) }) {
                (it.payload as? EventPayload.StatusChanged)?.state == AgentState.WORKING
            }
            assertEquals(
                AgentState.WORKING,
                fixture.sessions
                    .sessions()
                    .single()
                    .status,
            )

            val died =
                fixture.log.eventAfter(session.id, trigger = { agent.breakWith(IllegalStateException("boom")) }) {
                    val change = it.payload as? EventPayload.StatusChanged
                    change?.state == AgentState.ERROR && change.threadId == null
                }

            assertEquals(
                AgentState.ERROR,
                fixture.sessions
                    .sessions()
                    .single()
                    .status,
            )
            assertEquals(
                AgentState.ERROR,
                fixture.sessions
                    .threads(session.id)
                    .single()
                    .status,
            )
            // The cause is logged on the server, never stored in the public event log.
            assertFalse("boom" in (died.payload as EventPayload.StatusChanged).detail.orEmpty())
        }
    }

    @Test
    fun threads_still_working_when_the_agent_exits_are_settled() {
        val fixture = fixture()
        val session = runBlocking { fixture.sessions.create("fake", project(), null) }
        val agent = fixture.adapter.started.single()

        runBlocking {
            fixture.log.eventAfter(session.id, trigger = { agent.emit(status(AgentState.WORKING, "main")) }) {
                (it.payload as? EventPayload.StatusChanged)?.state == AgentState.WORKING
            }
            val settled =
                fixture.log.eventAfter(session.id, trigger = { agent.exit() }) {
                    (it.payload as? EventPayload.StatusChanged)?.detail == "The agent exited."
                }

            assertEquals(AgentState.DONE, (settled.payload as EventPayload.StatusChanged).state)
            assertEquals(
                AgentState.DONE,
                fixture.sessions
                    .threads(session.id)
                    .single()
                    .status,
            )
            assertEquals(
                AgentState.DONE,
                fixture.sessions
                    .sessions()
                    .single()
                    .status,
            )
        }
    }

    @Test
    fun after_a_restart_work_shows_as_errored_and_a_prompt_resumes_with_the_stored_agent_handle() {
        val first = fixture()
        val session = runBlocking { first.sessions.create("fake", project(), null) }
        val agentRef =
            first.adapter.started
                .single()
                .agentSessionRef
        runBlocking {
            val agent = first.adapter.started.single()
            first.log.eventAfter(session.id, trigger = { agent.emit(status(AgentState.WORKING, "main")) }) {
                (it.payload as? EventPayload.StatusChanged)?.state == AgentState.WORKING
            }
            first.sessions.shutdown()
        }
        first.close()
        opened.remove(first)

        val second = fixture(FakeAdapter())
        runBlocking {
            second.sessions.recoverAfterRestart()
            assertEquals(
                AgentState.ERROR,
                second.sessions
                    .sessions()
                    .single()
                    .status,
            )

            second.sessions.prompt(session.id, threadId = null, text = "carry on")
        }

        assertEquals(listOf(agentRef), second.adapter.resumedRefs)
        assertEquals(
            listOf("main" to "carry on"),
            second.adapter.started
                .single()
                .prompts,
        )
    }
}
