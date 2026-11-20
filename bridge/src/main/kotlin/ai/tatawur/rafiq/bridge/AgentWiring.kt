package ai.tatawur.rafiq.bridge

import ai.tatawur.rafiq.bridge.agent.AgentAdapter
import ai.tatawur.rafiq.bridge.agent.AgentSession
import ai.tatawur.rafiq.bridge.agent.AgentUnavailableException
import java.nio.file.Path

/**
 * Builds the adapter for one agent from `RAFIQ_AGENTS`. This is the single place that decides which
 * [AgentAdapter] class runs an agent, so connecting the ACP adapter means changing this function.
 *
 * Until then every configured agent gets an adapter that reports itself unavailable, so the bridge
 * starts, the agent is listed, and creating a session fails with a clear error.
 */
internal fun adapterFor(spec: AgentSpec): AgentAdapter = UnconnectedAdapter(spec.id, spec.command)

private class UnconnectedAdapter(
    override val agentId: String,
    private val command: List<String>,
) : AgentAdapter {
    override suspend fun start(
        sessionId: String,
        projectPath: Path,
    ): AgentSession = throw unavailable()

    override suspend fun resume(
        sessionId: String,
        projectPath: Path,
        agentSessionRef: String,
    ): AgentSession = throw unavailable()

    private fun unavailable() =
        AgentUnavailableException("No adapter is connected for agent '$agentId' (command: ${command.first()}).")
}
