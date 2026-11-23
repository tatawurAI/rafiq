package ai.tatawur.rafiq.bridge.sessions

/**
 * A request the bridge cannot carry out, with a stable [code] a client can branch on.
 * The routes turn each subtype into one HTTP status and one JSON error body.
 */
sealed class SessionException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** The request is malformed or names something that is not valid, such as a folder that does not exist. */
    class Invalid(
        code: String,
        message: String,
        cause: Throwable? = null,
    ) : SessionException(code, message, cause)

    /** The session, thread or approval does not exist. */
    class NotFound(
        code: String,
        message: String,
        cause: Throwable? = null,
    ) : SessionException(code, message, cause)

    /** The request is valid but cannot be done now, such as cancelling in a session that is not running. */
    class Conflict(
        code: String,
        message: String,
        cause: Throwable? = null,
    ) : SessionException(code, message, cause)

    /** The request names something the bridge will not show, such as a folder outside the project roots. */
    class Forbidden(
        code: String,
        message: String,
        cause: Throwable? = null,
    ) : SessionException(code, message, cause)
}
