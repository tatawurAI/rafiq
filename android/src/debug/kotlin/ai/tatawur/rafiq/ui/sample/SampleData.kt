// Sample data: made-up literals in long builders. Naming each number or splitting the builders
// would only scatter the data.
@file:Suppress("MagicNumber", "LongMethod", "TooManyFunctions")

package ai.tatawur.rafiq.ui.sample

import ai.tatawur.rafiq.client.ApprovalCard
import ai.tatawur.rafiq.client.NoticeKind
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

private const val MINUTE_MS = 60_000L
private const val LAST_LINE_CHARS = 140

/** The long reply that streams into `weather-app` when the sample starts. */
internal val weatherLongReply =
    """
    ### Findings
    The forecast API reports units in three places. The parser reads two of them directly, and the
    hourly view reads the third through an old helper.

    - `forecast/Units.kt` now holds the canonical values
    - `Parser.kt` duplicated them for wind speed
    - `hourly/Legacy.kt` still calls the old API

    ### Plan
    Point the parser at the new module, then decide what happens to the old helper. The test writer
    pinned the current behaviour, so I can refactor against those tests.

    ```kotlin
    val reading = Units.parse(raw.temperature, raw.unit)
    ```

    Before I go on, I want to check the parser against a live forecast.
    """.trimIndent()

/** The reply that streams into the research helper thread. */
internal val researchReply =
    """
    The API returns `temperature_unit`, `wind_speed_unit` and `precipitation_unit`. Each has a default
    when the request leaves it out, so the parser must not assume metric.
    """.trimIndent()

/** The approval `weather-app` asks for once its long reply has finished. */
internal fun weatherApproval(toolCallId: String) =
    ApprovalCard(
        id = "weather-approval-1",
        sessionId = SampleIds.WEATHER,
        threadId = SampleIds.WEATHER_MAIN,
        toolCallId = toolCallId,
        description = "Fetch a live forecast from api.example.com to check the new parser?",
        options =
            ApprovalCard.ordered(
                listOf(
                    ApprovalOption("deny", ApprovalOptionKind.DENY, "Use the fixture instead"),
                    ApprovalOption("once", ApprovalOptionKind.ALLOW_ONCE, "Fetch once"),
                    ApprovalOption("always", ApprovalOptionKind.ALLOW_ALWAYS, "Always allow this host"),
                ),
            ),
        expiresAtMs = null,
    )

/** What the sample answers to a prompt. */
internal fun cannedReply(prompt: String): String =
    "Understood: *${prompt.take(LAST_LINE_CHARS)}*. This sample runs nothing on a server, so this " +
        "reply is made up. With your bridge paired, the agent answers here as it works."

/** A session's state from its threads: anything waiting on you wins, then anything working. */
internal fun sessionStatus(threads: List<ThreadTab>): AgentState =
    when {
        threads.any { it.status == AgentState.WAITING_ON_YOU } -> AgentState.WAITING_ON_YOU
        threads.any { it.status == AgentState.WORKING } -> AgentState.WORKING
        threads.any { it.status == AgentState.ERROR } -> AgentState.ERROR
        else -> AgentState.DONE
    }

/** The first line of the newest message on the main thread, as the data layer would show it. */
internal fun lastLine(state: SessionScreenState): String? {
    val mainId = state.mainThread?.id
    val newest =
        state.timelines[mainId]
            .orEmpty()
            .filterIsInstance<TimelineItem.Message>()
            .lastOrNull { it.blocks.isNotEmpty() }
    return newest
        ?.blocks
        ?.joinToString(" ") { block ->
            when (block) {
                is ContentBlock.Text -> block.text
                is ContentBlock.Markdown -> block.markdown
                else -> ""
            }
        }?.lineSequence()
        ?.map { it.trimStart('#', ' ', '-', '*') }
        ?.firstOrNull { it.isNotBlank() }
        ?.take(LAST_LINE_CHARS)
}

private fun tool(
    id: String,
    seq: Long,
    kind: ToolCallKind,
    summary: String,
    output: String?,
    status: ToolCallStatus = ToolCallStatus.COMPLETED,
) = TimelineItem.Tool(id, seq, kind, summary, output, status, approval = null)

private fun text(
    id: String,
    seq: Long,
    role: MessageRole,
    markdown: String,
) = TimelineItem.Message(id, seq, role, listOf(ContentBlock.Markdown(markdown)), isStreaming = false)

private fun session(
    id: String,
    agent: String,
    name: String,
    status: AgentState,
    ageMinutes: Long,
    now: Long,
) = Session(
    id = id,
    agent = agent,
    projectPath = "/srv/$name",
    title = name,
    status = status,
    createdAtMs = now - ageMinutes * MINUTE_MS,
    updatedAtMs = now,
)

