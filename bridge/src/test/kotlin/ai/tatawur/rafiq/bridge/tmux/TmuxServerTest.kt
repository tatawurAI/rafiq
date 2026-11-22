package ai.tatawur.rafiq.bridge.tmux

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals

@Timeout(60)
class TmuxServerTest {
    private val tmux = TestTmux()
    private val holders = mutableListOf<Process>()

    @AfterEach
    fun cleanUp() {
        holders.forEach { it.destroyForcibly() }
        tmux.close()
    }

    @Test
    fun only_a_view_session_nobody_is_attached_to_is_killed_at_start() {
        tmux.newSession("base")
        // A view of another bridge that is running: it has a client attached.
        holders +=
            ProcessBuilder(
                "tmux",
                "-L",
                tmux.socket,
                "-C",
                "new-session",
                "-t",
                "base",
                "-s",
                "rafiq-view-aaaaaa-bbbbbbbb",
            ).redirectErrorStream(true)
                .also { it.environment().remove("TMUX") }
                .start()
        eventually("the live view is attached") { "rafiq-view-aaaaaa-bbbbbbbb" in tmux.sessionNames() }
        // A leftover of a bridge that died: no client. And two sessions of your own with similar names.
        tmux.tmux("new-session", "-d", "-t", "base", "-s", "rafiq-view-cccccc-dddddddd")
        tmux.newSession("rafiq-view-mine")
        tmux.newSession("rafiq-view-")

        runBlocking { tmux.server.killLeftoverViews() }

        assertEquals(
            setOf("base", "rafiq-view-aaaaaa-bbbbbbbb", "rafiq-view-mine", "rafiq-view-"),
            tmux.sessionNames().toSet(),
        )
    }
}
