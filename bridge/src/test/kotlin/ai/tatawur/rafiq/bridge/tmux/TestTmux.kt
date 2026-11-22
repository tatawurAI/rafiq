package ai.tatawur.rafiq.bridge.tmux

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * A tmux server on a throwaway socket, so no test ever touches the default server. The socket is removed
 * with `kill-server` when the test ends.
 */
class TestTmux : AutoCloseable {
    val socket = "rafiq-test-" + UUID.randomUUID().toString().take(8)
    val server = TmuxServer(socket)
    private val desks = mutableListOf<Process>()

    init {
        val version = runCatching { process("tmux", "-V").text() }.getOrNull()
        val ok =
            version != null &&
                Regex("(\\d+)\\.(\\d+)").find(version)?.let {
                    it.groupValues[1].toInt() * 1000 + it.groupValues[2].toInt() >= MINIMUM
                } == true
        // CI must have tmux. A silent skip there would hide every one of these tests.
        check(ok || System.getenv("CI") == null) { "These tests need tmux 3.2 or newer, and CI has none." }
        assumeTrue(ok, "tmux 3.2 or newer is not installed")
    }

    /** Runs a tmux command on the throwaway server and returns what it printed. */
    fun tmux(vararg args: String): String {
        val process = process("tmux", "-L", socket, *args)
        val out = process.text()
        check(process.waitFor() == 0) { "tmux ${args.joinToString(" ")} failed: $out" }
        return out
    }

    /** Creates a detached session running [command] in a window of the given size. */
    fun newSession(
        name: String,
        command: String = "cat",
        cols: Int = 120,
        rows: Int = 40,
    ) {
        tmux("new-session", "-d", "-s", name, "-x", "$cols", "-y", "$rows", command)
    }

    fun sessionNames(): List<String> =
        runCatching { tmux("list-sessions", "-F", "#{session_name}").lines().filter(String::isNotBlank) }
            .getOrDefault(emptyList())

    fun windowSize(session: String): Pair<Int, Int> {
        val parts =
            tmux(
                "display-message",
                "-p",
                "-t",
                "=$session:",
                "#{window_width} #{window_height}",
            ).trim().split(' ')
        return parts[0].toInt() to parts[1].toInt()
    }

    fun capture(session: String): String = tmux("capture-pane", "-p", "-t", "=$session:")

    /**
     * A stand-in for the desk: a control-mode client with a fixed size, attached to [session]. It is a second
     * client for `window-size latest` to fall back to when the phone leaves.
     */
    fun attachDesk(
        session: String,
        cols: Int,
        rows: Int,
    ): Desk {
        val process =
            ProcessBuilder("tmux", "-L", socket, "-C", "attach", "-t", "=$session").redirectErrorStream(true).start()
        desks += process
        val desk = Desk(process, session)
        desk.send("refresh-client -C ${cols}x$rows")
        // Drain its output so it never blocks on a full pipe.
        Thread { process.inputStream.readAllBytes() }.apply { isDaemon = true }.start()
        return desk
    }

    /** The desk's client. [typeSomething] makes it the client `window-size latest` follows, as a keystroke does. */
    class Desk(
        private val process: Process,
        private val session: String,
    ) {
        fun send(command: String) {
            process.outputStream.write("$command\n".toByteArray())
            process.outputStream.flush()
        }

        fun typeSomething() = send("switch-client -t '=$session'")
    }

    /** True while some process still runs a `tmux -C` client whose command line names [viewName]. */
    fun clientProcessExists(viewName: String): Boolean =
        process("ps", "-axo", "command=").text().lines().any {
            it.contains("tmux") && it.contains(" -C ") &&
                viewName in it
        }

    override fun close() {
        desks.forEach { it.destroyForcibly() }
        runCatching { process("tmux", "-L", socket, "kill-server").waitFor(5, TimeUnit.SECONDS) }
        // A server that ended on its own can leave its socket file behind. This one is ours to remove.
        runCatching {
            val uid = process("id", "-u").text().trim()
            val base = System.getenv("TMUX_TMPDIR") ?: "/tmp"
            Files.deleteIfExists(Path.of(base, "tmux-$uid", socket))
        }
    }

    private fun process(vararg command: String): Process =
        ProcessBuilder(*command).redirectErrorStream(true).also { it.environment().remove("TMUX") }.start()

    private fun Process.text(): String = inputStream.readBytes().toString(Charsets.UTF_8)

    private companion object {
        const val MINIMUM = 3 * 1000 + 2
    }
}

/**
 * Polls [check] until it holds, or fails after [seconds] with [message]. For things tmux does a moment after
 * a command.
 */
fun eventually(
    message: () -> String,
    seconds: Long = 15,
    check: () -> Boolean,
) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
    while (System.nanoTime() < deadline) {
        if (check()) return
        Thread.sleep(POLL_MS)
    }
    throw AssertionError("Gave up waiting: ${message()}")
}

fun eventually(
    message: String,
    seconds: Long = 15,
    check: () -> Boolean,
) = eventually({ message }, seconds, check)

/** Like [eventually], for a value: says what it last saw when it gives up. */
fun <T> eventuallyEquals(
    message: String,
    expected: T,
    seconds: Long = 15,
    actual: () -> T,
) {
    var last: T? = null
    eventually({ "$message. Expected $expected, last saw $last." }, seconds) {
        last = actual()
        last == expected
    }
}

private const val POLL_MS = 50L
