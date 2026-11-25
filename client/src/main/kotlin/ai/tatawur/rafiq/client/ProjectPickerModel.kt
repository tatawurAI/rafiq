package ai.tatawur.rafiq.client

import ai.tatawur.rafiq.protocol.api.ProjectErrorCodes
import ai.tatawur.rafiq.protocol.api.ProjectFolderListing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * What "New session" shows for the project folder.
 *
 * @property recent project folders of earlier sessions on your server, newest first.
 * @property roots the folders your server lets you browse. Empty when it allows none.
 * @property overviewFailure why the recent folders and roots could not load, or null. Typing a path still works.
 * @property browsing the folder being browsed, or null at the top, where the recent folders and roots show.
 * @property path the project folder Start uses: typed, or filled in by a pick.
 * @property check what is known about [path].
 */
data class ProjectPickerState(
    val recent: List<String> = emptyList(),
    val roots: List<String> = emptyList(),
    val overviewFailure: CommandResult<Nothing>? = null,
    val browsing: BrowsedFolder? = null,
    val path: String = "",
    val check: PathCheck = PathCheck.Empty,
)

/**
 * One folder being browsed.
 *
 * @property listing its folders, or null while they load or after they failed.
 * @property failure why they could not load, or null.
 * @property root the root it is under, so going up stops there.
 */
data class BrowsedFolder(
    val path: String,
    val root: String,
    val listing: ProjectFolderListing? = null,
    val failure: CommandResult<Nothing>? = null,
)

/** What is known about the project path, shown next to the field. */
sealed interface PathCheck {
    /** Nothing typed yet. */
    data object Empty : PathCheck

    /** It does not start with `/`, so it cannot be a folder on your server. */
    data object NotAbsolute : PathCheck

    /** Your server is being asked. */
    data object Checking : PathCheck

    /** A folder your server listed or confirmed. */
    data object Folder : PathCheck

    /** Your server has no folder there. */
    data object NotAFolder : PathCheck

    /** Not under a root your server lets you browse, so it is checked only when you start. */
    data object OutsideRoots : PathCheck

    /** It could not be checked, for example with no connection. Start checks it. */
    data class Unchecked(
        val result: CommandResult<Nothing>,
    ) : PathCheck

    /** False when Start would certainly fail, or the answer is still on its way. */
    val allowsStart: Boolean
        get() = this == Folder || this == OutsideRoots || this is Unchecked
}

/**
 * The state holder behind the project folder on "New session": recent folders, browsing one level at a time
 * under the roots, and a typed path that your server checks a moment after you stop typing.
 *
 * @param checkDelay how long typing must pause before the path is checked, so each key press is not a request.
 */
class ProjectPickerModel(
    private val repository: SessionRepository,
    private val scope: CoroutineScope,
    private val checkDelay: Duration = DEFAULT_CHECK_DELAY,
) {
    private val current = MutableStateFlow(ProjectPickerState())
    private var checkJob: Job? = null
    private var browseJob: Job? = null

    val state: StateFlow<ProjectPickerState> = current.asStateFlow()

    /** Reads the recent folders and the roots. */
    fun load() {
        scope.launch {
            when (val result = repository.projects()) {
                is CommandResult.Ok -> {
                    current.update {
                        it.copy(recent = result.value.recent, roots = result.value.roots, overviewFailure = null)
                    }
                }

                else -> {
                    current.update { it.copy(overviewFailure = result.withoutValue()) }
                }
            }
        }
    }

    /** Opens [path], a root or a folder inside the one being browsed. */
    fun open(path: String) {
        val root = current.value.roots.firstOrNull { path == it || path.startsWith("$it/") } ?: return
        browseJob?.cancel()
        current.update { it.copy(browsing = BrowsedFolder(path, root)) }
        browseJob =
            scope.launch {
                val result = repository.projectFolders(path)
                current.update { state ->
                    val browsing = state.browsing?.takeIf { it.path == path } ?: return@update state
                    state.copy(
                        browsing =
                            when (result) {
                                is CommandResult.Ok -> browsing.copy(path = result.value.path, listing = result.value)
                                else -> browsing.copy(failure = result.withoutValue())
                            },
                    )
                }
            }
    }

    /** Opens the folder [name] inside the one being browsed. */
    fun openChild(name: String) {
        val browsing = current.value.browsing ?: return
        open(browsing.path.trimEnd('/') + "/" + name)
    }

    /** One level up, or back to the recent folders and roots from a root. */
    fun up() {
        val browsing = current.value.browsing ?: return
        if (browsing.path == browsing.root || browsing.listing?.path == browsing.root) {
            browseJob?.cancel()
            current.update { it.copy(browsing = null) }
        } else {
            open(browsing.path.substringBeforeLast('/').ifEmpty { browsing.root })
        }
    }

    /** Uses [path], a folder your server listed, as the project. No check is needed. */
    fun choose(path: String) {
        checkJob?.cancel()
        current.update { it.copy(path = path, check = PathCheck.Folder) }
    }

    /** Uses the folder being browsed as the project. */
    fun chooseBrowsed() {
        current.value.browsing?.let { choose(it.listing?.path ?: it.path) }
    }

    /** The path field changed. A full path is checked with your server once typing pauses. */
    fun type(text: String) {
        checkJob?.cancel()
        val path = text.trim()
        val check =
            when {
                path.isEmpty() -> PathCheck.Empty
                !path.startsWith("/") -> PathCheck.NotAbsolute
                else -> PathCheck.Checking
            }
        current.update { it.copy(path = text, check = check) }
        if (check != PathCheck.Checking) return
        checkJob =
            scope.launch {
                delay(checkDelay)
                val verdict = verdict(repository.projectFolders(path))
                // A newer edit owns the field by now, and its own check is on its way.
                current.update { if (it.path.trim() == path) it.copy(check = verdict) else it }
            }
    }

    private fun verdict(result: CommandResult<ProjectFolderListing>): PathCheck =
        when {
            result is CommandResult.Ok -> {
                PathCheck.Folder
            }

            // A bridge from before the picker answers every project route with 404, which says nothing here.
            result is CommandResult.NotFound && current.value.overviewFailure == CommandResult.NotFound -> {
                PathCheck.Unchecked(result)
            }

            result is CommandResult.NotFound -> {
                PathCheck.NotAFolder
            }

            result is CommandResult.Failed && result.code == ProjectErrorCodes.NOT_A_FOLDER -> {
                PathCheck.NotAFolder
            }

            result is CommandResult.Failed && result.code == ProjectErrorCodes.OUTSIDE_ROOTS -> {
                PathCheck.OutsideRoots
            }

            else -> {
                PathCheck.Unchecked(result.withoutValue())
            }
        }

    private companion object {
        val DEFAULT_CHECK_DELAY = 400.milliseconds
    }
}
