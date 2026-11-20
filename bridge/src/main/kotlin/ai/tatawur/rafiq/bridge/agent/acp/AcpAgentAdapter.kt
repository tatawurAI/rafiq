// ResumeSessionRequest is marked unstable in the SDK. session/resume is what lets a resumed session
// skip a history replay, so it is used when the agent offers it.
@file:OptIn(UnstableApi::class)

package ai.tatawur.rafiq.bridge.agent.acp

import ai.tatawur.rafiq.bridge.agent.AgentAdapter
import ai.tatawur.rafiq.bridge.agent.AgentSession
import ai.tatawur.rafiq.bridge.agent.AgentUnavailableException
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.InitializeRequest
import com.agentclientprotocol.model.LATEST_PROTOCOL_VERSION
import com.agentclientprotocol.model.LoadSessionRequest
import com.agentclientprotocol.model.NewSessionRequest
import com.agentclientprotocol.model.ResumeSessionRequest
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.protocol.JsonRpcException
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.MethodName
import com.agentclientprotocol.transport.StdioTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.serializer
import java.io.IOException
import java.nio.file.Path
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Runs any agent that speaks the Agent Client Protocol (ACP) over stdio, one process per session.
 *
 * The bridge configures one instance per agent, for example `claude-code` with the command
 * `claude-agent-acp`. Each session starts [command] in the project folder, sends `initialize` with the
 * subagents capability (see [initializeParamsWithSubagents]), and opens the root session. The bridge
 * offers no file system or terminal capability, so the agent uses its own tools for both.
 *
 * [AgentSession.prompt] does not echo your text on [AgentSession.updates]. The bridge logs it as a user
 * message before calling the adapter, so every adapter gets this right without its own rule. An
 * agent's own echo of it, `user_message_chunk`, is dropped. After `session/prompt` is sent the main
 * thread reports working, and when the turn ends it reports waiting on you. Updates keep flowing after
 * that, because helpers launched in the background report back and the agent can follow up on its own.
 *
 * The SDK's JSON-RPC layer is driven directly rather than through its `Client`, see [AcpAgentSession].
 *
 * @param command the program and its arguments, for example `npx -y @agentclientprotocol/claude-agent-acp@0.109.0`.
 * @param clientVersion the bridge version, sent to the agent as the client's version.
 * @param ioDispatcher runs process I/O. Tests can pass their own.
 * @param startTimeout how long the agent may take to answer `initialize` and open the session.
 * @param stopGracePeriod how long the agent gets to exit after stdin closes, and again after a
 *   termination signal, before it is killed.
 * @param newId makes event ids for messages and approvals, which must stay unique across resumes.
 */
