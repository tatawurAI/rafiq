package ai.tatawur.rafiq.bridge

import ai.tatawur.rafiq.bridge.agent.AgentAdapter
import ai.tatawur.rafiq.bridge.agent.AgentSession
import ai.tatawur.rafiq.bridge.server.BridgeSecurity
import ai.tatawur.rafiq.bridge.sessions.SessionManager
import ai.tatawur.rafiq.bridge.store.EventLog
import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.AgentThread
import ai.tatawur.rafiq.protocol.Event
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.Session
import ai.tatawur.rafiq.protocol.ThreadKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/** An agent that does only what a test scripts: the test pushes payloads, and prompts are recorded. */
class FakeAdapter(
    override val agentId: String = "fake",
) : AgentAdapter {
    val started = CopyOnWriteArrayList<FakeAgentSession>()
    val resumedRefs = CopyOnWriteArrayList<String>()

    override suspend fun start(
        sessionId: String,
        projectPath: Path,
    ): AgentSession = FakeAgentSession(sessionId, "ref-$sessionId").also(started::add)

    override suspend fun resume(
        sessionId: String,
        projectPath: Path,
        agentSessionRef: String,
    ): AgentSession {
        resumedRefs += agentSessionRef
        return FakeAgentSession(sessionId, agentSessionRef).also(started::add)
    }
}

class FakeAgentSession(
    override val sessionId: String,
    override val agentSessionRef: String,
) : AgentSession {
    private val outbox = Channel<EventPayload>(Channel.UNLIMITED)
    val prompts = CopyOnWriteArrayList<Pair<String, String>>()
    val cancels = CopyOnWriteArrayList<String>()
    val approvals = CopyOnWriteArrayList<Pair<String, String>>()

    /** When set, [prompt], [cancel] or [respondToApproval] throws it, as an adapter that refuses would. */
    @Volatile var failure: Throwable? = null

    override val mainThreadId = "main"
    override val updates: Flow<EventPayload> = outbox.receiveAsFlow()

    fun emit(payload: EventPayload) {
        outbox.trySend(payload)
    }

    /** The agent process exits: the updates flow completes. */
    fun exit() {
        outbox.close()
    }

    /** The updates flow breaks with [cause]. */
    fun breakWith(cause: Throwable) {
        outbox.close(cause)
    }

    override suspend fun prompt(
        threadId: String,
        text: String,
    ) {
        failure?.let { throw it }
        prompts += threadId to text
        // Like the real adapter: it reports the turn starting, but never echoes the user's message.
        emit(status(AgentState.WORKING, threadId))
    }

    override suspend fun cancel(threadId: String) {
        failure?.let { throw it }
        cancels += threadId
    }

    override suspend fun respondToApproval(
        approvalId: String,
        optionId: String,
    ) {
        failure?.let { throw it }
        approvals += approvalId to optionId
    }

    override suspend fun close() = Unit
}

/** A real SQLite file in [dir], a session manager over it, and the fake adapter it drives. */
class BridgeFixture(
    dir: Path,
    val adapter: FakeAdapter = FakeAdapter(),
) : AutoCloseable {
    val log: EventLog = EventLog.open(dir, Dispatchers.IO.limitedParallelism(1))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val sessions = SessionManager(log, listOf(adapter), scope)

    override fun close() {
        scope.cancel()
        log.close()
    }
}

/**
 * A device token exactly as long as the bridge demands (64 characters, the length `openssl rand -hex 32`
 * makes). Built at runtime and obviously fake, so secret scanners do not mistake it for a real key.
 */
val TEST_TOKEN: String = "rafiq-test-token-".padEnd(64, 'x')

fun testSecurity() = BridgeSecurity(TEST_TOKEN, bindAddress = "127.0.0.1")

val TEST_SESSION = Session("ses_1", "fake", "/work/weather-app", "weather-app", AgentState.DONE, 1, 1)
val TEST_MAIN_THREAD = AgentThread("main", "ses_1", null, ThreadKind.MAIN, "weather-app", AgentState.DONE)

/** Stores [TEST_SESSION], which leaves two events in its log: the session and its main thread. */
suspend fun EventLog.seed() = createSession(TEST_SESSION, "ref", TEST_MAIN_THREAD)

fun status(
    state: AgentState,
    threadId: String? = null,
    detail: String? = null,
) = EventPayload.StatusChanged(threadId = threadId, state = state, detail = detail)

/**
 * Runs [trigger], then returns the first event of [sessionId] logged after it that satisfies [until].
 * The subscription starts before the trigger, so nothing it causes can be missed.
 */
suspend fun EventLog.eventAfter(
    sessionId: String,
    trigger: suspend () -> Unit,
    until: (Event) -> Boolean,
): Event {
    val subscription = subscribe(sessionId, capacity = 10_000)
    try {
        trigger()
        for (event in subscription.events) if (until(event)) return event
        error("The log closed before a matching event arrived.")
    } finally {
        subscription.close()
    }
}
