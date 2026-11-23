package ai.tatawur.rafiq.bridge.projects

import ai.tatawur.rafiq.bridge.sessions.SessionException
import ai.tatawur.rafiq.protocol.api.MAX_PROJECT_FOLDERS
import ai.tatawur.rafiq.protocol.api.ProjectErrorCodes
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The project picker's catalog (#35): it lists folders under the roots and nothing outside them. */
class FolderCatalogTest {
    @TempDir
    lateinit var dir: Path

    private val root: Path by lazy { Files.createDirectories(dir.resolve("code")) }
    private val outside: Path by lazy { Files.createDirectories(dir.resolve("private/keys")) }

    private fun catalog(recent: List<String> = emptyList()) = FolderCatalog(listOf(root), recentPaths = { recent })

    private fun folder(name: String): Path = Files.createDirectories(root.resolve(name))

    private fun refusal(path: String): SessionException =
        assertThrows<SessionException> { runBlocking { catalog().folders(path) } }

    @Test
    fun a_root_lists_its_folders_sorted_without_hidden_ones_or_files() =
        runBlocking {
            folder("weather-app")
            folder("api-server")
            folder(".cache")
            Files.writeString(root.resolve("notes.txt"), "not a folder")

            val listing = catalog().folders(root.toString())

            assertEquals(listOf("api-server", "weather-app"), listing.folders)
            assertEquals(root.toRealPath().toString(), listing.path)
            assertEquals(false, listing.truncated)
        }

    @Test
    fun dot_dot_that_climbs_out_of_a_root_is_refused() {
        folder("weather-app")
        outside

        val refused = refusal("$root/weather-app/../../private")

        assertEquals(ProjectErrorCodes.OUTSIDE_ROOTS, refused.code)
        assertTrue(refused is SessionException.Forbidden)
    }

    @Test
    fun a_symlink_inside_a_root_that_points_out_of_it_is_refused_and_not_listed() {
        folder("weather-app")
        Files.createSymbolicLink(root.resolve("keys"), outside)

        val refused = refusal("$root/keys")

        assertEquals(ProjectErrorCodes.OUTSIDE_ROOTS, refused.code)
        assertEquals(listOf("weather-app"), runBlocking { catalog().folders(root.toString()) }.folders)
    }

    @Test
    fun a_path_under_no_root_is_refused_whether_or_not_it_exists() {
        assertEquals(ProjectErrorCodes.OUTSIDE_ROOTS, refusal(outside.toString()).code)
        assertEquals(ProjectErrorCodes.OUTSIDE_ROOTS, refusal(dir.resolve("nowhere").toString()).code)
        // A sibling whose name starts like the root is not under it.
        assertEquals(ProjectErrorCodes.OUTSIDE_ROOTS, refusal(dir.resolve("code-old").toString()).code)
    }

    @Test
    fun a_file_or_a_missing_folder_under_a_root_is_not_a_folder() {
        Files.writeString(root.resolve("notes.txt"), "not a folder")

        val file = refusal("$root/notes.txt")
        val missing = refusal("$root/docs-site")

        assertEquals(ProjectErrorCodes.NOT_A_FOLDER, file.code)
        assertTrue(file is SessionException.NotFound)
        assertEquals(ProjectErrorCodes.NOT_A_FOLDER, missing.code)
    }

    @Test
    fun a_huge_folder_is_cut_at_the_cap_and_says_so() =
        runBlocking {
            repeat(MAX_PROJECT_FOLDERS + 1) { folder("project-%04d".format(it)) }

            val listing = catalog().folders(root.toString())

            assertEquals(MAX_PROJECT_FOLDERS, listing.folders.size)
            assertTrue(listing.truncated)
        }

    @Test
    fun recent_projects_keep_their_order_once_each_and_drop_folders_that_are_gone() =
        runBlocking {
            val weather = folder("weather-app").toString()
            val docs = folder("docs-site").toString()
            val gone = root.resolve("api-server").toString()

            val overview = catalog(recent = listOf(docs, gone, weather, docs)).overview()

            assertEquals(listOf(docs, weather), overview.recent)
            assertEquals(listOf(root.toRealPath().toString()), overview.roots)
        }
}
