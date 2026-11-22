package ai.tatawur.rafiq.ui.sample

import ai.tatawur.rafiq.client.ApprovalCard
import ai.tatawur.rafiq.client.CommandResult
import ai.tatawur.rafiq.client.ConnectionState
import ai.tatawur.rafiq.client.SessionListItem
import ai.tatawur.rafiq.client.SessionRepository
import ai.tatawur.rafiq.client.SessionScreenState
import ai.tatawur.rafiq.client.ThreadTab
import ai.tatawur.rafiq.client.TimelineItem
import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.ApprovalOption
import ai.tatawur.rafiq.protocol.ApprovalOptionKind
import ai.tatawur.rafiq.protocol.ContentBlock
import ai.tatawur.rafiq.protocol.MessageRole
import ai.tatawur.rafiq.protocol.Session
import ai.tatawur.rafiq.protocol.ThreadKind
import ai.tatawur.rafiq.protocol.ToolCallKind
import ai.tatawur.rafiq.protocol.ToolCallStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * A [SessionRepository] with made-up sessions, for building and reviewing the screens without a
 * bridge. `weather-app` streams a long reply on its main thread while a helper thread works, then
 * asks for an approval. `api-server` waits on an approval. `docs-site` is done. Prompts get a short
 * canned reply, approvals resolve after a moment, and cancel stops a thread's stream.
 *
 * Everything runs on the main thread, so the simulation needs no locking.
 *
 * @param scope runs the simulated agents. It should outlive the screens, so a fold or unfold does
 *   not restart the sample.
 * @param nowMs the clock for `updated_at_ms`, in milliseconds since the Unix epoch.
 */
