package ai.tatawur.rafiq.bridge.server

import ai.tatawur.rafiq.bridge.tmux.TerminalSettings
import ai.tatawur.rafiq.protocol.api.ApiError
import ai.tatawur.rafiq.protocol.api.ApiErrorDetail
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.response.respond
import org.slf4j.LoggerFactory

/**
 * The optional second proof of who is calling: the Tailscale login that `tailscale serve` names in the
 * `Tailscale-User-Login` header must be [ownerLogin], on top of a valid device token.
 *
 * A stolen device token used from another person's Tailscale login is then refused. The header is trusted only
 * on a connection from loopback, which is where `tailscale serve` connects from. The bridge also refuses to
 * start with this check on and a non-loopback bind address (see `BridgeConfig`), because a client that reaches
 * the port directly could send the header itself. It does not help against anything that runs on your server:
 * a local process can connect from loopback and send any header it likes.
 *
 * Every path except `GET /health` is checked, both socket handshakes included. `/health` stays open so a local
 * health probe keeps working, and it reveals only the version.
 *
 * @param ownerLogin the login to accept, compared without regard to case, as Tailscale shows it in the admin
 *   console, for example `alex@example.com`.
 */
class TailscaleIdentityCheck(
    private val ownerLogin: String,
) {
    /** What the check decided about one request. */
    sealed interface Verdict {
        data object Accepted : Verdict

        /** [refusedLogin] is the login the header named, or null when there was none to name. Safe to log. */
        data class Rejected(
            val refusedLogin: String?,
            val reason: String,
        ) : Verdict
    }

    /**
     * Checks one request.
     *
     * @param peerAddress the IP address of the connection's other end, not anything a header claims.
     * @param loginHeaders every value of `Tailscale-User-Login` the request carries. More than one is refused,
     *   since `tailscale serve` sets exactly one.
     */
    fun inspect(
        path: String,
        peerAddress: String,
        loginHeaders: List<String>,
    ): Verdict {
        val login = loginHeaders.singleOrNull()?.trim()
        return when {
            path in OPEN_PATHS -> Verdict.Accepted
            !TerminalSettings.isLoopback(peerAddress) -> Verdict.Rejected(loggable(login), "peer is not loopback")
            loginHeaders.isEmpty() -> Verdict.Rejected(null, "no identity header")
            login == null -> Verdict.Rejected(null, "more than one identity header")
            !login.equals(ownerLogin, ignoreCase = true) -> Verdict.Rejected(loggable(login), "wrong login")
            else -> Verdict.Accepted
        }
    }

    // The owner's login is not a secret, but it has no business in logs printed by toString either.
    override fun toString(): String = "TailscaleIdentityCheck(ownerLogin=set)"

    companion object {
        /** The header `tailscale serve` adds with the caller's login name. */
        const val LOGIN_HEADER = "Tailscale-User-Login"

        /** The error code of the 403 answer, so the app can tell this refusal from a wrong token. */
        const val ERROR_CODE = "identity_mismatch"

        private val OPEN_PATHS = setOf("/health")
        private const val MAX_LOGGED_LENGTH = 64

        // A refused login is whatever the caller sent. Keep it to one short printable line in the log.
        private fun loggable(login: String?): String? =
            login?.take(MAX_LOGGED_LENGTH)?.map { if (it in ' '..'~') it else '?' }?.joinToString("")
    }
}

/**
 * Runs the identity check for [ownerLogin] on every request, or does nothing when it is null, which keeps the
 * bridge exactly as it is without `RAFIQ_TAILSCALE_LOGIN`. Install it after [installSecurity], so a missing or
 * wrong token still gets 401 first. A refusal is answered with 403 [TailscaleIdentityCheck.ERROR_CODE] and one
 * log line from [refusalLogLine].
 */
fun Application.installIdentityCheck(ownerLogin: String?) {
    val check = ownerLogin?.let(::TailscaleIdentityCheck) ?: return
    intercept(ApplicationCallPipeline.Plugins) {
        val request = call.request
        val verdict =
            check.inspect(
                path = request.path(),
                peerAddress = request.local.remoteAddress,
                loginHeaders = request.headers.getAll(TailscaleIdentityCheck.LOGIN_HEADER).orEmpty(),
            )
        if (verdict is TailscaleIdentityCheck.Verdict.Rejected) {
            identityLog.warn(refusalLogLine(request.local.method.value, request.path(), verdict))
            call.respond(
                HttpStatusCode.Forbidden,
                ApiError(
                    ApiErrorDetail(TailscaleIdentityCheck.ERROR_CODE, "This Tailscale login may not use the bridge."),
                ),
            )
            finish()
        }
    }
}

/**
 * The log line for a refused request. It names the method, the path without its query, why, and the refused
 * login. Nothing else from the request: no token, and no other part of any header.
 */
internal fun refusalLogLine(
    method: String,
    path: String,
    verdict: TailscaleIdentityCheck.Verdict.Rejected,
): String = "Refused $method $path: ${verdict.reason}, login=${verdict.refusedLogin ?: "none"}"

private val identityLog = LoggerFactory.getLogger(TailscaleIdentityCheck::class.java)