private fun weatherState(now: Long): SessionScreenState {
    val ids = SampleIds
    return SessionScreenState(
        sessionId = ids.WEATHER,
        session = session(ids.WEATHER, "claude-code", "weather-app", AgentState.WORKING, 30, now),
        threads =
            listOf(
                ThreadTab(ids.WEATHER_MAIN, null, ThreadKind.MAIN, "Main", AgentState.WORKING, 0),
                ThreadTab(
                    ids.WEATHER_RESEARCH,
                    ids.WEATHER_MAIN,
                    ThreadKind.SUBAGENT,
                    "Forecast API research",
                    AgentState.WORKING,
                    0,
                ),
                ThreadTab(ids.WEATHER_TESTS, ids.WEATHER_MAIN, ThreadKind.SUBAGENT, "Test writer", AgentState.DONE, 0),
            ),
        timelines =
            mapOf(
                ids.WEATHER_MAIN to
                    listOf(
                        text(
                            "user:1",
                            1,
                            MessageRole.USER,
                            "Move the forecast parsing into its own module. Check how the API reports units first.",
                        ),
                        text(
                            "weather-reply-1",
                            2,
                            MessageRole.AGENT,
                            "I'll start two helpers: one to map the forecast API's unit fields, one to pin " +
                                "the current parsing behaviour in tests before I move anything.",
                        ),
                        tool("weather-search-1", 3, ToolCallKind.SEARCH, "\"temperature_unit\" in src/", "4 matches"),
                        tool("weather-read-1", 4, ToolCallKind.READ, "src/forecast/Parser.kt", "212 lines"),
                        tool(
                            "weather-edit-1",
                            5,
                            ToolCallKind.EDIT,
                            "Edited 3 files",
                            "src/forecast/Units.kt  +48\nsrc/forecast/Parser.kt  -12\nsrc/forecast/index.kt  +2",
                        ),
                        tool("weather-cmd-1", 6, ToolCallKind.COMMAND, "./gradlew :forecast:test", "38 tests passed"),
                        TimelineItem.Notice(
                            "weather-file-1",
                            7,
                            NoticeKind.FILE_CHANGED,
                            "src/forecast/Units.kt (modified)",
                            url = null,
                        ),
                    ),
                ids.WEATHER_RESEARCH to
                    listOf(
                        text("research-task", 8, MessageRole.USER, "Map every unit field the forecast API returns."),
                        tool("research-fetch-1", 9, ToolCallKind.FETCH, "https://api.example.com/docs/units", "200 OK"),
                    ),
                ids.WEATHER_TESTS to
                    listOf(
                        text("tests-task", 10, MessageRole.USER, "Pin the current parsing behaviour in tests."),
                        tool("tests-edit-1", 11, ToolCallKind.EDIT, "Created ParserTest.kt", "+96"),
                        tool("tests-cmd-1", 12, ToolCallKind.COMMAND, "./gradlew :forecast:test", "12 tests passed"),
                        text("tests-done", 13, MessageRole.AGENT, "Twelve tests now pin the parser. All pass."),
                    ),
            ),
    )
}

private fun apiState(now: Long): SessionScreenState {
    val ids = SampleIds
    val toolId = "api-cmd-1"
    val approval =
        ApprovalCard(
            id = "api-approval-1",
            sessionId = ids.API,
            threadId = ids.API_MAIN,
            toolCallId = toolId,
            description = "Run npm test -- routes after editing the rate limiter?",
            options =
                ApprovalCard.ordered(
                    listOf(
                        ApprovalOption("allow", ApprovalOptionKind.ALLOW_ONCE, "Allow once"),
                        ApprovalOption("always", ApprovalOptionKind.ALLOW_ALWAYS, "Always allow npm test"),
                        ApprovalOption("deny", ApprovalOptionKind.DENY, "Deny"),
                    ),
                ),
            expiresAtMs = null,
        )
    return SessionScreenState(
        sessionId = ids.API,
        session = session(ids.API, "claude-code", "api-server", AgentState.WAITING_ON_YOU, 90, now - 2 * MINUTE_MS),
        threads = listOf(ThreadTab(ids.API_MAIN, null, ThreadKind.MAIN, "Main", AgentState.WAITING_ON_YOU, 1)),
        timelines =
            mapOf(
                ids.API_MAIN to
                    listOf(
                        text("user:api-1", 20, MessageRole.USER, "The rate limiter lets bursts through. Fix it."),
                        tool("api-edit-1", 21, ToolCallKind.EDIT, "src/middleware/rateLimit.ts", "+14 -6"),
                        text("api-reply-1", 22, MessageRole.AGENT, "Fixed the window reset. Next, the route tests."),
                        TimelineItem.Tool(
                            toolId,
                            23,
                            ToolCallKind.COMMAND,
                            "npm test -- routes",
                            output = null,
                            status = ToolCallStatus.PENDING,
                            approval = approval,
                        ),
                    ),
            ),
        openApprovals = listOf(approval),
    )
}

private fun docsState(now: Long): SessionScreenState {
    val ids = SampleIds
    return SessionScreenState(
        sessionId = ids.DOCS,
        session = session(ids.DOCS, "codex", "docs-site", AgentState.DONE, 300, now - 60 * MINUTE_MS),
        threads = listOf(ThreadTab(ids.DOCS_MAIN, null, ThreadKind.MAIN, "Main", AgentState.DONE, 0)),
        timelines =
            mapOf(
                ids.DOCS_MAIN to
                    listOf(
                        text("user:docs-1", 30, MessageRole.USER, "Write a getting-started page and check every link."),
                        tool("docs-cmd-1", 31, ToolCallKind.COMMAND, "npm run check-links", "0 broken links"),
                        tool(
                            "docs-cmd-2",
                            32,
                            ToolCallKind.OTHER,
                            "deploy preview",
                            "Preview quota reached",
                            ToolCallStatus.FAILED,
                        ),
                        text("docs-reply-1", 33, MessageRole.AGENT, "Published the getting-started page."),
                    ),
            ),
    )
}

/** The three sample sessions as they are when the app starts. */
internal fun initialSampleStates(now: Long): Map<String, SessionScreenState> =
    mapOf(
        SampleIds.WEATHER to weatherState(now),
        SampleIds.API to apiState(now),
        SampleIds.DOCS to docsState(now),
    )
