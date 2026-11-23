package ai.tatawur.rafiq.bridge.projects

import ai.tatawur.rafiq.protocol.api.ProjectFolderListing
import ai.tatawur.rafiq.protocol.api.ProjectsOverview

/**
 * Where a new session can start, for `GET /projects` and `GET /projects/folders`. Read-only: it lists folder names
 * and never reads a file. Every path is resolved, symlinks included, before it is compared with the roots.
 */
interface ProjectCatalog {
    /** Recent project folders and the configured roots. */
    suspend fun overview(): ProjectsOverview

    /**
     * The folders directly inside [path].
     *
     * @throws ai.tatawur.rafiq.bridge.sessions.SessionException with
     *   [ai.tatawur.rafiq.protocol.api.ProjectErrorCodes.OUTSIDE_ROOTS] or
     *   [ai.tatawur.rafiq.protocol.api.ProjectErrorCodes.NOT_A_FOLDER].
     */
    suspend fun folders(path: String): ProjectFolderListing
}
