package ai.tatawur.rafiq.bridge.server

import ai.tatawur.rafiq.bridge.projects.ProjectCatalog
import ai.tatawur.rafiq.bridge.sessions.SessionException
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** `GET /projects` and `GET /projects/folders`. Both need the device token, like every route but `/health`. */
internal fun Route.projectRoutes(projects: ProjectCatalog) {
    get("/projects") { call.respond(projects.overview()) }
    get("/projects/folders") {
        val path =
            call.request.queryParameters["path"]
                ?: throw SessionException.Invalid("missing_path", "Add ?path= with an absolute folder path.")
        call.respond(projects.folders(path))
    }
}
