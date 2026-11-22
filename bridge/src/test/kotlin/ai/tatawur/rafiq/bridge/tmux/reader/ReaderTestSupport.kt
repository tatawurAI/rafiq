package ai.tatawur.rafiq.bridge.tmux.reader

import ai.tatawur.rafiq.bridge.hook.HookEnvelope
import ai.tatawur.rafiq.bridge.tmux.PaneInfo
import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.AgentThread
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.Session
import ai.tatawur.rafiq.protocol.ThreadKind
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.CopyOnWriteArrayList

const val SESSION = "tmux-weather-app"
const val PANE = "%3"
const val PID = 4242L

/** The bridge's own tmux socket, as the fake server reports it. Hook calls name it in `tmux_socket`. */
const val SOCKET = "/tmp/rafiq-test-socket"

/** The event log as the reader sees it, in memory, with the tmux session the source would have listed. */
class FakeReaderLog : ReaderLog {
    val events = CopyOnWriteArrayList<EventPayload>()
    var session =
        Session(SESSION, "claude-code", "/work/weather-app", "weather-app", AgentState.DONE, 1, 1, terminal = true)

    override suspend fun append(
        sessionId: String,
        payload: EventPayload,
    ) {
        check(sessionId == SESSION) { "an event for another session: $sessionId" }
        events += payload
    }

    override suspend fun session(sessionId: String): Session? = session.takeIf { sessionId == SESSION }

    override suspend fun threads(sessionId: String): List<AgentThread> =
        listOf(AgentThread("main", SESSION, null, ThreadKind.MAIN, "weather-app", AgentState.DONE))

    override suspend fun recentAgentTexts(sessionId: String): List<String> = agentTexts()

    inline fun <reified T : EventPayload> all(): List<T> = events.filterIsInstance<T>()
}

/** A tmux server and process table that do what a test says, and remember what was typed. */
class FakePaneIo : PaneIo {
    var screen: String? = EMPTY_BOX
    var agentRuns = true
    var onPaste: () -> Unit = {}
    val pasted = CopyOnWriteArrayList<String>()
    val enters = CopyOnWriteArrayList<String>()

    var paneList = listOf(PaneInfo("weather-app", PANE, 100, "/work/weather-app", true))

    override suspend fun panes(): List<PaneInfo> = paneList

    override suspend fun socketPath(): String? = SOCKET

    override suspend fun agentPids(panes: List<PaneInfo>): Map<String, List<Long>> = mapOf(PANE to listOf(PID))

    override suspend fun capture(paneId: String): String? = screen

    override suspend fun paste(
        paneId: String,
        text: String,
    ): Boolean {
        pasted += text
        screen = box("[Pasted text]")
        onPaste()
        return true
    }

    override suspend fun pressEnter(paneId: String): Boolean {
        enters += paneId
        screen = EMPTY_BOX
        return true
    }

    override suspend fun runsAgent(
        paneId: String,
        agentPid: Long,
    ): Boolean = agentRuns

    companion object {
        fun box(line: String) = "some output\n${"─".repeat(40)}\n❯ $line\n${"─".repeat(40)}\n  ? for shortcuts\n"

        val EMPTY_BOX = "some output\n${"─".repeat(40)}\n❯ \n${"─".repeat(40)}\n  ? for shortcuts\n"
    }
}

/** The agent's status list, set by the test. */
class FakeStatuses(
    var status: String = AgentStatus.IDLE,
) : AgentStatusSource {
    var calls = 0
    var available = true

    /** False once the agent process is gone from the list. */
    var present = true
    var onCall: (Int) -> Unit = {}

    override suspend fun statuses(): Map<Long, AgentStatus>? {
        calls++
        onCall(calls)
        return when {
            !available -> null
            !present -> emptyMap()
            else -> mapOf(PID to AgentStatus(PID, status))
        }
    }
}

/** A hook call from [PANE], received now on the test clock. */
fun TestScope.hook(
    event: JsonObject,
    pane: String = PANE,
    pid: Long? = PID,
    socket: String? = SOCKET,
) = HookEnvelope("claude-code", pane, pid, event, testScheduler.currentTime, socket)

