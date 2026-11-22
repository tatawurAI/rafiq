package ai.tatawur.rafiq.bridge.hook

import ai.tatawur.rafiq.bridge.sessions.SessionException
import ai.tatawur.rafiq.bridge.store.DeviceStore
import ai.tatawur.rafiq.protocol.api.HookSetupRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HookSetupTest {
    @TempDir
    lateinit var dir: Path

    private val store = DeviceStore("jdbc:sqlite::memory:", Dispatchers.IO.limitedParallelism(1))

    @AfterEach
    fun close() = store.close()

    private fun setup(
        home: Path = dir.resolve("home"),
        beforeCommit: suspend () -> Unit = {},
    ) = HookSetup(
        HookSettings("/opt/rafiq-bridge/hooks/rafiq-hook.py", "/var/lib/rafiq"),
        store,
        home,
        beforeCommit = beforeCommit,
    )

    private fun project(name: String = "weather-app"): Path = Files.createDirectories(dir.resolve(name))

    private fun conflictCode(block: suspend () -> Unit): String? =
        runBlocking {
            try {
                block()
                null
            } catch (conflict: SessionException.Conflict) {
                conflict.code
            }
        }

    @Test
    fun install_writes_exactly_the_previewed_text_and_records_the_consent() =
        runBlocking {
            val project = project()
            val setup = setup()
            val preview = setup.preview(project.toString())
            val target = project.resolve(".claude/settings.local.json")
            assertFalse(preview.installed)
            assertFalse(Files.exists(target), "a preview writes nothing")

            val after = setup.install(project.toString(), HookSetupRequest(preview.previewHash), "dev_1")

            assertEquals(preview.fileText, Files.readString(target))
            assertTrue(after.preview.installed)
            val consent = store.hookConsent(project.toRealPath().toString())
            assertEquals(target.toRealPath().toString(), consent?.filePath)
            assertEquals(HookSetup.sha256(preview.fileText), consent?.writtenSha256)
            assertEquals("dev_1", consent?.deviceId)
        }

    @Test
    fun a_preview_hash_goes_stale_when_the_file_changes_after_it() {
        val project = project()
        val setup = setup()
        val preview = runBlocking { setup.preview(project.toString()) }
        Files.createDirectories(project.resolve(".claude"))
        Files.writeString(project.resolve(".claude/settings.local.json"), """{"model":"placeholder-model"}""")

        assertEquals(
            HookSetup.HOOKS_FILE_CHANGED,
            conflictCode { setup.install(project.toString(), HookSetupRequest(preview.previewHash), "dev_1") },
        )
        assertEquals(
            """{"model":"placeholder-model"}""",
            Files.readString(project.resolve(".claude/settings.local.json")),
        )
    }

    @Test
    fun remove_deletes_the_file_it_created_and_keeps_a_file_with_other_content() =
        runBlocking {
            val setup = setup()
            val empty = project("docs-site")
            setup.install(empty.toString(), HookSetupRequest(setup.preview(empty.toString()).previewHash), "dev_1")
            val removal = setup.preview(empty.toString())
            assertTrue(removal.installed)
            assertEquals("", removal.fileText)

            setup.remove(empty.toString(), HookSetupRequest(removal.previewHash))

            assertFalse(Files.exists(empty.resolve(".claude/settings.local.json")))
            assertNull(store.hookConsent(empty.toRealPath().toString()))

            val shared = project("api-server")
            Files.createDirectories(shared.resolve(".claude"))
            val theirs = "{\n  \"model\": \"placeholder-model\"\n}\n"
            Files.writeString(shared.resolve(".claude/settings.local.json"), theirs)
            setup.install(shared.toString(), HookSetupRequest(setup.preview(shared.toString()).previewHash), "dev_1")
            setup.remove(shared.toString(), HookSetupRequest(setup.preview(shared.toString()).previewHash))

            assertEquals(theirs, Files.readString(shared.resolve(".claude/settings.local.json")))
        }

    @Test
    fun remove_is_refused_when_our_entries_were_edited_by_hand() {
        val project = project()
        val setup = setup()
        val file = project.resolve(".claude/settings.local.json")
        runBlocking {
            setup.install(project.toString(), HookSetupRequest(setup.preview(project.toString()).previewHash), "dev_1")
        }
        Files.writeString(file, Files.readString(file).replace("900", "30"))
        val edited = Files.readString(file)
        val preview = runBlocking { setup.preview(project.toString()) }

        assertEquals(
            HookSetup.HOOKS_FILE_CHANGED,
            conflictCode { setup.remove(project.toString(), HookSetupRequest(preview.previewHash)) },
        )
        assertEquals(edited, Files.readString(file))
    }

    @Test
    fun the_home_folder_and_a_linked_claude_folder_are_never_written() {
        val home = Files.createDirectories(dir.resolve("home"))
        val setup = setup(home)
        val linked = project("linked")
        Files.createDirectories(home.resolve(".claude"))
        Files.createSymbolicLink(linked.resolve(".claude"), home.resolve(".claude"))

        for (path in listOf(home, linked, Path.of("/"))) {
            assertEquals("hooks_no_project", conflictCode { setup.preview(path.toString()) }, path.toString())
        }
        assertFalse(Files.exists(home.resolve(".claude/settings.local.json")))
    }

    @Test
    fun the_file_is_offered_for_git_exclude_and_added_once() =
        runBlocking {
            val project = project()
            Files.createDirectories(project.resolve(".git/info"))
            Files.writeString(project.resolve(".git/info/exclude"), "# local excludes\n*.log")
            val setup = setup()
            val preview = setup.preview(project.toString())
            assertTrue(preview.gitExcludeOffered)

            val after =
                setup.install(
                    project.toString(),
                    HookSetupRequest(preview.previewHash, addGitExclude = true),
                    "dev_1",
                )

            assertFalse(after.preview.gitExcludeOffered)
            assertEquals(
                "# local excludes\n*.log\n/.claude/settings.local.json\n",
                Files.readString(project.resolve(".git/info/exclude")),
            )
        }

    @Test
    fun a_linked_git_info_folder_or_exclude_file_is_refused_before_anything_is_written() {
        val elsewhere = Files.createDirectories(dir.resolve("elsewhere"))
        Files.writeString(elsewhere.resolve("exclude"), "# not yours\n")
        val linkedFolder = project("docs-site")
        Files.createDirectories(linkedFolder.resolve(".git"))
        Files.createSymbolicLink(linkedFolder.resolve(".git/info"), elsewhere)
        val linkedFile = project("api-server")
        Files.createDirectories(linkedFile.resolve(".git/info"))
        Files.createSymbolicLink(linkedFile.resolve(".git/info/exclude"), elsewhere.resolve("exclude"))
        val setup = setup()

        for (project in listOf(linkedFolder, linkedFile)) {
            val preview = runBlocking { setup.preview(project.toString()) }
            assertFalse(preview.gitExcludeOffered, project.toString())

            val code =
                conflictCode {
                    setup.install(project.toString(), HookSetupRequest(preview.previewHash, addGitExclude = true), "d")
                }

            assertEquals("hooks_no_project", code, project.toString())
            assertFalse(Files.exists(project.resolve(".claude/settings.local.json")), project.toString())
        }
        assertEquals("# not yours\n", Files.readString(elsewhere.resolve("exclude")))
    }

    @Test
    fun a_change_to_the_file_between_the_plan_and_the_write_is_kept_and_refused() {
        val project = project()
        val file = project.resolve(".claude/settings.local.json")
        val setup =
            setup(beforeCommit = {
                Files.createDirectories(file.parent)
                Files.writeString(file, """{"model":"placeholder-model"}""")
            })
        val preview = runBlocking { setup.preview(project.toString()) }

        assertEquals(
            HookSetup.HOOKS_FILE_CHANGED,
            conflictCode { setup.install(project.toString(), HookSetupRequest(preview.previewHash), "dev_1") },
        )
        assertEquals("""{"model":"placeholder-model"}""", Files.readString(file))
    }

    @Test
    fun changes_to_one_project_run_one_at_a_time() {
        val project = project()
        val inside = AtomicInteger()
        val mostInside = AtomicInteger()
        // Each change waits at the write for the other to arrive. One at a time, the other never does.
        val setup =
            setup(beforeCommit = {
                mostInside.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
                withTimeoutOrNull(OVERLAP_WAIT_MS) { awaitOther(inside) }
                inside.decrementAndGet()
            })
        val hash = runBlocking { setup.preview(project.toString()).previewHash }

        runBlocking {
            (1..2)
                .map {
                    async(Dispatchers.IO) {
                        runCatching { setup.install(project.toString(), HookSetupRequest(hash), "dev_$it") }
                    }
                }.awaitAll()
        }

        assertEquals(1, mostInside.get())
    }

    private suspend fun awaitOther(inside: AtomicInteger) {
        while (inside.get() < 2) delay(POLL_MS)
    }

    @Test
    fun a_hook_program_others_can_change_is_not_trusted() {
        val folder = Files.createDirectories(dir.resolve("install/hooks"))
        val program = Files.writeString(folder.resolve("rafiq-hook.py"), "#!/usr/bin/env python3\n")

        fun modes(
            file: String,
            dirMode: String,
        ) {
            Files.setPosixFilePermissions(program, PosixFilePermissions.fromString(file))
            Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString(dirMode))
        }
        val link = dir.resolve("install/link.py")
        Files.createSymbolicLink(link, program)

        modes("rwxr-xr-x", "rwxr-xr-x")
        assertTrue(HookSetup.isTrustedProgram(program))
        assertFalse(HookSetup.isTrustedProgram(link), "a link")
        modes("rwxrwxr-x", "rwxr-xr-x")
        assertFalse(HookSetup.isTrustedProgram(program), "group can write the program")
        modes("rwxr-xrwx", "rwxr-xr-x")
        assertFalse(HookSetup.isTrustedProgram(program), "others can write the program")
        modes("rwxr-xr-x", "rwxrwxr-x")
        assertFalse(HookSetup.isTrustedProgram(program), "group can write the folder")
        modes("rwxr-xr-x", "rwxr-xrwx")
        assertFalse(HookSetup.isTrustedProgram(program), "others can write the folder")
        modes("rw-r--r--", "rwxr-xr-x")
        assertFalse(HookSetup.isTrustedProgram(program), "not executable")
    }

    private companion object {
        const val OVERLAP_WAIT_MS = 2000L
        const val POLL_MS = 10L
    }
}
