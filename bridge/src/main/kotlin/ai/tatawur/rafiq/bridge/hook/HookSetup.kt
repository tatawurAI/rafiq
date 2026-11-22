package ai.tatawur.rafiq.bridge.hook

import ai.tatawur.rafiq.bridge.sessions.SessionException
import ai.tatawur.rafiq.protocol.api.HookSetupPreview
import ai.tatawur.rafiq.protocol.api.HookSetupRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/** One recorded agreement to install the hooks in a project. */
data class HookConsent(
    val projectPath: String,
    val filePath: String,
    /** SHA-256 of the file text the bridge wrote, as lowercase hex. */
    val writtenSha256: String,
    val deviceId: String,
    val atMs: Long,
)

/**
 * What an install or remove did: the preview of the new state, and the SHA-256 of the file text written, or null
 * when the file was deleted.
 */
data class HookChange(
    val preview: HookSetupPreview,
    val writtenSha256: String?,
)

/** Where the bridge keeps hook consents. The device store implements it. */
interface HookConsentStore {
    suspend fun recordHookConsent(consent: HookConsent)

    suspend fun hookConsent(projectPath: String): HookConsent?

    suspend fun forgetHookConsent(projectPath: String)
}

/**
 * Shows, installs and removes the reader's hooks in one project's `.claude/settings.local.json` (issue #44).
 * Nothing is written without a preview hash from a matching [preview], so you always agree to the exact text.
 *
 * The bridge never writes the user-level settings: a project at the root or in your home folder is refused, and
 * so is a `.claude` folder or settings file that is a symbolic link, since either could point at that file.
 *
 * Install and remove for one project run one at a time, and the file is read again right before it is replaced. If
 * it changed since the plan, nothing is written.
 *
 * @param home your home folder, which is never treated as a project.
 * @param beforeCommit runs right before the last check and the write. Tests use it to change the file in between.
 */
