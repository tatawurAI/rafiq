package ai.tatawur.rafiq.bridge.agent.acp

import ai.tatawur.rafiq.bridge.agent.AgentSession
import ai.tatawur.rafiq.protocol.ContentBlock
import ai.tatawur.rafiq.protocol.EventPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.readLines
import kotlin.io.path.readText

/** An adapter session over FakeAcpAgent, and everything the session reported so far. */
internal class AcpRun(
    val session: AgentSession,
    private val log: Path,
    scope: CoroutineScope,
) {
    private val received = Channel<EventPayload>(Channel.UNLIMITED)
    private val seen = mutableListOf<EventPayload>()

    init {
        scope.launch {
            session.updates.collect { received.send(it) }
            received.close()
        }
    }

    suspend fun awaitEvent(predicate: (EventPayload) -> Boolean): EventPayload {
        while (true) {
            val event = received.receive()
            seen += event
            if (predicate(event)) return event
        }
    }

    /** Everything up to the end of the session, which comes when the agent process exits. */
    suspend fun drain(): List<EventPayload> {
        for (event in received) seen += event
        return seen
    }

    /** Every line the bridge wrote to the agent, read once the agent has exited. */
    fun sentByBridge(): List<JsonObject> = log.readLines().map { Json.parseToJsonElement(it).jsonObject }

    /** The pid of the agent process this run started. */
    fun agentPid(): Long =
        Path
            .of("$log.pid")
            .readText()
            .trim()
            .toLong()

    /** The names of the environment variables the agent process started with. */
    fun agentEnvironment(): List<String> = Path.of("$log.env").readLines()
}

/**
 * Starts an [AcpAgentAdapter] session over FakeAcpAgent replaying [fixture], runs [block], and
 * closes the session.
 *
 * @param open opens the session. Starts a new one unless a test resumes instead.
 */
internal fun runFakeAgent(
    projectPath: Path,
    fixture: String,
    exitAtEnd: Boolean = true,
    environment: Map<String, String> = System.getenv(),
    maxLineChars: Int = 8 * 1024 * 1024,
    open: suspend (AcpAgentAdapter) -> AgentSession = { it.start("session-1", projectPath) },
    block: suspend CoroutineScope.(AcpRun) -> Unit,
) = runBlocking {
    val log = projectPath.resolve("sent-by-bridge.jsonl")
    val ids = AtomicInteger()
    val adapter =
        AcpAgentAdapter(
            agentId = "fake",
            command = fakeAcpAgentCommand(fixture, log, exitAtEnd),
            clientVersion = "test",
            newId = { "id-${ids.incrementAndGet()}" },
            environment = environment,
            maxLineChars = maxLineChars,
        )
    val session = open(adapter)
    try {
        block(AcpRun(session, log, this))
    } finally {
        session.close()
    }
}

/** The message's text, whichever kind of text block holds it. */
internal fun EventPayload.Message.text(): String =
    content.joinToString("") {
        when (it) {
            is ContentBlock.Text -> it.text
            is ContentBlock.Markdown -> it.markdown
            else -> ""
        }
    }
