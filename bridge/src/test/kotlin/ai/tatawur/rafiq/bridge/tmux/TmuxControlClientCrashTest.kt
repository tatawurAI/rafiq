package ai.tatawur.rafiq.bridge.tmux

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/** Starts a view in its own JVM and holds it open, so a test can kill that JVM the hard way. */
object HoldView {
    @JvmStatic
    fun main(args: Array<String>) {
        val (socket, session, view) = args
        val server = TmuxServer(socket)
        val id = kotlinx.coroutines.runBlocking { server.session(session) }!!.id
        TmuxControlClient(server, id, view, TerminalLimits()) {}.start(60, 20)
        println("ready")
        Thread.sleep(Long.MAX_VALUE)
    }
}

@Timeout(120)
class TmuxControlClientCrashTest {
    private val tmux = TestTmux()
    private var holder: Process? = null

    @AfterEach
    fun cleanUp() {
        holder?.destroyForcibly()
        tmux.close()
    }

    @Test
    fun killing_the_bridge_process_leaves_no_view_session_and_no_tmux_client() {
        tmux.newSession("weather-app")
        val view = "rafiq-view-crashtest"
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process =
            ProcessBuilder(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                HoldView::class.java.name,
                tmux.socket,
                "weather-app",
                view,
            ).redirectErrorStream(true)
                .start()
                .also { holder = it }
        val ready = process.inputStream.bufferedReader().readLine()
        assertTrue(ready == "ready", "the holder did not attach: $ready")
        assertTrue(tmux.sessionNames().contains(view), "the view session exists while the bridge is alive")
        assertTrue(tmux.clientProcessExists(view), "a tmux -C client runs for the view")

        process.destroyForcibly().waitFor(10, TimeUnit.SECONDS)

        eventually("the view session is gone") { !tmux.sessionNames().contains(view) }
        eventually("the tmux -C client has exited") { !tmux.clientProcessExists(view) }
    }
}
