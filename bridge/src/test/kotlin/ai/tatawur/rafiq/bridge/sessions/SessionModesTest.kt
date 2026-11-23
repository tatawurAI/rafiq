package ai.tatawur.rafiq.bridge.sessions

import ai.tatawur.rafiq.bridge.BridgeFixture
import ai.tatawur.rafiq.bridge.FakeAdapter
import ai.tatawur.rafiq.bridge.agent.AgentUnavailableException
import ai.tatawur.rafiq.bridge.claudeLikeModes
import ai.tatawur.rafiq.bridge.eventAfter
import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.AgentThread
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.Session
import ai.tatawur.rafiq.protocol.ThreadKind
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Permission modes (#33): the mode a session starts in, switching it, and keeping it across a resume. */
@Timeout(60)
class SessionModesTest {
    @TempDir
    lateinit var dir: Path

    private val opened = mutableListOf<BridgeFixture>()

    @AfterEach
    fun closeFixtures() = opened.forEach(BridgeFixture::close)

    private fun fixture(
        defaultModes: Map<String, String> = mapOf("fake" to "default"),
        current: String = "auto",
    ) = BridgeFixture(
        dir.resolve("data"),
        FakeAdapter(modes = claudeLikeModes(current)),
        defaultModes = defaultModes,
    ).also(opened::add)

    private val project: String by lazy { Files.createDirectory(dir.resolve("weather-app")).toString() }

    @Test
    fun a_session_from_the_app_starts_in_the_configured_default_whatever_the_agent_picked() =
        runBlocking {
            val fixture = fixture()

            val session = fixture.sessions.create("fake", project, null)

            assertEquals(
                listOf("default"),
                fixture.adapter.started
                    .single()
                    .modeChanges,
            )
            assertEquals("default", session.permissionMode)
            assertEquals(listOf("default", "acceptEdits", "plan", "auto"), session.availableModes.map { it.id })
            // The first event already carries the mode, so the app never shows the agent's own pick.
            val first =
                fixture.log
                    .after(session.id, 0, 1)
                    .single()
                    .payload as EventPayload.SessionUpdated
            assertEquals("default", first.session.permissionMode)
        }

    @Test
    fun a_mode_named_on_create_wins_over_the_default() =
        runBlocking {
            val fixture = fixture()

            val session = fixture.sessions.create("fake", project, null, modeId = "plan")

            assertEquals(
                listOf("plan"),
                fixture.adapter.started
                    .single()
                    .modeChanges,
            )
            assertEquals("plan", session.permissionMode)
        }

    @Test
    fun a_mode_the_agent_does_not_offer_is_refused_on_create_and_the_agent_is_stopped() =
        runBlocking {
            val fixture = fixture()

            val refused =
                assertThrows<SessionException.Invalid> { fixture.sessions.create("fake", project, null, "yolo") }

            assertEquals("unknown_mode", refused.code)
            assertTrue(
                fixture.adapter.started
                    .single()
                    .closed,
            )
            assertEquals(emptyList(), fixture.sessions.sessions())
        }

    @Test
    fun a_configured_default_the_agent_does_not_offer_fails_closed() =
        runBlocking {
            val fixture = fixture(defaultModes = mapOf("fake" to "ask-everything"))

            val refused = assertThrows<SessionException.Conflict> { fixture.sessions.create("fake", project, null) }

            assertEquals("default_mode_unavailable", refused.code)
            assertTrue(
                fixture.adapter.started
                    .single()
                    .closed,
            )
            assertEquals(emptyList(), fixture.sessions.sessions())
        }

    @Test
    fun switching_mode_logs_the_change_and_an_unknown_mode_is_refused() =
        runBlocking {
            val fixture = fixture()
            val session = fixture.sessions.create("fake", project, null)

            val logged =
                fixture.log.eventAfter(session.id, trigger = { fixture.sessions.setMode(session.id, "acceptEdits") }) {
                    it.payload is EventPayload.SessionUpdated
                }

            assertEquals("acceptEdits", (logged.payload as EventPayload.SessionUpdated).session.permissionMode)
            assertEquals(
                "acceptEdits",
                fixture.sessions
                    .sessions()
                    .single()
                    .permissionMode,
            )
            val unknown = assertThrows<SessionException.Invalid> { fixture.sessions.setMode(session.id, "yolo") }
            assertEquals("unknown_mode", unknown.code)
            assertEquals(
                listOf("default", "acceptEdits"),
                fixture.adapter.started
                    .single()
                    .modeChanges,
            )
        }

    @Test
    fun a_mode_the_agent_switches_to_on_its_own_is_logged() =
        runBlocking {
            val fixture = fixture()
            val session = fixture.sessions.create("fake", project, null, modeId = "plan")
            val agent = fixture.adapter.started.single()

            val logged =
                fixture.log.eventAfter(session.id, trigger = { agent.switchModeItself("default") }) {
                    it.payload is EventPayload.SessionUpdated
                }

            assertEquals("default", (logged.payload as EventPayload.SessionUpdated).session.permissionMode)
        }

    @Test
    fun a_resumed_session_gets_its_mode_back_before_the_prompt_reaches_the_agent() =
        runBlocking {
            val before = fixture()
            val session = before.sessions.create("fake", project, null)
            before.close()
            opened.remove(before)

            // After a restart the agent opens the resumed session in its own mode again.
            val after = fixture()
            after.sessions.prompt(session.id, null, "Add a forecast endpoint")

            val resumed = after.adapter.started.single()
            assertEquals(listOf("default"), resumed.modeChanges)
            assertEquals(1, resumed.prompts.size)
            assertEquals("default", resumed.modes.value.currentModeId)
        }

    @Test
    fun a_resumed_session_whose_mode_cannot_be_set_again_is_errored_and_gets_no_prompt() =
        runBlocking {
            val before = fixture()
            val session = before.sessions.create("fake", project, null)
            before.close()
            opened.remove(before)

            val after = fixture()
            after.adapter.onStart = { it.failure = AgentUnavailableException("The agent refused to change mode.") }
            val refused =
                assertThrows<SessionException.Conflict> {
                    after.sessions.prompt(session.id, null, "Add a forecast endpoint")
                }

            assertEquals("mode_not_restored", refused.code)
            val resumed = after.adapter.started.single()
            assertEquals(emptyList(), resumed.prompts)
            assertTrue(resumed.closed)
            assertEquals(
                AgentState.ERROR,
                after.sessions
                    .sessions()
                    .single()
                    .status,
            )
        }

    @Test
    fun a_tmux_session_has_no_mode_to_switch() =
        runBlocking {
            val fixture = BridgeFixture(dir.resolve("data"), visibility = { true }).also(opened::add)
            val id = "tmux-weather-app"
            fixture.log.createSession(
                Session(id, "claude-code", project, "weather-app", AgentState.DONE, 1, 1),
                "tmux",
                AgentThread("main", id, null, ThreadKind.MAIN, "weather-app", AgentState.DONE),
            )

            val refused = assertThrows<SessionException.Conflict> { fixture.sessions.setMode(id, "default") }

            assertEquals("mode_not_supported", refused.code)
        }
}
