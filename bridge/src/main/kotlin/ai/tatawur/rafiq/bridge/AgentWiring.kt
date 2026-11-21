package ai.tatawur.rafiq.bridge

import ai.tatawur.rafiq.bridge.agent.AgentAdapter
import ai.tatawur.rafiq.bridge.agent.acp.AcpAgentAdapter

/**
 * Builds the adapter for one agent from `RAFIQ_AGENTS`. This is the single place that decides which
 * [AgentAdapter] class runs an agent.
 *
 * Every configured agent is driven over the Agent Client Protocol (ACP): `spec.command` starts the
 * agent's ACP adapter, for example `claude-agent-acp`. An agent that speaks something else gets its
 * own adapter class, chosen here.
 */
internal fun adapterFor(spec: AgentSpec): AgentAdapter =
    AcpAgentAdapter(agentId = spec.id, command = spec.command, clientVersion = bridgeVersion())
