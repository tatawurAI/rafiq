package ai.tatawur.rafiq.bridge.hook

import ai.tatawur.rafiq.bridge.sessions.SessionException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * The files [HookSetup] reads and writes in a project: the folder itself, `.claude/settings.local.json` and
 * `.git/info/exclude`. Every check that keeps the bridge away from your user-level settings lives here.
 *
 * @param home your home folder, which is never treated as a project.
 */
internal class ProjectFiles(
    private val home: Path,
) {
    /** The real path of the project. Refuses a missing folder, the file system root and your home folder. */
    fun projectFolder(projectPath: String): Path {
        val given = Path.of(projectPath)
        if (!given.isAbsolute || !Files.isDirectory(given)) {
            throw SessionException.Conflict(
                NO_PROJECT,
                "The session's folder $projectPath is not a folder on your server.",
            )
        }
        val real = given.toRealPath()
        if (real.parent == null || real == realHome()) {
            throw SessionException.Conflict(
                NO_PROJECT,
                "Rafiq installs hooks per project only, never for $real, where your user-level settings live.",
            )
        }
        return real
    }

    /** The settings file in [project]. Refuses a `.claude` folder or a settings file that is a link. */
    fun targetIn(project: Path): Path {
        val folder = project.resolve(".claude")
        val target = folder.resolve(HookSetup.SETTINGS_FILE)
        val notAFolder = Files.exists(folder) && !Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)
        if (notAFolder || Files.isSymbolicLink(folder) || Files.isSymbolicLink(target)) {
            throw SessionException.Conflict(
                NO_PROJECT,
                "$folder or its settings file is a link. Rafiq only writes real files.",
            )
        }
        return target
    }

    /** The file text now, or null when there is no file. Never follows a link: [targetIn] refused those. */
    fun read(target: Path): String? =
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) Files.readString(target) else null

    /**
     * Replaces [target] with [text], or deletes it for null. The text goes to a file next to the target and is moved
     * into place, so Claude Code never reads half a file. Right before the move, [unchanged] gets the file as it is
     * then, and nothing is changed if it says no.
     *
     * @return false when [unchanged] refused.
     */
    fun replace(
        target: Path,
        text: String?,
        unchanged: (String?) -> Boolean,
    ): Boolean =
        if (text == null) {
            unchanged(read(target)).also { if (it) Files.deleteIfExists(target) }
        } else {
            write(target, text, unchanged)
        }

    private fun write(
        target: Path,
        text: String,
        unchanged: (String?) -> Boolean,
    ): Boolean {
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".settings.local", ".tmp")
        try {
            if (Files.exists(target)) Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(target))
            Files.writeString(temporary, text)
            val ok = unchanged(read(target))
            if (ok) Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return ok
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /**
     * `.git/info/exclude` in a git repository, or null when there is none. Also null when `.git`, `.git/info` or the
     * file itself is a link, so the bridge never appends to a file somewhere else.
     */
    private fun excludeFile(project: Path): Path? {
        val git = project.resolve(".git")
        val info = git.resolve("info")
        val exclude = info.resolve("exclude")
        val safe =
            Files.isDirectory(git, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(info) &&
                !Files.isSymbolicLink(exclude)
        return exclude.takeIf { safe }
    }

    /** True for a git repository whose `.git/info/exclude` does not list the settings file yet. */
    fun gitExcludeOffered(project: Path): Boolean {
        val exclude = excludeFile(project) ?: return false
        return !Files.exists(exclude) || Files.readAllLines(exclude).none { it.trim() in EXCLUDE_LINES }
    }

    /** Refuses before anything is written when the exclude file cannot be written safely. */
    fun checkGitExclude(project: Path) {
        val git = project.resolve(".git")
        if (Files.isDirectory(git, LinkOption.NOFOLLOW_LINKS) && excludeFile(project) == null) {
            throw SessionException.Conflict(
                NO_PROJECT,
                "$git/info or its exclude file is a link. Rafiq only writes real files. Add the line yourself.",
            )
        }
    }

    /** Lists the settings file in `.git/info/exclude`, once. */
    fun addGitExclude(project: Path) {
        if (!gitExcludeOffered(project)) return
        val exclude = excludeFile(project) ?: return
        Files.createDirectories(exclude.parent)
        val existing = if (Files.exists(exclude)) Files.readString(exclude) else ""
        val prefix = if (existing.isEmpty() || existing.endsWith("\n")) "" else "\n"
        Files.writeString(
            exclude,
            prefix + EXCLUDE_LINE + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    private fun realHome(): Path = runCatching { home.toRealPath() }.getOrDefault(home.toAbsolutePath().normalize())

    private companion object {
        const val NO_PROJECT = "hooks_no_project"
        const val EXCLUDE_LINE = "/.claude/${HookSetup.SETTINGS_FILE}"
        val EXCLUDE_LINES = setOf(EXCLUDE_LINE, ".claude/${HookSetup.SETTINGS_FILE}")
    }
}
