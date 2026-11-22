package ai.tatawur.rafiq.bridge

import ai.tatawur.rafiq.bridge.agent.acp.AcpAgentAdapter
import ai.tatawur.rafiq.bridge.agent.acp.fakeAcpAgentCommand
import ai.tatawur.rafiq.bridge.sessions.SessionManager
import ai.tatawur.rafiq.bridge.store.EventLog
import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.AgentThread
import ai.tatawur.rafiq.protocol.ContentBlock
import ai.tatawur.rafiq.protocol.Event
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.Session
import ai.tatawur.rafiq.protocol.ThreadKind
import ai.tatawur.rafiq.protocol.protocolJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Writes the event streams the app's tests replay, by running the ACP fixtures through the real
 * adapter, session manager and event log. Not part of `test`: `./gradlew :bridge:recordEventFixtures`
 * runs it and rewrites `client/src/test/resources/events/recorded/`. See the README there.
 *
 * Each stream is made repeatable: a stepping clock, counted ids, and a fixed session id and project
 * path in place of the random and temporary ones.
 */
@EnabledIfSystemProperty(named = "rafiq.recordEventFixtures", matches = "true")
@Timeout(120)
class RecordEventFixtures {
    @TempDir
    lateinit var dir: Path

    private val outputDir: Path by lazy { Path.of(System.getProperty("rafiq.recordedEventsDir")) }

    @Test
    fun record_with_subagents() =
        recordNewSession("with-subagents") { sessions, log, id ->
            sessions.prompt(id, null, "Launch two helpers: summarise README.md and find the highest temperature.")
            // The follow-up the agent sends on its own once the first helper reports back.
            log.awaitLogged(id) { it.isCompleteMessage("answers together") }
        }

    @Test
    fun record_child_approval() =
        recordNewSession("child-approval") { sessions, log, id ->
            sessions.prompt(id, null, "Run the tests.")
            val approval = log.awaitLogged(id) { it.payload is EventPayload.ApprovalRequest }
            sessions.respondToApproval((approval.payload as EventPayload.ApprovalRequest).id, "allow-once")
            sessions.cancel(id, "sess_child")
            log.awaitLogged(id, afterSeq = approval.seq) { it.isTurnEnd() }
        }

    @Test
    fun record_approval_turn_cancel() =
        recordNewSession("approval-turn-cancel") { sessions, log, id ->
            sessions.prompt(id, null, "Clean up.")
            val approval = log.awaitLogged(id) { it.payload is EventPayload.ApprovalRequest }
            sessions.cancel(id, "main")
            // The approval left the main thread waiting on you too, so only a later wait is the turn's end.
            log.awaitLogged(id, afterSeq = approval.seq + 1) { it.isTurnEnd() }
        }

    /**
     * A session the log already has, reattached after a bridge restart. The session manager resumes
     * only for a prompt, whose user message would race the agent's first chunk, so this drives the
     * adapter itself and logs what the manager logs on a resume.
     */
    @Test
    fun record_resume() =
        record("resume") { adapter, log, project ->
            val session = Session(SESSION_ID, AGENT_ID, project.toString(), "weather-app", AgentState.DONE, 0, 0)
            val main = AgentThread("main", SESSION_ID, null, ThreadKind.MAIN, "weather-app", AgentState.DONE)
            log.createSession(session, "sess_old", main)
            val agent = adapter.resume(SESSION_ID, project, "sess_old")
            log.append(
                SESSION_ID,
                EventPayload.StatusChanged(threadId = "main", state = AgentState.DONE, detail = "Resumed."),
            )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            scope.launch { agent.updates.collect { log.append(SESSION_ID, it) } }
            // No turn ends this reply, so it ends after the adapter's quiet period.
            log.awaitLogged(SESSION_ID) { it.isCompleteMessage("Picking up") }
            val events = log.after(SESSION_ID, 0, MAX_EVENTS)
            agent.close()
            scope.cancel()
            SESSION_ID to events
        }

