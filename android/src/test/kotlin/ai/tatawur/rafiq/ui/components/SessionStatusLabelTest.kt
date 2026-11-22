package ai.tatawur.rafiq.ui.components

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.protocol.AgentState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SessionStatusLabelTest {
    // A new session is DONE before its first turn, and "Done" reads as if it already ran.
    @Test
    fun a_done_session_without_messages_reads_ready() {
        assertEquals(R.string.status_ready, sessionStatusLabel(AgentState.DONE, hasMessages = false))
        assertEquals(R.string.status_done, sessionStatusLabel(AgentState.DONE, hasMessages = true))
        assertEquals(R.string.status_working, sessionStatusLabel(AgentState.WORKING, hasMessages = false))
    }
}