class HookSetup(
    private val settings: HookSettings,
    private val consents: HookConsentStore,
    private val home: Path = Path.of(System.getProperty("user.home")),
    private val clock: Clock = Clock.systemUTC(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val beforeCommit: suspend () -> Unit = {},
) {
    private val files = ProjectFiles(home)
    private val locks = ConcurrentHashMap<Path, Mutex>()

    @Suppress("LongParameterList") // A value holder for one plan. Each field is read by install, remove or preview.
    private class Plan(
        val project: Path,
        val target: Path,
        val installed: Boolean,
        /** The file text the plan was made from. Null when there was no file. */
        val current: String?,
        /** The file text after the change. Null means the file is deleted. */
        val after: String?,
        /** Removing would also take entries install did not write. */
        val changed: Boolean,
        val hash: String,
    )

    /** What installing (or, when already installed, removing) would do. Writes nothing. */
    suspend fun preview(projectPath: String): HookSetupPreview = withContext(io) { previewOf(plan(projectPath)) }

    /**
     * Installs the hooks, if [request] carries the hash of the current install preview.
     *
     * @throws SessionException.Conflict `hooks_file_changed` when the file or the plan changed since the preview.
     */
    suspend fun install(
        projectPath: String,
        request: HookSetupRequest,
        deviceId: String,
    ): HookChange =
        locked(projectPath) {
            val plan = plan(projectPath, forRemove = false)
            if (!MessageDigest.isEqual(plan.hash.toByteArray(), request.previewHash.toByteArray())) throw changed()
            if (request.addGitExclude) files.checkGitExclude(plan.project)
            val text = plan.after ?: error("An install always leaves a file.")
            commit(plan, text)
            if (request.addGitExclude) files.addGitExclude(plan.project)
            consents.recordHookConsent(
                HookConsent(plan.project.toString(), plan.target.toString(), sha256(text), deviceId, clock.millis()),
            )
            HookChange(previewOf(plan(projectPath)), sha256(text))
        }

    /**
     * Removes exactly the bridge's entries, if [request] carries the hash of the current remove preview.
     *
     * @throws SessionException.Conflict `hooks_not_installed`, or `hooks_file_changed` when the file changed since
     *   the preview or holds entries with the hook program's path that install did not write.
     */
    suspend fun remove(
        projectPath: String,
        request: HookSetupRequest,
    ): HookChange =
        locked(projectPath) {
            val plan = plan(projectPath, forRemove = true)
            if (!plan.installed) {
                throw SessionException.Conflict(HOOKS_NOT_INSTALLED, "Rafiq's hooks are not in ${plan.target}.")
            }
            if (plan.changed) {
                throw SessionException.Conflict(
                    HOOKS_FILE_CHANGED,
                    "Rafiq's hooks in ${plan.target} were edited by hand, so removing them could lose your " +
                        "changes. Remove them in the file.",
                )
            }
            if (!MessageDigest.isEqual(plan.hash.toByteArray(), request.previewHash.toByteArray())) throw changed()
            commit(plan, plan.after)
            consents.forgetHookConsent(plan.project.toString())
            HookChange(previewOf(plan(projectPath)), plan.after?.let(::sha256))
        }

    /** One change at a time per project, on the IO dispatcher. */
    private suspend fun <T> locked(
        projectPath: String,
        block: suspend () -> T,
    ): T =
        withContext(io) {
            val project = files.projectFolder(projectPath)
            locks.computeIfAbsent(project) { Mutex() }.withLock { block() }
        }

    /** Writes [text] (or deletes the file for null), unless the file changed since [plan] read it. */
    private suspend fun commit(
        plan: Plan,
        text: String?,
    ) {
        beforeCommit()
        if (!files.replace(plan.target, text) { now -> now == plan.current }) throw changed()
    }

    private fun previewOf(plan: Plan) =
        HookSetupPreview(
            projectPath = plan.project.toString(),
            targetFile = plan.target.toString(),
            fileText = plan.after.orEmpty(),
            previewHash = plan.hash,
            events = HookSettings.EVENTS,
            sends = SENDS,
            installed = plan.installed,
            gitExcludeOffered = files.gitExcludeOffered(plan.project),
        )

    private fun plan(
        projectPath: String,
        forRemove: Boolean? = null,
    ): Plan {
        val project = files.projectFolder(projectPath)
        val target = files.targetIn(project)
        val current = files.read(target)
        val parsed = current?.let(::parse) ?: JsonObject(emptyMap())
        val installed = settings.isInstalled(parsed)
        val removing = forRemove ?: installed
        var changed = false
        val after =
            if (removing) {
                when (val removal = settings.remove(parsed)) {
                    is HookSettings.Removal.Done -> {
                        removal.settings?.let(::render)
                    }

                    HookSettings.Removal.Changed -> {
                        changed = true
                        current
                    }
                }
            } else {
                render(settings.install(parsed))
            }
        val operation = if (removing) "remove" else "install"
        val hash =
            sha256(listOf(operation, target.toString(), after ?: ABSENT, current ?: ABSENT).joinToString("\u0000"))
        return Plan(project, target, installed, current, after, changed, hash)
    }

    private fun parse(text: String): JsonObject =
        try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        } ?: throw SessionException.Conflict(
            "hooks_file_unreadable",
            "The settings file is not a JSON object. Fix it, then try again.",
        )

    private fun changed() =
        SessionException.Conflict(
            HOOKS_FILE_CHANGED,
            "The settings file changed since you looked at it. Check the new preview and try again.",
        )

    companion object {
        /** The file the hooks go in, inside the project's `.claude` folder. */
        const val SETTINGS_FILE = "settings.local.json"

        /** 409 code: the file or the plan changed since the preview. */
        const val HOOKS_FILE_CHANGED = "hooks_file_changed"

        /** 409 code: remove was asked for, but the hooks are not in the file. */
        const val HOOKS_NOT_INSTALLED = "hooks_not_installed"
        private const val ABSENT = "\u0000absent"

        /** What the hook program sends, for the consent screen. */
        const val SENDS =
            "Each hook event, as Claude Code's own JSON, with the tmux pane and the agent's process id. It goes to " +
                "your Rafiq bridge over a Unix socket in the bridge's data folder on this server, never over the " +
                "network. A permission request waits up to 10 minutes for your phone. If the bridge is not running " +
                "or anything goes wrong, Claude Code asks at the desk as usual."

        // Two-space indents, as Claude Code writes the file itself.
        @OptIn(ExperimentalSerializationApi::class)
        private val pretty =
            Json {
                prettyPrint = true
                prettyPrintIndent = "  "
            }

        private fun render(settings: JsonObject): String =
            pretty.encodeToString(JsonObject.serializer(), settings) + "\n"

        internal fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        /**
         * The installed hook program: the first `hooks/rafiq-hook.py` above the bridge's own code. In an installed
         * bridge that is `<install>/hooks/rafiq-hook.py`, next to `bin` and `lib`. Null when it cannot be found, or
         * when it is not safe to point Claude Code at (see [isTrustedProgram]).
         */
        fun locateProgram(): Path? {
            val code =
                runCatching {
                    Path.of(
                        HookSetup::class.java.protectionDomain.codeSource.location
                            .toURI(),
                    )
                }.getOrNull()
            return generateSequence(code) { it.parent }
                .map { it.resolve("hooks").resolve("rafiq-hook.py") }
                .firstOrNull { Files.isRegularFile(it) }
                ?.takeIf(::isTrustedProgram)
        }

        /**
         * True when [program] is an executable regular file that only you can change: it and its folder belong to
         * the bridge's user, and neither is writable by the group or others. Claude Code runs this file on every
         * hook event, so anyone who could change it could run code as you.
         */
        internal fun isTrustedProgram(program: Path): Boolean {
            val runnable = Files.isRegularFile(program, LinkOption.NOFOLLOW_LINKS) && Files.isExecutable(program)
            val posix = "posix" in program.fileSystem.supportedFileAttributeViews()
            return runnable && (!posix || listOf(program, program.parent).all(::onlyMine))
        }

        private fun onlyMine(path: Path): Boolean {
            val modes = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
            return Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).name == System.getProperty("user.name") &&
                PosixFilePermission.GROUP_WRITE !in modes &&
                PosixFilePermission.OTHERS_WRITE !in modes
        }
    }
}
