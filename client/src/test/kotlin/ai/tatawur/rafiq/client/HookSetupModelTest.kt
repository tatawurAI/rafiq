package ai.tatawur.rafiq.client

import ai.tatawur.rafiq.protocol.api.ProjectFolderListing
import ai.tatawur.rafiq.protocol.api.ProjectsOverview
import ai.tatawur.rafiq.client.sync.BridgeApi
import ai.tatawur.rafiq.protocol.Session
import ai.tatawur.rafiq.protocol.api.HookSetupPreview
import ai.tatawur.rafiq.protocol.api.TerminalHistory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The hooks are written only after your tap, and only as the preview you saw (#44). */
class HookSetupModelTest {
    private fun preview(
        hash: String,
        installed: Boolean = false,
    ) = HookSetupPreview(
        projectPath = "/srv/weather-app",
        targetFile = "/srv/weather-app/.claude/settings.local.json",
        fileText = "{}",
        previewHash = hash,
        events = listOf("Stop"),
        sends = "Each event, to a local socket on your server.",
        installed = installed,
        gitExcludeOffered = true,
    )

    @Test
    fun nothing_is_written_until_you_tap_and_the_tap_sends_the_previewed_hash_and_your_choice() =
        runTest {
            val repository = FakeHooks(preview("h1"))
            val model = HookSetupModel(repository, "tmux-weather-app", backgroundScope)

            model.load()
            testScheduler.runCurrent()
            model.setGitExclude(true)
            assertEquals(emptyList(), repository.writes)

            repository.preview = preview("h2", installed = true)
            model.confirm()
            testScheduler.runCurrent()

            assertEquals(listOf("install h1 exclude=true"), repository.writes)
            val ready = assertIs<HookSetupState.Ready>(model.state.value)
            assertEquals(HookSetupNotice.Installed, ready.notice)
            assertEquals(true, ready.preview.installed)
        }

    @Test
    fun a_file_changed_on_your_server_shows_the_new_preview_and_waits_for_another_tap() =
        runTest {
            val repository = FakeHooks(preview("h1"))
            val model = HookSetupModel(repository, "tmux-weather-app", backgroundScope)
            model.load()
            testScheduler.runCurrent()

            repository.preview = preview("h2")
            repository.writeResult = CommandResult.Failed(BridgeApi.HOOKS_FILE_CHANGED, "The file changed.")
            model.confirm()
            testScheduler.runCurrent()

            val ready = assertIs<HookSetupState.Ready>(model.state.value)
            assertEquals("h2", ready.preview.previewHash)
            assertEquals(HookSetupNotice.FileChanged, ready.notice)
            assertEquals(listOf("install h1 exclude=false"), repository.writes)
        }

    @Test
    fun installed_hooks_are_removed_with_one_tap() =
        runTest {
            val repository = FakeHooks(preview("h1", installed = true))
            val model = HookSetupModel(repository, "tmux-weather-app", backgroundScope)
            model.load()
            testScheduler.runCurrent()

            model.confirm()
            testScheduler.runCurrent()

            assertEquals(listOf("remove h1"), repository.writes)
        }

    private class FakeHooks(
        var preview: HookSetupPreview,
    ) : SessionRepository {
        val writes = mutableListOf<String>()
        var writeResult: CommandResult<Unit> = CommandResult.Ok(Unit)

        override suspend fun hookSetup(sessionId: String): CommandResult<HookSetupPreview> = CommandResult.Ok(preview)

        override suspend fun installHooks(
            sessionId: String,
            previewHash: String,
            addGitExclude: Boolean,
        ): CommandResult<Unit> = writeResult.also { writes += "install $previewHash exclude=$addGitExclude" }

        override suspend fun removeHooks(
            sessionId: String,
            previewHash: String,
        ): CommandResult<Unit> = writeResult.also { writes += "remove $previewHash" }

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

        override suspend fun projects(): CommandResult<ProjectsOverview> = CommandResult.NotFound

        override suspend fun projectFolders(path: String): CommandResult<ProjectFolderListing> = CommandResult.NotFound

        override suspend fun prompt(
            sessionId: String,
            text: String,
            clientId: String?,
        ): CommandResult<Unit> = CommandResult.NotFound

        override suspend fun cancel(
            sessionId: String,
            threadId: String,
        ): CommandResult<Unit> = CommandResult.NotFound

        override suspend fun approve(
            approvalId: String,
            optionId: String,
        ): CommandResult<Unit> = CommandResult.NotFound

        override suspend fun terminalHistory(
            sessionId: String,
            window: Int?,
            lines: Int,
        ): CommandResult<TerminalHistory> = CommandResult.NotFound
    }
}
