package ai.tatawur.rafiq.sessions

import ai.tatawur.rafiq.client.ApprovalCard
import ai.tatawur.rafiq.client.CommandResult
import ai.tatawur.rafiq.client.ConnectionState
import ai.tatawur.rafiq.client.EchoState
import ai.tatawur.rafiq.client.SessionListItem
import ai.tatawur.rafiq.client.SessionRepository
import ai.tatawur.rafiq.client.SessionScreenState
import ai.tatawur.rafiq.client.ThreadTab
import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.ApprovalOption
import ai.tatawur.rafiq.protocol.ApprovalOptionKind
import ai.tatawur.rafiq.protocol.DeliveryState
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.Session
import ai.tatawur.rafiq.protocol.ThreadKind
import ai.tatawur.rafiq.protocol.api.HookSetupPreview
import ai.tatawur.rafiq.protocol.api.SendRefusal
import ai.tatawur.rafiq.protocol.api.TerminalHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private val approval =
        ApprovalCard(
            id = "a-1",
            sessionId = "s-1",
            threadId = "t-main",
            toolCallId = "c-1",
            description = "Run the tests?",
            options = listOf(ApprovalOption("once", ApprovalOptionKind.ALLOW_ONCE, "Allow once")),
            expiresAtMs = null,
        )

    private class FakeRepository(
        initial: SessionScreenState,
    ) : SessionRepository {
        val screen = MutableStateFlow(initial)
        var promptResult: CommandResult<Unit> = CommandResult.Ok(Unit)
        var approveResult: CommandResult<Unit> = CommandResult.Ok(Unit)
        val prompts = mutableListOf<String>()
        val clientIds = mutableListOf<String?>()

        override val connection: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Live)

        override fun sessions(): Flow<List<SessionListItem>> =
            screen.map { state -> listOfNotNull(state.session?.let { SessionListItem(it, null, state.openApprovals) }) }

        override fun session(sessionId: String): Flow<SessionScreenState> = screen

        override suspend fun agents(): CommandResult<List<String>> = CommandResult.Ok(emptyList())

        override suspend fun createSession(
            agent: String,
            projectPath: String,
            title: String?,
        ): CommandResult<Session> = CommandResult.NotFound

        override suspend fun prompt(
            sessionId: String,
            text: String,
            clientId: String?,
        ): CommandResult<Unit> {
            prompts += text
            clientIds += clientId
            return promptResult
        }

        override suspend fun cancel(
            sessionId: String,
            threadId: String,
        ): CommandResult<Unit> = CommandResult.Ok(Unit)

        override suspend fun approve(
            approvalId: String,
            optionId: String,
        ): CommandResult<Unit> = approveResult

        override suspend fun hookSetup(sessionId: String): CommandResult<HookSetupPreview> = CommandResult.NotFound

        override suspend fun installHooks(
            sessionId: String,
            previewHash: String,
            addGitExclude: Boolean,
        ): CommandResult<Unit> = CommandResult.NotFound

        override suspend fun removeHooks(
            sessionId: String,
            previewHash: String,
        ): CommandResult<Unit> = CommandResult.NotFound

        override suspend fun terminalHistory(
            sessionId: String,
            window: Int?,
            lines: Int,
        ): CommandResult<TerminalHistory> = CommandResult.NotFound
    }

    private fun screen(
        mainStatus: AgentState = AgentState.DONE,
        approvals: List<ApprovalCard> = emptyList(),
        inTmux: Boolean = false,
    ) = SessionScreenState(
        sessionId = "s-1",
        session =
            Session("s-1", "claude-code", "/srv/weather-app", "weather-app", mainStatus, 0, 0, terminal = inTmux),
        threads =
            listOf(
                ThreadTab("t-main", null, ThreadKind.MAIN, "Main", mainStatus, approvals.size),
                ThreadTab("t-help", "t-main", ThreadKind.SUBAGENT, "Test writer", AgentState.WORKING, 0),
            ),
        openApprovals = approvals,
    )

    @Test
    fun a_busy_bridge_keeps_your_draft_and_says_the_agent_is_still_working() =
        runTest(dispatcher) {
            val repository = FakeRepository(screen()).apply { promptResult = CommandResult.Busy }
            val viewModel = SessionViewModel(repository, "s-1")
            backgroundScope.launch { viewModel.state.collect {} }

            viewModel.updateDraft("Run the tests")
            viewModel.send()

            assertEquals("Run the tests", viewModel.state.value.draft)
            assertEquals(Feedback.StillWorking, viewModel.state.value.feedback)
        }

    @Test
    fun a_sent_prompt_clears_the_draft_without_echoing_it_into_the_timeline() =
        runTest(dispatcher) {
            val repository = FakeRepository(screen())
            val viewModel = SessionViewModel(repository, "s-1")
            backgroundScope.launch { viewModel.state.collect {} }

            viewModel.updateDraft("  Run the tests ")
            viewModel.send()

            assertEquals(listOf("Run the tests"), repository.prompts)
            assertEquals("", viewModel.state.value.draft)
            assertTrue(
                viewModel.state.value.timeline
                    .isEmpty(),
                "the message appears only when the bridge sends it back",
            )
        }

    @Test
    fun an_answered_approval_stays_disabled_until_the_repository_drops_it() =
        runTest(dispatcher) {
            val repository = FakeRepository(screen(AgentState.WAITING_ON_YOU, listOf(approval)))
            val viewModel = SessionViewModel(repository, "s-1")
            backgroundScope.launch { viewModel.state.collect {} }

            viewModel.approve("a-1", "once")
            assertEquals(setOf("a-1"), viewModel.state.value.answering)
            assertEquals(approval, viewModel.state.value.pinned)

            repository.screen.update { it.copy(openApprovals = emptyList()) }
            assertEquals(emptySet(), viewModel.state.value.answering)
            assertEquals(null, viewModel.state.value.pinned)
        }

    @Test
    fun a_failed_answer_enables_the_approval_again() =
        runTest(dispatcher) {
            val repository =
                FakeRepository(screen(AgentState.WAITING_ON_YOU, listOf(approval))).apply {
                    approveResult = CommandResult.Offline
                }
            val viewModel = SessionViewModel(repository, "s-1")
            backgroundScope.launch { viewModel.state.collect {} }

            viewModel.approve("a-1", "once")

            assertEquals(emptySet(), viewModel.state.value.answering)
            assertEquals(Feedback.Offline, viewModel.state.value.feedback)
        }

    // 409 approval_gone: someone answered on another device or the approval was cancelled.
    @Test
    fun an_approval_the_bridge_says_is_gone_stays_disabled_until_its_resolution_clears_it() =
        runTest(dispatcher) {
            val repository =
                FakeRepository(screen(AgentState.WAITING_ON_YOU, listOf(approval))).apply {
                    approveResult = CommandResult.Gone
                }
            val viewModel = SessionViewModel(repository, "s-1")
            backgroundScope.launch { viewModel.state.collect {} }

            viewModel.approve("a-1", "once")

            assertEquals(setOf("a-1"), viewModel.state.value.answering)
            assertEquals(Feedback.AlreadyAnswered, viewModel.state.value.feedback)

            repository.screen.update { it.copy(openApprovals = emptyList()) }
            assertEquals(emptySet(), viewModel.state.value.answering)
        }

    @Test
    fun the_view_falls_back_to_the_main_thread_when_the_open_tab_disappears() =
        runTest(dispatcher) {
            val repository = FakeRepository(screen())
            val viewModel = SessionViewModel(repository, "s-1")
            backgroundScope.launch { viewModel.state.collect {} }

            viewModel.selectThread("t-help")
            assertEquals(
                "t-help",
                viewModel.state.value.selectedThread
                    ?.id,
            )

            repository.screen.update { it.copy(threads = it.threads.take(1)) }
            assertEquals(
                "t-main",
                viewModel.state.value.selectedThread
                    ?.id,
            )
        }

    @Test
    fun a_prompt_to_a_session_in_tmux_goes_exactly_as_typed_and_shows_until_the_agent_has_it() =
        runTest(dispatcher) {
            // The agent is busy: in tmux your server queues the prompt instead of refusing it.
            val repository = FakeRepository(screen(mainStatus = AgentState.WORKING, inTmux = true))
            val viewModel = SessionViewModel(repository, "s-1") { "c-1" }
            backgroundScope.launch { viewModel.state.collect {} }

            viewModel.updateDraft(" !ls -la ")
            viewModel.send()

            assertEquals(listOf(" !ls -la "), repository.prompts)
            assertEquals(listOf<String?>("c-1"), repository.clientIds)
            assertEquals(
                listOf(EchoState.SENT),
                viewModel.state.value.echoes
                    .map { it.state },
            )

            repository.screen.update { it.copy(deliveries = mapOf("c-1" to delivery(DeliveryState.QUEUED))) }
            assertEquals(
                listOf(EchoState.QUEUED),
                viewModel.state.value.echoes
                    .map { it.state },
            )

            repository.screen.update { it.copy(deliveries = mapOf("c-1" to delivery(DeliveryState.DELIVERED))) }
            assertEquals(emptyList(), viewModel.state.value.echoes)
        }

    @Test
    fun a_refused_send_keeps_your_draft_and_says_why_in_the_composer() =
        runTest(dispatcher) {
            val repository =
                FakeRepository(screen(inTmux = true)).apply {
                    promptResult = CommandResult.Failed(SendRefusal.INPUT_NOT_EMPTY, "Half-typed text.")
                }
            val viewModel = SessionViewModel(repository, "s-1")
            backgroundScope.launch { viewModel.state.collect {} }

            viewModel.updateDraft("/compact")
            viewModel.send()

            val state = viewModel.state.value
            assertEquals("/compact", state.draft)
            assertEquals(ComposerRefusal(SendRefusal.INPUT_NOT_EMPTY, "Half-typed text."), state.refusal)
            assertEquals(null, state.feedback)
            assertEquals(emptyList(), state.echoes)
            assertEquals(false, state.offerHooks)
        }

    @Test
    fun a_send_refused_for_missing_hooks_offers_the_hook_setup() =
        runTest(dispatcher) {
            val repository =
                FakeRepository(screen(inTmux = true)).apply {
                    promptResult = CommandResult.Failed(SendRefusal.HOOKS_NOT_INSTALLED, "No hooks.")
                }
            val viewModel = SessionViewModel(repository, "s-1")
            backgroundScope.launch { viewModel.state.collect {} }

            viewModel.updateDraft("Run the tests")
            viewModel.send()

            assertTrue(viewModel.state.value.offerHooks)
        }

    private fun delivery(state: DeliveryState) = EventPayload.PromptDelivery("c-1", "t-main", state)
}