    private fun recordNewSession(
        name: String,
        script: suspend (SessionManager, EventLog, String) -> Unit,
    ) = record(name) { adapter, log, project ->
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sessions = SessionManager(log, listOf(adapter), scope, clock = SteppingClock())
        try {
            val id = sessions.create(AGENT_ID, project.toString(), null).id
            script(sessions, log, id)
            // Read before the shutdown, whose interruption notes are not part of the story.
            id to log.after(id, 0, MAX_EVENTS)
        } finally {
            sessions.shutdown()
            scope.cancel()
        }
    }

    /**
     * Runs [block] over [name]'s ACP fixture, then writes the events it returns, made repeatable.
     * [block] returns the session id and the session's events.
     */
    private fun record(
        name: String,
        block: suspend (AcpAgentAdapter, EventLog, Path) -> Pair<String, List<Event>>,
    ) = runBlocking {
        val project = Files.createDirectory(dir.resolve("weather-app"))
        val ids = AtomicInteger()
        val adapter =
            AcpAgentAdapter(
                agentId = AGENT_ID,
                command = fakeAcpAgentCommand("$name.jsonl", dir.resolve("sent-by-bridge.jsonl"), exitAtEnd = false),
                clientVersion = "recorder",
                newId = { "id-${ids.incrementAndGet()}" },
            )
        val log = EventLog.open(dir.resolve("data"), Dispatchers.IO.limitedParallelism(1), SteppingClock())
        log.use {
            val (sessionId, events) = block(adapter, log, project)
            val lines = events.map { protocolJson.encodeToString(Event.serializer(), it) }
            val text = Repeatable(sessionId, project).apply(lines.joinToString("\n", postfix = "\n"))
            outputDir.createDirectories()
            outputDir.resolve("$name.events.jsonl").writeText(text)
            assertTrue(text.isNotBlank())
        }
    }
}

/** Swaps the run's random and local values for fixed ones. */
private class Repeatable(
    private val sessionId: String,
    private val project: Path,
) {
    fun apply(text: String): String {
        var out = text.replace(sessionId, SESSION_ID)
        for (path in listOf(project.toRealPath().toString(), project.toString())) out = out.replace(path, PROJECT_PATH)
        val userIds =
            USER_MESSAGE_ID
                .findAll(out)
                .map { it.value }
                .distinct()
                .toList()
        userIds.forEachIndexed { index, id ->
            out =
                out.replace(id, "user:00000000-0000-4000-8000-%012d".format(index + 1))
        }
        return out
    }
}

/** Waits until an event of [sessionId] after [afterSeq] matching [predicate] is in the log, and returns it. */
private suspend fun EventLog.awaitLogged(
    sessionId: String,
    afterSeq: Long = 0,
    predicate: (Event) -> Boolean,
): Event {
    val subscription = subscribe(sessionId, capacity = MAX_EVENTS)
    val matches = { event: Event -> event.seq > afterSeq && predicate(event) }
    try {
        after(sessionId, afterSeq, MAX_EVENTS).firstOrNull(matches)?.let { return it }
        for (event in subscription.events) if (matches(event)) return event
        error("The log closed before a matching event arrived.")
    } finally {
        subscription.close()
    }
}

private fun Event.isCompleteMessage(containing: String): Boolean {
    val message = payload as? EventPayload.Message ?: return false
    return !message.isStreaming &&
        message.content.any {
            containing in ((it as? ContentBlock.Markdown)?.markdown ?: (it as? ContentBlock.Text)?.text).orEmpty()
        }
}

private fun Event.isTurnEnd(): Boolean {
    val status = payload as? EventPayload.StatusChanged ?: return false
    return status.threadId == "main" && status.state == AgentState.WAITING_ON_YOU
}

/** A clock that moves on a quarter second at every reading, from a fixed start, so `ts_ms` repeats. */
private class SteppingClock : Clock() {
    private val nowMs = AtomicLong(START_MS)

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun millis(): Long = nowMs.getAndAdd(STEP_MS)

    override fun instant(): Instant = Instant.ofEpochMilli(millis())

    private companion object {
        const val START_MS = 1_795_165_200_000L // 2026-11-20 09:00 UTC
        const val STEP_MS = 250L
    }
}

private const val AGENT_ID = "claude"
private const val SESSION_ID = "ses_weather_app"
private const val PROJECT_PATH = "/home/user/weather-app"
private const val MAX_EVENTS = 10_000
private val USER_MESSAGE_ID = Regex("user:[0-9a-f-]{36}")
