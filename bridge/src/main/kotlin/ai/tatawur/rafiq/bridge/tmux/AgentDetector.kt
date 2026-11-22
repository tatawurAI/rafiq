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
    private val timeoutSeconds: Long = TIMEOUT_SECONDS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** The last answer of `claude agents --json`, kept so a poll every few seconds does not start it every time. */
    private class Cached(
        val atMs: Long,
        val live: Map<Int, String>?,
    )

    private var cached: Cached? = null

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
        val wanted = claudePids.values.flatten().toSet()
        val live = if (wanted.isNotEmpty()) liveClaudeSessions(wanted) else null
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

    /**
     * Live interactive Claude Code sessions as `claude agents --json` reports them, pid to folder. Null when
     * the command is missing, fails or hangs. The answer, a failure too, is kept for [CACHE_MS], and asked
     * again sooner only when [wanted] holds a process the kept answer does not know and the answer is
     * [RETRY_MS] old, so a Claude started a moment ago is found without asking on every poll.
     */
    private suspend fun liveClaudeSessions(wanted: Set<Int>): Map<Int, String>? {
        val kept = cached
        if (kept != null && stillFresh(kept, wanted)) return kept.live
        val live = askClaude()
        cached = Cached(nowMs(), live)
        return live
    }

    private fun stillFresh(
        kept: Cached,
        wanted: Set<Int>,
    ): Boolean {
        val age = nowMs() - kept.atMs
        val unknown = kept.live?.let { live -> wanted.any { it !in live } } ?: false
        return age < CACHE_MS && !(unknown && age >= RETRY_MS)
    }

    private suspend fun askClaude(): Map<Int, String>? =
        withContext(io) {
            val result = runCapturing(listOf(claudeCommand, "agents", "--json"), timeoutSeconds)
            result?.takeIf { it.exit == 0 }?.let { parseAgents(it.out) }
        }

    @Suppress("TooGenericExceptionCaught") // Output that is not the expected JSON means "ask the process tree".
    private fun parseAgents(text: String): Map<Int, String>? =
        try {
            (Json.parseToJsonElement(text) as JsonArray)
                .mapNotNull { entry ->
                    val o = entry as? JsonObject ?: return@mapNotNull null
                    val pid = o["pid"]?.jsonPrimitive?.int ?: return@mapNotNull null
                    pid to ((o["cwd"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty())
                }.toMap()
        } catch (_: Exception) {
            null
        }

    companion object {
        /** Parent pid and command name of each process on this machine, for finding an agent under a pane. */
        suspend fun processTable(io: CoroutineDispatcher = Dispatchers.IO): Map<Int, Pair<Int, String>> =
            withContext(io) {
                runCapturing(listOf("ps", "-axo", "pid=,ppid=,comm="), PS_TIMEOUT_SECONDS)
                    ?.out
                    .orEmpty()
                    .lines()
                    .mapNotNull(::parseProcess)
                    .toMap()
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
        private const val PS_TIMEOUT_SECONDS = 10L
        const val CLAUDE_CODE = "claude-code"
        const val TERMINAL = "terminal"
        private const val TIMEOUT_SECONDS = 10L
        private const val CACHE_MS = 20_000L
        private const val RETRY_MS = 5_000L
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