@Suppress("TooManyFunctions") // The contract plus small simulation steps that all share one state.
class SampleSessionRepository(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : SessionRepository {
    private val mutableConnection = MutableStateFlow<ConnectionState>(ConnectionState.Live)
    override val connection: StateFlow<ConnectionState> = mutableConnection.asStateFlow()

    private val states = MutableStateFlow(initialSampleStates(nowMs()))
    private val streams = mutableMapOf<String, Job>()
    private var nextSeq = INITIAL_SEQ

    init {
        val ids = SampleIds
        streams[threadKey(ids.WEATHER, ids.WEATHER_MAIN)] = scope.launch { runWeatherMain() }
        streams[threadKey(ids.WEATHER, ids.WEATHER_RESEARCH)] =
            scope.launch {
                stream(ids.WEATHER, ids.WEATHER_RESEARCH, "research-reply-1", researchReply)
                setThreadStatus(ids.WEATHER, ids.WEATHER_RESEARCH, AgentState.DONE)
            }
    }

    override fun sessions(): Flow<List<SessionListItem>> =
        states.map { all ->
            all.values
                .mapNotNull { state ->
                    state.session?.let { SessionListItem(it, lastLine(state), state.openApprovals) }
                }.sortedByDescending { it.session.updatedAtMs }
        }

    override fun session(sessionId: String): Flow<SessionScreenState> =
        states.mapNotNull { it[sessionId] }.distinctUntilChanged()

    override suspend fun agents(): CommandResult<List<String>> = CommandResult.Ok(listOf("claude-code", "codex"))

    override suspend fun createSession(
        agent: String,
        projectPath: String,
        title: String?,
    ): CommandResult<Session> {
        val id = "s-${UUID.randomUUID()}"
        val now = nowMs()
        val session =
            Session(
                id = id,
                agent = agent,
                projectPath = projectPath,
                title = title ?: projectPath.substringAfterLast('/').ifEmpty { projectPath },
                status = AgentState.DONE,
                createdAtMs = now,
                updatedAtMs = now,
            )
        val main = ThreadTab("$id-main", null, ThreadKind.MAIN, "Main", AgentState.DONE, 0)
        val state = SessionScreenState(id, session, listOf(main), mapOf(main.id to emptyList()))
        states.update { it + (id to state) }
        return CommandResult.Ok(session)
    }

    override suspend fun prompt(
        sessionId: String,
        text: String,
    ): CommandResult<Unit> {
        val main = states.value[sessionId]?.mainThread
        return when {
            main == null -> {
                CommandResult.NotFound
            }

            main.status == AgentState.WORKING -> {
                CommandResult.Busy
            }

            else -> {
                // The bridge logs your prompt and sends it back as a user message, so the sample does too.
                delay(SEND_DELAY_MS)
                append(sessionId, main.id, message("user:${UUID.randomUUID()}", MessageRole.USER, text))
                setThreadStatus(sessionId, main.id, AgentState.WORKING)
                streams[threadKey(sessionId, main.id)] =
                    scope.launch {
                        stream(sessionId, main.id, "reply-${UUID.randomUUID()}", cannedReply(text))
                        setThreadStatus(sessionId, main.id, AgentState.DONE)
                    }
                CommandResult.Ok(Unit)
            }
        }
    }

    override suspend fun cancel(
        sessionId: String,
        threadId: String,
    ): CommandResult<Unit> {
        if (sessionId !in states.value) return CommandResult.NotFound
        streams.remove(threadKey(sessionId, threadId))?.cancel()
        updateState(sessionId) { state ->
            val timeline =
                state.timelines[threadId].orEmpty().map { item ->
                    if (item is TimelineItem.Message && item.isStreaming) item.copy(isStreaming = false) else item
                }
            state.copy(timelines = state.timelines + (threadId to timeline))
        }
        setThreadStatus(sessionId, threadId, AgentState.DONE)
        return CommandResult.Ok(Unit)
    }

    override suspend fun approve(
        approvalId: String,
        optionId: String,
    ): CommandResult<Unit> {
        val card = states.value.values.firstNotNullOfOrNull { s -> s.openApprovals.firstOrNull { it.id == approvalId } }
        val option = card?.options?.firstOrNull { it.id == optionId }
        return when {
            card == null -> {
                CommandResult.Gone
            }

            option == null -> {
                CommandResult.Failed("bad_option", optionId)
            }

            else -> {
                scope.launch { resolve(card, option) }
                CommandResult.Ok(Unit)
            }
        }
    }

    private suspend fun resolve(
        card: ApprovalCard,
        option: ApprovalOption,
    ) {
        val sessionId = card.sessionId
        delay(RESOLVE_DELAY_MS)
        val denied = option.kind == ApprovalOptionKind.DENY
        updateState(sessionId) { it.copy(openApprovals = it.openApprovals.filterNot { open -> open.id == card.id }) }
        updateTool(sessionId, card.toolCallId) {
            it.copy(status = if (denied) ToolCallStatus.FAILED else ToolCallStatus.IN_PROGRESS)
        }
        val threadId = card.threadId ?: return
        if (!denied) {
            setThreadStatus(sessionId, threadId, AgentState.WORKING)
            delay(TOOL_RUN_MS)
            updateTool(sessionId, card.toolCallId) {
                it.copy(status = ToolCallStatus.COMPLETED, output = "Finished without errors.")
            }
        }
        setThreadStatus(sessionId, threadId, AgentState.DONE)
    }

    private suspend fun runWeatherMain() {
        val ids = SampleIds
        stream(ids.WEATHER, ids.WEATHER_MAIN, "weather-reply-2", weatherLongReply)
        delay(APPROVAL_AFTER_MS)
        val toolId = "weather-fetch-1"
        val fetch =
            TimelineItem.Tool(
                key = toolId,
                seq = seq(),
                kind = ToolCallKind.FETCH,
                inputSummary = "GET https://api.example.com/v1/forecast?city=Lisbon",
                output = null,
                status = ToolCallStatus.PENDING,
                approval = null,
            )
        append(ids.WEATHER, ids.WEATHER_MAIN, fetch)
        updateState(ids.WEATHER) { it.copy(openApprovals = it.openApprovals + weatherApproval(toolId)) }
        setThreadStatus(ids.WEATHER, ids.WEATHER_MAIN, AgentState.WAITING_ON_YOU)
    }

    /** Grows a message a few words at a time, as deltas would, then marks it final. */
    private suspend fun stream(
        sessionId: String,
        threadId: String,
        messageId: String,
        markdown: String,
    ) {
        val words = markdown.split(' ')
        append(sessionId, threadId, message(messageId, MessageRole.AGENT, "", markdown = true, streaming = true))
        for (end in WORDS_PER_DELTA..words.size step WORDS_PER_DELTA) {
            delay(DELTA_INTERVAL_MS)
            replaceMessage(sessionId, threadId, messageId, words.take(end).joinToString(" "), streaming = true)
        }
        replaceMessage(sessionId, threadId, messageId, markdown, streaming = false)
    }

    private fun message(
        id: String,
        role: MessageRole,
        text: String,
        markdown: Boolean = false,
        streaming: Boolean = false,
    ) = TimelineItem.Message(
        key = id,
        seq = seq(),
        role = role,
        blocks = listOf(if (markdown) ContentBlock.Markdown(text) else ContentBlock.Text(text)),
        isStreaming = streaming,
    )

    private fun append(
        sessionId: String,
        threadId: String,
        item: TimelineItem,
    ) = updateState(sessionId) { state ->
        state.copy(timelines = state.timelines + (threadId to state.timelines[threadId].orEmpty() + item))
    }

    private fun replaceMessage(
        sessionId: String,
        threadId: String,
        messageId: String,
        markdown: String,
        streaming: Boolean,
    ) = updateState(sessionId) { state ->
        val timeline =
            state.timelines[threadId].orEmpty().map { item ->
                if (item is TimelineItem.Message && item.key == messageId) {
                    item.copy(blocks = listOf(ContentBlock.Markdown(markdown)), isStreaming = streaming)
                } else {
                    item
                }
            }
        state.copy(timelines = state.timelines + (threadId to timeline))
    }

    private fun updateTool(
        sessionId: String,
        toolId: String,
        change: (TimelineItem.Tool) -> TimelineItem.Tool,
    ) = updateState(sessionId) { state ->
        val timelines =
            state.timelines.mapValues { (_, items) ->
                items.map { if (it is TimelineItem.Tool && it.key == toolId) change(it) else it }
            }
        state.copy(timelines = timelines)
    }

    private fun setThreadStatus(
        sessionId: String,
        threadId: String,
        status: AgentState,
    ) = updateState(sessionId) { state ->
        val threads = state.threads.map { if (it.id == threadId) it.copy(status = status) else it }
        state.copy(threads = threads, session = state.session?.copy(status = sessionStatus(threads)))
    }

    /** Applies [change], then derives what the bridge keeps consistent: badges, tool approvals and the clock. */
    private fun updateState(
        sessionId: String,
        change: (SessionScreenState) -> SessionScreenState,
    ) = states.update { all ->
        val current = all[sessionId] ?: return@update all
        val changed = change(current)
        val open = changed.openApprovals
        val threads = changed.threads.map { tab -> tab.copy(openApprovals = open.count { it.threadId == tab.id }) }
        val timelines =
            changed.timelines.mapValues { (_, items) ->
                items.map { item ->
                    if (item is TimelineItem.Tool) {
                        item.copy(approval = open.firstOrNull { it.toolCallId == item.key })
                    } else {
                        item
                    }
                }
            }
        val normalized =
            changed.copy(
                threads = threads,
                timelines = timelines,
                session = changed.session?.copy(updatedAtMs = nowMs()),
                lastSeq = nextSeq,
            )
        all + (sessionId to normalized)
    }

    private fun seq(): Long = nextSeq++

    private companion object {
        const val INITIAL_SEQ = 100L
        const val WORDS_PER_DELTA = 3
        const val DELTA_INTERVAL_MS = 140L
        const val APPROVAL_AFTER_MS = 1_500L
        const val SEND_DELAY_MS = 250L
        const val RESOLVE_DELAY_MS = 700L
        const val TOOL_RUN_MS = 1_800L

        fun threadKey(
            sessionId: String,
            threadId: String,
        ) = "$sessionId/$threadId"
    }
}

/** Ids of the sample's sessions and threads. */
internal object SampleIds {
    const val WEATHER = "s-weather-app"
    const val WEATHER_MAIN = "t-weather-main"
    const val WEATHER_RESEARCH = "t-weather-research"
    const val WEATHER_TESTS = "t-weather-tests"
    const val API = "s-api-server"
    const val API_MAIN = "t-api-main"
    const val DOCS = "s-docs-site"
    const val DOCS_MAIN = "t-docs-main"
}

/**
 * The process-wide sample. One per process, so a configuration change that recreates the activity
 * does not restart it.
 */
val sampleSessionRepository: SampleSessionRepository by lazy {
    SampleSessionRepository(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
}