/** A hook event with the fields every payload carries, plus [fields]. */
fun event(
    name: String,
    vararg fields: Pair<String, Any?>,
    sessionId: String = "00000000-0000-4000-8000-000000000001",
): JsonObject =
    buildJsonObject {
        put("hook_event_name", name)
        put("session_id", sessionId)
        put("cwd", "/work/weather-app")
        for ((key, value) in fields) {
            when (value) {
                null -> Unit
                is String -> put(key, value)
                is Boolean -> put(key, value)
                is Number -> put(key, value)
                is kotlinx.serialization.json.JsonElement -> put(key, value)
                else -> error("unsupported field type for $key")
            }
        }
    }

fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

fun bash(command: String): JsonObject = buildJsonObject { put("command", command) }

/** A reader on the test clock, for the tmux session `weather-app` only. */
fun TestScope.reader(
    log: ReaderLog,
    io: PaneIo = FakePaneIo(),
    statuses: AgentStatusSource = FakeStatuses(),
    timing: ReaderTiming = ReaderTiming(),
    registry: ai.tatawur.rafiq.bridge.server.ConnectionRegistry? = null,
    devices: suspend () -> List<String> = { emptyList() },
    offsets: ReaderOffsets = ReaderOffsets(null),
    hooksInstalled: suspend (String) -> Boolean = { true },
    projectsDir: java.nio.file.Path? = null,
) = ClaudeReader(
    log = log,
    io = io,
    statuses = statuses,
    allows = { it == "weather-app" },
    scope = backgroundScope,
    registry = registry,
    devices = devices,
    offsets = offsets,
    hooksInstalled = hooksInstalled,
    projectsDir = projectsDir,
    nowMs = { testScheduler.currentTime },
    ioDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
    timing = timing,
)

/** One line of a session record, in the shape Claude Code writes. */
fun recordLine(
    type: String,
    content: kotlinx.serialization.json.JsonElement,
    promptId: String? = null,
): String =
    buildJsonObject {
        put("type", type)
        promptId?.let { put("promptId", it) }
        put(
            "uuid",
            java.util.UUID
                .randomUUID()
                .toString(),
        )
        put(
            "message",
            buildJsonObject {
                put("role", if (type == "assistant") "assistant" else "user")
                put("content", content)
            },
        )
    }.toString() + "\n"

fun assistantText(text: String) =
    recordLine(
        "assistant",
        kotlinx.serialization.json.JsonArray(
            listOf(
                buildJsonObject {
                    put("type", "text")
                    put("text", text)
                },
            ),
        ),
    )

fun userPrompt(
    text: String,
    promptId: String? = null,
) = recordLine("user", kotlinx.serialization.json.JsonPrimitive(text), promptId)

/** The prompts shown as your messages, as (id, text). */
fun FakeReaderLog.userMessages(): List<Pair<String, String>> =
    all<EventPayload.Message>()
        .filter { it.role == ai.tatawur.rafiq.protocol.MessageRole.USER }
        .map { it.id to (it.content.single() as ai.tatawur.rafiq.protocol.ContentBlock.Text).text }

/** The agent's messages in the log, as text. */
fun FakeReaderLog.agentTexts(): List<String> =
    all<EventPayload.Message>()
        .filter { it.role == ai.tatawur.rafiq.protocol.MessageRole.AGENT && !it.isStreaming }
        .map { (it.content.single() as ai.tatawur.rafiq.protocol.ContentBlock.Markdown).markdown }

/** A whole one-batch `MessageDisplay`. */
fun display(
    text: String,
    vararg extra: Pair<String, Any?>,
    id: String = "m1",
) = event("MessageDisplay", "message_id" to id, "index" to 0, "final" to true, "delta" to text, *extra)

/** A `PreToolUse` or `PostToolUse` of a shell command. */
fun bashStep(
    name: String,
    id: String,
    command: String,
) = event(name, "tool_name" to "Bash", "tool_use_id" to id, "tool_input" to bash(command))

fun permission(
    tool: String,
    input: JsonObject,
) = event("PermissionRequest", "tool_name" to tool, "tool_input" to input)
