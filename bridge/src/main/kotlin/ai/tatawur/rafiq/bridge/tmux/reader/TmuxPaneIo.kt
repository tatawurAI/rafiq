package ai.tatawur.rafiq.bridge.tmux.reader

import ai.tatawur.rafiq.bridge.tmux.AgentDetector
import ai.tatawur.rafiq.bridge.tmux.PaneInfo
import ai.tatawur.rafiq.bridge.tmux.TmuxServer
import ai.tatawur.rafiq.bridge.tmux.cleanEnvironment
import ai.tatawur.rafiq.bridge.tmux.runCapturing
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * [PaneIo] on the bridge's own tmux server. Panes are addressed by pane id (`%3`), which tmux resolves on its
 * own, so no session or window name is parsed.
 *
 * Text goes in through a paste buffer with a name no one else uses: `load-buffer` reads it from standard
 * input, so its length is not limited by tmux's command line, `paste-buffer -p` types it as a bracketed paste,
 * so the agent sees it as text and never as key presses, and the buffer is deleted straight after.
 */
class TmuxPaneIo(
    private val server: TmuxServer,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val processes: suspend () -> Map<Int, Pair<Int, String>> = { AgentDetector.processTable() },
) : PaneIo {
    override suspend fun panes(): List<PaneInfo> = runCatching { server.panes() }.getOrDefault(emptyList())

    override suspend fun socketPath(): String? =
        tmux("list-sessions", "-F", "#{socket_path}")
            ?.takeIf { it.exit == 0 }
            ?.out
            ?.lineSequence()
            ?.firstOrNull(String::isNotBlank)

    override suspend fun agentPids(panes: List<PaneInfo>): Map<String, List<Long>> {
        if (panes.isEmpty()) return emptyMap()
        val table = processes()
        return panes.associate { pane ->
            pane.paneId to AgentDetector.claudeProcessesUnder(pane.panePid, table).map(Int::toLong)
        }
    }

    override suspend fun capture(paneId: String): String? =
        tmux("capture-pane", "-p", "-e", "-t", paneId)?.takeIf { it.exit == 0 }?.out

    override suspend fun paste(
        paneId: String,
        text: String,
    ): Boolean {
        val buffer = BUFFER_PREFIX + UUID.randomUUID().toString().replace("-", "")
        return try {
            loadBuffer(buffer, text) && tmux("paste-buffer", "-p", "-b", buffer, "-t", paneId)?.exit == 0
        } finally {
            // Even when the caller is cancelled, the text must not stay in a buffer anyone could paste.
            withContext(NonCancellable) { tmux("delete-buffer", "-b", buffer) }
        }
    }

    override suspend fun pressEnter(paneId: String): Boolean = tmux("send-keys", "-t", paneId, "Enter")?.exit == 0

    override suspend fun runsAgent(
        paneId: String,
        agentPid: Long,
    ): Boolean {
        val pane = panes().firstOrNull { it.paneId == paneId } ?: return false
        return descendsFrom(agentPid.toInt(), pane.panePid, processes())
    }

    /**
     * The agent is alive and descends from the pane's process. A pid that was reused by a process outside the
     * pane does not count.
     */
    private fun descendsFrom(
        pid: Int,
        root: Int,
        table: Map<Int, Pair<Int, String>>,
    ): Boolean {
        var current = pid
        var hops = 0
        while (hops++ < MAX_HOPS && current > 1 && current != root) current = table[current]?.first ?: 0
        return current == root && pid in table
    }

    private suspend fun tmux(vararg args: String) =
        withContext(io) { runCapturing(server.commandLine(*args), EXEC_TIMEOUT_SECONDS) }

    /** A tmux that cannot start or read is the same answer as one that fails: false. */
    private suspend fun loadBuffer(
        buffer: String,
        text: String,
    ): Boolean =
        withContext(io) {
            try {
                val process =
                    ProcessBuilder(server.commandLine("load-buffer", "-b", buffer, "-"))
                        .redirectErrorStream(true)
                        .also(::cleanEnvironment)
                        .start()
                try {
                    process.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    process.inputStream.readBytes()
                    process.waitFor(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0
                } finally {
                    process.destroyForcibly()
                }
            } catch (_: IOException) {
                false
            }
        }

    private companion object {
        const val BUFFER_PREFIX = "rafiq-send-"
        const val EXEC_TIMEOUT_SECONDS = 5L
        const val MAX_HOPS = 12
    }
}
