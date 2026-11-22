package ai.tatawur.rafiq.bridge

import ai.tatawur.rafiq.bridge.agent.AgentAdapter
import ai.tatawur.rafiq.bridge.agent.acp.AcpAgentAdapter
import ai.tatawur.rafiq.bridge.hook.HookSetup
import ai.tatawur.rafiq.bridge.server.ConnectionRegistry
import ai.tatawur.rafiq.bridge.store.EventLog
import ai.tatawur.rafiq.bridge.tmux.TerminalService
import ai.tatawur.rafiq.bridge.tmux.reader.ClaudeAgentsCommand
import ai.tatawur.rafiq.bridge.tmux.reader.ClaudeReader
import ai.tatawur.rafiq.bridge.tmux.reader.EventLogReaderLog
import ai.tatawur.rafiq.bridge.tmux.reader.ReaderOffsets
import ai.tatawur.rafiq.bridge.tmux.reader.TmuxPaneIo
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Path

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

/**
 * Builds the Claude Code reader for tmux sessions, or null when Terminal is off, in which case no tmux session
 * exists for it to read. The reader follows the tmux source's scans, so call this before the source starts.
 *
 * @param devices the ids of the paired devices, so revoking one releases the approvals waiting on a phone.
 * @param hooksInstalled whether a project has the Rafiq hooks.
 */
@Suppress("LongParameterList") // The reader's collaborators, each from a different part of start-up.
internal fun claudeReaderFor(
    terminal: TerminalService?,
    log: EventLog,
    scope: CoroutineScope,
    dataDir: Path?,
    connections: ConnectionRegistry,
    devices: suspend () -> List<String>,
    hooksInstalled: suspend (projectPath: String) -> Boolean = { true },
): ClaudeReader? {
    terminal ?: return null
    val reader =
        ClaudeReader(
            log = EventLogReaderLog(log),
            io = TmuxPaneIo(terminal.gate.server),
            statuses = ClaudeAgentsCommand(),
            allows = { name -> terminal.gate.settings.allows(name) },
            scope = scope,
            registry = connections,
            devices = devices,
            offsets = ReaderOffsets(dataDir?.resolve("reader")?.resolve("record-offsets.json")),
            hooksInstalled = hooksInstalled,
            present = terminal.source::present,
        )
    terminal.source.onScan = reader::onScan
    terminal.source.permissionModeOf = reader::permissionMode
    return reader
}

/**
 * Whether a project has the Rafiq hooks, by the hook installer. With no installer, because the hook program or the
 * data folder is missing, no project can have them, so a prompt is refused as hooks not installed.
 */
internal fun hooksInstalledBy(hooks: HookSetup?): suspend (projectPath: String) -> Boolean =
    { project -> hooks?.preview(project)?.installed ?: false }
