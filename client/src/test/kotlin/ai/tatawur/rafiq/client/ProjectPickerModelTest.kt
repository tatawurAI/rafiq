package ai.tatawur.rafiq.client

import ai.tatawur.rafiq.protocol.Session
import ai.tatawur.rafiq.protocol.api.HookSetupPreview
import ai.tatawur.rafiq.protocol.api.ProjectErrorCodes
import ai.tatawur.rafiq.protocol.api.ProjectFolderListing
import ai.tatawur.rafiq.protocol.api.ProjectsOverview
import ai.tatawur.rafiq.protocol.api.TerminalHistory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** The project folder on "New session" (#35): pick, browse one level at a time, or type and get checked. */
class ProjectPickerModelTest {
    private val folders =
        mapOf(
            "/srv" to listOf("api-server", "weather-app"),
            "/srv/weather-app" to listOf("src"),
        )

    // Background work is not run by advanceUntilIdle, so time is moved past the check delay by hand.
    private fun TestScope.settle() {
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
    }

    private fun TestScope.model(repository: FakeProjects = FakeProjects(folders)) =
        ProjectPickerModel(repository, backgroundScope, checkDelay = 400.milliseconds).also {
            it.load()
            testScheduler.runCurrent()
        }

    @Test
    fun a_typed_path_is_checked_once_typing_pauses_and_a_missing_folder_blocks_start() =
        runTest {
            val repository = FakeProjects(folders)
            val model = model(repository)

            model.type("srv/weather-app")
            assertEquals(PathCheck.NotAbsolute, model.state.value.check)

            model.type("/srv/weather-ap")
            model.type("/srv/weather-app")
            testScheduler.advanceTimeBy(399)
            assertEquals(PathCheck.Checking, model.state.value.check)
            assertFalse(model.state.value.check.allowsStart)
            assertEquals(emptyList(), repository.asked)

            testScheduler.advanceTimeBy(2)
            testScheduler.runCurrent()
            assertEquals(PathCheck.Folder, model.state.value.check)
            // Only the path you stopped at was asked about.
            assertEquals(listOf("/srv/weather-app"), repository.asked)

            model.type("/srv/wether-app")
            settle()
            assertEquals(PathCheck.NotAFolder, model.state.value.check)
            assertFalse(model.state.value.check.allowsStart)
        }

    @Test
    fun a_path_outside_the_roots_can_still_start_and_your_server_checks_it_then() =
        runTest {
            val model = model()

            model.type("/opt/docs-site")
            settle()

            assertEquals(PathCheck.OutsideRoots, model.state.value.check)
            assertTrue(model.state.value.check.allowsStart)
        }

    @Test
    fun browsing_goes_one_level_at_a_time_and_up_stops_at_the_root() =
        runTest {
            val model = model()

            model.open("/srv")
            testScheduler.runCurrent()
            assertEquals(
                listOf("api-server", "weather-app"),
                model.state.value.browsing
                    ?.listing
                    ?.folders,
            )

            model.openChild("weather-app")
            testScheduler.runCurrent()
            assertEquals(
                listOf("src"),
                model.state.value.browsing
                    ?.listing
                    ?.folders,
            )

            model.chooseBrowsed()
            assertEquals("/srv/weather-app", model.state.value.path)
            assertEquals(PathCheck.Folder, model.state.value.check)

            model.up()
            testScheduler.runCurrent()
            assertEquals(
                "/srv",
                model.state.value.browsing
                    ?.path,
            )
            model.up()
            assertNull(model.state.value.browsing)
        }

    @Test
    fun a_recent_folder_is_picked_without_typing() =
        runTest {
            val model = model()

            model.choose(
                model.state.value.recent
                    .first(),
            )

            assertEquals("/srv/weather-app", model.state.value.path)
            assertTrue(model.state.value.check.allowsStart)
        }

    private class FakeProjects(
        private val folders: Map<String, List<String>>,
    ) : SessionRepository {
        val asked = mutableListOf<String>()

        override suspend fun projects(): CommandResult<ProjectsOverview> =
            CommandResult.Ok(ProjectsOverview(recent = listOf("/srv/weather-app"), roots = listOf("/srv")))

        override suspend fun projectFolders(path: String): CommandResult<ProjectFolderListing> {
            asked += path
            return when {
                path in folders -> CommandResult.Ok(ProjectFolderListing(path, folders.getValue(path)))
                path.startsWith("/srv/") -> CommandResult.NotFound
                else -> CommandResult.Failed(ProjectErrorCodes.OUTSIDE_ROOTS, "Not under a project root.")
            }
        }

        override val connection: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Live)

        override fun sessions(): Flow<List<SessionListItem>> = emptyFlow()

        override fun session(sessionId: String): Flow<SessionScreenState> = emptyFlow()

        override suspend fun agents(): CommandResult<List<String>> = CommandResult.NotFound

        override suspend fun createSession(
            agent: String,
            projectPath: String,
            title: String?,
            modeId: String?,
        ): CommandResult<Session> = CommandResult.NotFound

        override suspend fun setMode(
            sessionId: String,
            modeId: String,
        ): CommandResult<Unit> = CommandResult.NotFound

        override suspend fun prompt(
            sessionId: String,
            text: String,
            clientId: String?,
        ): CommandResult<Unit> = CommandResult.NotFound

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

        override suspend fun cancel(
            sessionId: String,
            threadId: String,
        ): CommandResult<Unit> = CommandResult.NotFound

        override suspend fun approve(
            approvalId: String,
            optionId: String,
        ): CommandResult<Unit> = CommandResult.NotFound
    }
}
