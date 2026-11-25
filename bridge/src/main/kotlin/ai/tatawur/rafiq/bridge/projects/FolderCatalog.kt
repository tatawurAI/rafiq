package ai.tatawur.rafiq.bridge.projects

import ai.tatawur.rafiq.bridge.sessions.SessionException
import ai.tatawur.rafiq.protocol.api.MAX_PROJECT_FOLDERS
import ai.tatawur.rafiq.protocol.api.ProjectErrorCodes
import ai.tatawur.rafiq.protocol.api.ProjectFolderListing
import ai.tatawur.rafiq.protocol.api.ProjectsOverview
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * The [ProjectCatalog] over your server's folders: recent projects from the event log, and one level at a time
 * under the project roots from `RAFIQ_PROJECT_ROOTS`.
 *
 * A path is checked twice. Its plain form, with `..` folded away, must lie under a root before the bridge looks
 * at the disk, so a path outside every root is refused the same way whether it exists or not. Then its real
 * path, with every symlink followed, must lie under a root's real path, so a link inside a root that points
 * out of it is refused too.
 *
 * @param roots the folders you may browse, as absolute paths. Empty means none.
 * @param recentPaths the project folders of earlier sessions, newest first, as the event log has them.
 * @param ioDispatcher runs the file system calls.
 */
class FolderCatalog(
    roots: List<Path>,
    private val recentPaths: suspend () -> List<String>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ProjectCatalog {
    private val roots = roots.map { it.toAbsolutePath().normalize() }

    override suspend fun overview(): ProjectsOverview {
        val candidates = recentPaths()
        return withContext(ioDispatcher) {
            ProjectsOverview(
                recent =
                    candidates
                        .asSequence()
                        .distinct()
                        .filter(::isExistingFolder)
                        .take(MAX_RECENT_PROJECTS)
                        .toList(),
                roots = realRoots().map(Path::toString),
            )
        }
    }

    override suspend fun folders(path: String): ProjectFolderListing =
        withContext(ioDispatcher) {
            val folder = resolveUnderRoots(path)
            list(folder)
        }

    /**
     * The real path of [raw], once it is known to be an existing folder under a root.
     *
     * @throws SessionException.Forbidden when [raw] is outside every root, before or after symlinks.
     * @throws SessionException.NotFound when nothing is there, or it is not a folder.
     */
    private fun resolveUnderRoots(raw: String): Path {
        val realRoots = realRoots()
        // Checked before the disk is touched, so a path outside the roots reveals nothing about what exists.
        val plain = absolutePath(raw)
        requireUnder(plain, roots + realRoots)
        val real = realPathOrNull(plain) ?: throw notAFolder()
        requireUnder(real, realRoots)
        if (!Files.isDirectory(real)) throw notAFolder()
        return real
    }

    private fun absolutePath(raw: String): Path =
        try {
            Path.of(raw).takeIf { it.isAbsolute }?.normalize()
        } catch (_: InvalidPathException) {
            null
        } ?: throw SessionException.Invalid("invalid_path", "path must be an absolute folder path.")

    private fun requireUnder(
        path: Path,
        anyOf: List<Path>,
    ) {
        if (anyOf.none(path::startsWith)) {
            throw SessionException.Forbidden(
                ProjectErrorCodes.OUTSIDE_ROOTS,
                "That folder is not under a project root. Add its root to RAFIQ_PROJECT_ROOTS to browse it.",
            )
        }
    }

    /** The folders directly in [folder], sorted, without hidden ones or links that lead out of the roots. */
    private fun list(folder: Path): ProjectFolderListing {
        val realRoots = realRoots()
        // A folder with a huge number of entries is not read to the end.
        val entries =
            try {
                Files.newDirectoryStream(folder).use { it.asSequence().take(MAX_SCANNED_ENTRIES + 1).toList() }
            } catch (_: IOException) {
                throw notAFolder()
            } catch (_: DirectoryIteratorException) {
                throw notAFolder()
            }
        val names =
            entries
                .take(MAX_SCANNED_ENTRIES)
                .filter { isFolderUnderRoots(it, realRoots) }
                .mapNotNull { it.fileName?.toString() }
                .filterNot { it.startsWith(".") }
                .sorted()
        val truncated = entries.size > MAX_SCANNED_ENTRIES || names.size > MAX_PROJECT_FOLDERS
        return ProjectFolderListing(folder.toString(), names.take(MAX_PROJECT_FOLDERS), truncated)
    }

    private fun isFolderUnderRoots(
        entry: Path,
        realRoots: List<Path>,
    ): Boolean =
        when {
            !Files.isDirectory(entry) -> false
            !Files.isSymbolicLink(entry) -> true
            else -> realPathOrNull(entry)?.let { real -> realRoots.any(real::startsWith) } == true
        }

    /** The roots as they are on disk now. A root that is gone is left out. */
    private fun realRoots(): List<Path> =
        roots.mapNotNull { root -> realPathOrNull(root)?.takeIf { Files.isDirectory(it) } }

    private fun notAFolder() =
        SessionException.NotFound(ProjectErrorCodes.NOT_A_FOLDER, "There is no folder at that path.")

    private companion object {
        /** How many recent projects the overview lists. */
        const val MAX_RECENT_PROJECTS = 20

        /** How many entries of one folder are looked at before the listing is cut short. */
        const val MAX_SCANNED_ENTRIES = 20_000
    }
}

private fun realPathOrNull(path: Path): Path? =
    try {
        path.toRealPath()
    } catch (_: IOException) {
        null
    }

private fun isExistingFolder(raw: String): Boolean =
    try {
        Files.isDirectory(Path.of(raw))
    } catch (_: InvalidPathException) {
        false
    }
