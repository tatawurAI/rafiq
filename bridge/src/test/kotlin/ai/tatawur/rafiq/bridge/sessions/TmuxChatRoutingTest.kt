package ai.tatawur.rafiq.bridge.sessions

import ai.tatawur.rafiq.bridge.FakeAdapter
import ai.tatawur.rafiq.bridge.store.EventLog
import ai.tatawur.rafiq.bridge.tmux.reader.TmuxChat
import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.AgentThread
import ai.tatawur.rafiq.protocol.Session
import ai.tatawur.rafiq.protocol.ThreadKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A tmux session's prompts and approvals go to its Chat, never to an agent adapter. */
class TmuxChatRoutingTest {
    private val log = EventLog.open(null, Dispatchers.IO.limitedParallelism(1))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val adapter = FakeAdapter("claude-code")
    private val chat = RecordingChat()
    private val sessions = SessionManager(log, listOf(adapter), scope, visibility = { true }, tmuxChat = chat)

    private class RecordingChat : TmuxChat {
        val prompts = mutableListOf<Triple<String, String, String?>>()
        val answers = mutableListOf<Pair<String, String>>()

        override suspend fun prompt(
            sessionId: String,
            text: String,
            clientId: String?,
        ) {
            prompts += Triple(sessionId, text, clientId)
        }

        override fun ownsApproval(approvalId: String) = approvalId.startsWith("tap_")

        override suspend fun respondToApproval(
            approvalId: String,
            optionId: String,
        ): String {
            answers += approvalId to optionId
            return ID
        }

        override fun permissionMode(sessionId: String) = "plan"
    }

    @AfterEach
    fun close() {
        scope.cancel()
        log.close()
    }

    private fun tmuxSession() =
        runBlocking {
            val session =
                Session(ID, "claude-code", "/work/weather-app", "weather-app", AgentState.DONE, 1, 1, terminal = true)
            log.createSession(
                session,
                "tmux",
                AgentThread("main", ID, null, ThreadKind.MAIN, "weather-app", AgentState.DONE),
            )
        }

    @Test
    fun a_prompt_goes_to_the_chat_with_its_client_id_and_starts_no_agent() {
        tmuxSession()

        runBlocking<Unit> { sessions.prompt(ID, null, "hello", "c-1") }

        assertEquals(listOf(Triple<String, String, String?>(ID, "hello", "c-1")), chat.prompts)
        assertTrue(adapter.started.isEmpty(), "an agent was started for a tmux session")
    }

    @Test
    fun a_prompt_to_a_helper_thread_is_refused_before_the_chat_sees_it() {
        tmuxSession()

        assertFailsWith<SessionException.NotFound> { runBlocking { sessions.prompt(ID, "pane-1", "hello", "c-1") } }
        assertTrue(chat.prompts.isEmpty())
    }

    @Test
    fun an_approval_the_chat_holds_is_answered_by_the_chat() {
        tmuxSession()

        val sessionId = runBlocking { sessions.respondToApproval("tap_1", "allow") }

        assertEquals(listOf("tap_1" to "allow"), chat.answers)
        assertEquals(ID, sessionId, "the audit line needs the approval's session")
    }

    @Test
    fun the_session_list_shows_the_permission_mode_the_chat_reports() {
        tmuxSession()

        assertEquals("plan", runBlocking { sessions.sessions() }.single().permissionMode)
        assertEquals("plan", runBlocking { sessions.summaries() }.single().session.permissionMode)
    }

    private companion object {
        const val ID = "tmux-weather-app"
    }
}
