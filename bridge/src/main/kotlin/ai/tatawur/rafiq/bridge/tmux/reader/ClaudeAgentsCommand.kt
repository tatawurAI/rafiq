package ai.tatawur.rafiq.bridge.tmux.reader

import ai.tatawur.rafiq.bridge.tmux.runCapturing
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Asks `claude agents --json`, Claude Code's documented list of live sessions, for each process's status. The
 * command takes about a sixth of a second, so callers ask only while it matters: during a turn, while a prompt
 * waits, and right before typing.
 */
class ClaudeAgentsCommand(
    private val command: String = "claude",
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutSeconds: Long = TIMEOUT_SECONDS,
) : AgentStatusSource {
    override suspend fun statuses(): Map<Long, AgentStatus>? =
        withContext(io) {
            runCapturing(listOf(command, "agents", "--json"), timeoutSeconds)
                ?.takeIf { it.exit == 0 }
                ?.let { parseAgents(it.out) }
        }

    companion object {
        private const val TIMEOUT_SECONDS = 10L

        /** The live agents in the command's output by pid. Null when the output is not the expected list. */
        fun parseAgents(text: String): Map<Long, AgentStatus>? {
            val list = runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return null
            return list
                .mapNotNull { element ->
                    val o = element as? JsonObject ?: return@mapNotNull null
                    val pid = o.long("pid") ?: return@mapNotNull null
                    AgentStatus(
                        pid = pid,
                        status = o.str("status") ?: "unknown",
                        waitingFor = o.str("waitingFor"),
                        sessionId = o.str("sessionId"),
                        cwd = o.str("cwd"),
                    )
                }.associateBy(AgentStatus::pid)
        }
    }
}
