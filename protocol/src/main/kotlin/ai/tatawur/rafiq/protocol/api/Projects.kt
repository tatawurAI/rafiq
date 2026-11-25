package ai.tatawur.rafiq.protocol.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Body of `GET /projects`: where a new session can start, so you pick a folder instead of typing a path.
 *
 * @property recent project folders of earlier sessions on this bridge, newest first, as absolute paths. Only
 *   folders that still exist are listed.
 * @property roots the folders the bridge may browse, from `RAFIQ_PROJECT_ROOTS`, as absolute paths. Empty when
 *   none are set, and then only [recent] and a typed path are offered.
 */
@Serializable
data class ProjectsOverview(
    @SerialName("recent") val recent: List<String> = emptyList(),
    @SerialName("roots") val roots: List<String> = emptyList(),
)

/**
 * Body of `GET /projects/folders?path=<absolute path>`: the folders directly inside [path], one level only.
 * Names only, never file contents. A path outside every root, including by `..` or a symlink, gets 403 with
 * [ProjectErrorCodes.OUTSIDE_ROOTS].
 *
 * @property path the folder that was listed, as the bridge resolved it.
 * @property folders the names of the folders inside it, sorted, without hidden ones.
 * @property truncated true when the bridge stopped at its cap.
 */
@Serializable
data class ProjectFolderListing(
    @SerialName("path") val path: String,
    @SerialName("folders") val folders: List<String> = emptyList(),
    @SerialName("truncated") val truncated: Boolean = false,
)

/** Error codes of the project routes. */
object ProjectErrorCodes {
    /** The path is not under any of the bridge's project roots, after resolving `..` and symlinks. */
    const val OUTSIDE_ROOTS = "outside_project_roots"

    /** The path does not exist or is not a folder. */
    const val NOT_A_FOLDER = "not_a_folder"
}

/** The most folder names `GET /projects/folders` returns. */
const val MAX_PROJECT_FOLDERS = 500
