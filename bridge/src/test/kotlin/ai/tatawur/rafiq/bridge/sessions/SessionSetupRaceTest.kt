package ai.tatawur.rafiq.bridge.sessions

import ai.tatawur.rafiq.bridge.BridgeFixture
import ai.tatawur.rafiq.bridge.FakeAdapter
import ai.tatawur.rafiq.bridge.FakeAgentSession
import ai.tatawur.rafiq.bridge.claudeLikeModes
import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.EventPayload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The moment between a new session being stored and it being live, which other requests can already see. */
@Timeout(60)
class SessionSetupRaceTest {
    @TempDir
    lateinit var dir: Path

    private val opened = mutableListOf<BridgeFixture>()
    private val stored = CompletableDeferred<String>()
    private val gate = CompletableDeferred<Unit>()

    @AfterEach
    fun closeFixtures() = opened.forEach(BridgeFixture::close)

    private fun fixture() =
        BridgeFixture(dir.resolve("data"), onStored = {
            stored.complete(it)
            gate.await()
        }).also(opened::add)

    private val project: String by lazy { Files.createDirectory(dir.resolve("weather-app")).toString() }

    @Test
    fun a_prompt_for_a_session_still_being_created_waits_and_reaches_the_same_agent() =
        runBlocking {
            val fixture = fixture()
            val create = async(Dispatchers.Default) { fixture.sessions.create("fake", project, null) }
            val sessionId = stored.await()

            // The session is in the log, so a phone can already send to it.
            val prompt = async(Dispatchers.Default) { fixture.sessions.prompt(sessionId, null, "Add a forecast") }
            // Only waiting shows that the prompt is held back, so the wait is short and only bounds the check.
            assertNull(withTimeoutOrNull(1.seconds) { prompt.await() })

            gate.complete(Unit)
            create.await()
            prompt.await()

            val agent = fixture.adapter.started.single()
            assertEquals(emptyList(), fixture.adapter.resumedRefs)
            assertEquals(1, agent.prompts.size)
        }

    @Test
    fun a_shutdown_while_a_session_is_being_created_stops_its_agent() =
        runBlocking {
            val fixture = fixture()
            val create = async(Dispatchers.Default) { runCatching { fixture.sessions.create("fake", project, null) } }
            val sessionId = stored.await()

            fixture.sessions.shutdown()
            // Stopped by the shutdown itself, not only once the create gets going again.
            assertTrue(
                fixture.adapter.started
                    .single()
                    .closed,
            )
            gate.complete(Unit)

            val refused = assertThrows<SessionException.Conflict> { create.await().getOrThrow() }
            assertEquals("bridge_stopping", refused.code)
            // The row was written before the shutdown, so it says it was interrupted, once, and is not left clean.
            val interrupted =
                fixture.log.after(sessionId, 0, 100).map { it.payload }.filter {
                    it is EventPayload.StatusChanged && it.threadId == null && it.state == AgentState.ERROR
                }
            assertEquals(1, interrupted.size)
            assertEquals(
                AgentState.ERROR,
                fixture.log
                    .session(sessionId)
                    ?.session
                    ?.status,
            )
        }

    @Test
    fun a_shutdown_before_a_new_session_is_stored_leaves_no_row() =
        runBlocking {
            val modeGate = CompletableDeferred<Unit>()
            val agent = CompletableDeferred<FakeAgentSession>()
            val adapter =
                FakeAdapter(modes = claudeLikeModes()).apply {
                    onStart = {
                        it.setModeGate = modeGate
                        agent.complete(it)
                    }
                }
            val fixture =
                BridgeFixture(dir.resolve("data"), adapter, defaultModes = mapOf("fake" to "default")).also(opened::add)
            val create = async(Dispatchers.Default) { runCatching { fixture.sessions.create("fake", project, null) } }
            // Held while the starting mode is set, before the row is written.
            agent.await().setModeEntered.await()

            fixture.sessions.shutdown()
            modeGate.complete(Unit)

            val refused = assertThrows<SessionException.Conflict> { create.await().getOrThrow() }
            assertEquals("bridge_stopping", refused.code)
            assertEquals(emptyList(), fixture.sessions.sessions())
        }
}
