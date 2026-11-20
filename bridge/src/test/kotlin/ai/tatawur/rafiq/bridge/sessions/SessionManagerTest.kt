package ai.tatawur.rafiq.bridge.sessions

import ai.tatawur.rafiq.bridge.BridgeFixture
import ai.tatawur.rafiq.bridge.FakeAdapter
import ai.tatawur.rafiq.bridge.eventAfter
import ai.tatawur.rafiq.bridge.status
import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.EventPayload
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionManagerTest {
    @TempDir
    lateinit var dir: Path

    private fun project(): String = Files.createDirectory(dir.resolve("weather-app")).toString()

    @Test
    fun a_session_whose_agent_process_exits_is_marked_errored() {
        val fixture = BridgeFixture(dir.resolve("data"))
        val session = runBlocking { fixture.sessions.create("fake", project(), null) }
        val agent = fixture.adapter.started.single()
        agent.emit(status(AgentState.WORKING, threadId = "main"))

        runBlocking {
            fixture.log.eventAfter(session.id, trigger = { agent.exit() }) {
                val change = it.payload as? EventPayload.StatusChanged
                change?.state == AgentState.ERROR && change.threadId == "main"
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
        }
        fixture.close()
    }

    @Test
    fun after_a_restart_work_shows_as_not_running_and_a_prompt_resumes_with_the_stored_agent_handle() {
        val first = BridgeFixture(dir.resolve("data"))
        val session = runBlocking { first.sessions.create("fake", project(), null) }
        val agentRef =
            first.adapter.started
                .single()
                .agentSessionRef
        runBlocking {
            val agent = first.adapter.started.single()
            first.log.eventAfter(session.id, trigger = { agent.emit(status(AgentState.WORKING)) }) {
                (it.payload as? EventPayload.StatusChanged)?.state == AgentState.WORKING
            }
            first.sessions.shutdown()
        }
        first.close()

        val second = BridgeFixture(dir.resolve("data"), FakeAdapter())
        runBlocking {
            second.sessions.recoverAfterRestart()
            assertEquals(
                AgentState.DONE,
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
        second.close()
    }
}