class AcpAgentAdapter(
    override val agentId: String,
    private val command: List<String>,
    private val clientVersion: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val startTimeout: Duration = 60.seconds,
    private val stopGracePeriod: Duration = 5.seconds,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : AgentAdapter {
    init {
        require(command.isNotEmpty()) { "The ACP command for $agentId is empty." }
    }

    override suspend fun start(
        sessionId: String,
        projectPath: Path,
    ): AgentSession =
        open(sessionId, projectPath) { protocol, session, _ ->
            val request = NewSessionRequest(cwd = projectPath.toString(), mcpServers = emptyList())
            val response = send(protocol, AcpMethod.AgentMethods.V1.SessionNew.methodName, encode(request))
            val acpSessionId =
                (response as? JsonObject)?.string("sessionId")
                    ?: throw AgentUnavailableException("$agentId opened a session without an id.")
            session.openedRoot(acpSessionId, replay = false)
        }

    override suspend fun resume(
        sessionId: String,
        projectPath: Path,
        agentSessionRef: String,
    ): AgentSession =
        open(sessionId, projectPath) { protocol, session, agent ->
            val cwd = projectPath.toString()
            val id = SessionId(agentSessionRef)
            when {
                // Resume reattaches without replaying history, which the event log already has.
                agent.canResume -> {
                    session.openedRoot(agentSessionRef, replay = false)
                    val request = ResumeSessionRequest(sessionId = id, cwd = cwd)
                    send(protocol, AcpMethod.AgentMethods.V1.SessionResume.methodName, encode(request))
                }

                agent.canLoad -> {
                    session.openedRoot(agentSessionRef, replay = true)
                    val request = LoadSessionRequest(sessionId = id, cwd = cwd, mcpServers = emptyList())
                    send(protocol, AcpMethod.AgentMethods.V1.SessionLoad.methodName, encode(request))
                    session.openedRoot(agentSessionRef, replay = false)
                }

                else -> {
                    throw AgentUnavailableException("$agentId cannot resume sessions.")
                }
            }
        }

    /**
     * Starts the process, initializes it and runs [openRoot]. Any failure stops the process again and
     * surfaces as [AgentUnavailableException].
     */
    private suspend fun open(
        sessionId: String,
        projectPath: Path,
        openRoot: suspend (Protocol, AcpAgentSession, AgentFeatures) -> Unit,
    ): AgentSession {
        val process =
            try {
                AgentProcess.start(command, projectPath, ioDispatcher, stopGracePeriod)
            } catch (e: IOException) {
                throw AgentUnavailableException("Could not start $agentId with `${command.first()}`.", e)
            }
        val scope = CoroutineScope(SupervisorJob() + ioDispatcher + CoroutineName("acp-$agentId-$sessionId"))
        process.drainStderr(scope)
        val transport =
            StdioTransport(scope, ioDispatcher, withEndOfOutput(process.lines), process::write, name = "acp-$agentId")
        val protocol = Protocol(scope, transport)
        val session =
            AcpAgentSession(sessionId, protocol, process, scope, AcpEventMapper(sessionId, projectPath, newId), newId)
        session.attach()
        protocol.start()
        var opened = false
        try {
            withTimeout(startTimeout) {
                val agent = initialize(protocol)
                openRoot(protocol, session, agent)
            }
            opened = true
        } catch (e: CancellationException) {
            throw startFailure(e)
        } finally {
            if (!opened) withContext(NonCancellable) { session.close() }
        }
        return session
    }

    /**
     * Why the session did not open. The SDK cancels pending requests when the connection closes, for
     * example because the process exited, so only a cancellation of the caller itself stays one.
     */
    private suspend fun startFailure(e: CancellationException): Exception {
        if (e is TimeoutCancellationException) {
            return AgentUnavailableException("$agentId did not answer within $startTimeout.", e)
        }
        currentCoroutineContext().ensureActive()
        return AgentUnavailableException("$agentId exited before the session opened.", e)
    }

    /**
     * Sends `initialize` and reads back only what the bridge acts on. The rest of the answer is not
     * decoded: real agents send capability shapes the SDK's typed model rejects, for example an
     * object where it expects `auth.status` to be a boolean.
     */
    private suspend fun initialize(protocol: Protocol): AgentFeatures {
        val request =
            InitializeRequest(
                protocolVersion = LATEST_PROTOCOL_VERSION,
                clientInfo = Implementation(name = "rafiq-bridge", version = clientVersion),
            )
        val response =
            send(protocol, AcpMethod.AgentMethods.V1.Initialize.methodName, initializeParamsWithSubagents(request))
        val answer = response as? JsonObject ?: JsonObject(emptyMap())
        val version = (answer["protocolVersion"] as? JsonPrimitive)?.intOrNull
        if (version != LATEST_PROTOCOL_VERSION) {
            throw AgentUnavailableException("$agentId speaks ACP version $version, not $LATEST_PROTOCOL_VERSION.")
        }
        val capabilities = answer["agentCapabilities"] as? JsonObject ?: JsonObject(emptyMap())
        return AgentFeatures(
            canResume = (capabilities["sessionCapabilities"] as? JsonObject)?.get("resume") is JsonObject,
            canLoad = (capabilities["loadSession"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

    /** Sends a request and turns an agent-side error, or the agent going away, into [AgentUnavailableException]. */
    private suspend fun send(
        protocol: Protocol,
        method: MethodName,
        params: JsonElement,
    ): JsonElement =
        try {
            protocol.sendRequestRaw(method, params)
        } catch (e: JsonRpcException) {
            throw AgentUnavailableException("$agentId refused `${method.name}`: ${e.message}", e)
        } catch (e: IllegalStateException) {
            // The SDK reports a closed connection this way, for example when the process has exited.
            throw AgentUnavailableException("$agentId closed the connection during `${method.name}`.", e)
        }
}

private inline fun <reified T> encode(value: T): JsonElement = ACPJson.encodeToJsonElement(serializer<T>(), value)

/** What the agent said it can do, from its `initialize` answer. */
private data class AgentFeatures(
    val canResume: Boolean,
    val canLoad: Boolean,
)
