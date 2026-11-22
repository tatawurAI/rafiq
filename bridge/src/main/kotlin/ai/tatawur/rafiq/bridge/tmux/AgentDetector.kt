package ai.tatawur.rafiq.bridge.tmux

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.TimeUnit

/** What the bridge shows for a tmux session: the agent running in it and the folder it works in. */
data class DetectedAgent(
    /** The id the session list shows: `claude-code`, or `terminal` when no agent is found. */
    val agent: String,
    val projectPath: String?,
)

/**
 * Finds out which agent runs in a tmux session without any hook installed in the project. It looks at the
 * pane's process tree for a `claude` process, and when `claude agents --json` works, asks it too, so a
 * process that is not a live interactive session does not count. Where the command is missing or fails,
 * the process tree alone decides.
 */
class AgentDetector(
    private val claudeCommand: String = "claude",
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * The agent of each session in [panes], by session name. A session with no agent is `terminal`.
     *
     * @param processes pid to parent pid and command name, from [processTable].
     */
    suspend fun detect(
        panes: List<PaneInfo>,
        processes: Map<Int, Pair<Int, String>>,
    ): Map<String, DetectedAgent> {
        val claudePids = panes.associateWith { claudeProcessesUnder(it.panePid, processes) }
        val live = if (claudePids.values.any { it.isNotEmpty() }) liveClaudeSessions() else null
        return panes.groupBy { it.sessionName }.mapValues { (_, group) ->
            val found =
                group.firstNotNullOfOrNull { pane ->
                    claudePids.getValue(pane).firstOrNull { live == null || it in live }?.let { pane to it }
                }
            if (found != null) {
                DetectedAgent(CLAUDE_CODE, live?.get(found.second)?.takeIf(String::isNotEmpty) ?: found.first.path)
            } else {
                DetectedAgent(TERMINAL, (group.firstOrNull(PaneInfo::isCurrent) ?: group.first()).path)
            }
        }
    }

    /** Live interactive Claude Code sessions as `claude agents --json` reports them, pid to folder. Null on failure. */
    @Suppress("TooGenericExceptionCaught") // A missing or failing claude program means "ask the process tree".
    private suspend fun liveClaudeSessions(): Map<Int, String>? =
        withContext(io) {
            try {
                val process = ProcessBuilder(claudeCommand, "agents", "--json").start()
                process.outputStream.close()
                val text = process.inputStream.readBytes().toString(Charsets.UTF_8)
                process.errorStream.readBytes()
                if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
                (Json.parseToJsonElement(text) as JsonArray)
                    .mapNotNull { entry ->
                        val o = entry as? JsonObject ?: return@mapNotNull null
                        val pid = o["pid"]?.jsonPrimitive?.int ?: return@mapNotNull null
                        pid to ((o["cwd"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty())
                    }.toMap()
            } catch (_: Exception) {
                null
            }
        }

    companion object {
        /** Parent pid and command name of each process on this machine, for finding an agent under a pane. */
        @Suppress("TooGenericExceptionCaught") // No process list means no agent is found, not a failed poll.
        suspend fun processTable(io: CoroutineDispatcher = Dispatchers.IO): Map<Int, Pair<Int, String>> =
            withContext(io) {
                try {
                    val process =
                        ProcessBuilder(
                            "ps",
                            "-axo",
                            "pid=,ppid=,comm=",
                        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                    process.outputStream.close()
                    val text = process.inputStream.readBytes().toString(Charsets.UTF_8)
                    process.waitFor()
                    text.lines().mapNotNull(::parseProcess).toMap()
                } catch (_: Exception) {
                    emptyMap()
                }
            }

        private fun parseProcess(line: String): Pair<Int, Pair<Int, String>>? {
            val parts = line.trim().split(Regex("\\s+"), limit = PROCESS_FIELDS)
            val pid = parts.firstOrNull()?.toIntOrNull()
            val ppid = parts.getOrNull(1)?.toIntOrNull()
            return if (pid != null && ppid != null && parts.size == PROCESS_FIELDS) {
                pid to (ppid to parts[2].substringAfterLast('/'))
            } else {
                null
            }
        }

        private const val PROCESS_FIELDS = 3
        const val CLAUDE_CODE = "claude-code"
        const val TERMINAL = "terminal"
        private const val TIMEOUT_SECONDS = 10L
        private const val MAX_HOPS = 12

        /** The pids of processes named `claude` that descend from [rootPid]. */
        fun claudeProcessesUnder(
            rootPid: Int,
            table: Map<Int, Pair<Int, String>>,
        ): List<Int> =
            table.entries
                .filter { it.value.second == "claude" }
                .map { it.key }
                .filter { pid ->
                    var current = pid
                    var hops = 0
                    while (hops++ < MAX_HOPS && current > 1) {
                        if (current == rootPid) return@filter true
                        current = table[current]?.first ?: return@filter false
                    }
                    false
                }
    }
}
