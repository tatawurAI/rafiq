package ai.tatawur.rafiq.bridge.sessions

import ai.tatawur.rafiq.bridge.agent.AgentModes
import ai.tatawur.rafiq.protocol.Session
import ai.tatawur.rafiq.protocol.SessionMode

/**
 * Which permission mode a session must be in, and the refusals when it cannot be. Pure, so [SessionManager]
 * keeps only the calls into the agent.
 *
 * @param defaultModes the mode, by agent id, from `RAFIQ_DEFAULT_MODES`.
 */
internal class ModeRules(
    private val defaultModes: Map<String, String>,
) {
    /**
     * The mode a new session must be switched to, or null when it may stay as the agent opened it.
     *
     * @throws SessionException.Invalid when the agent does not offer [requested].
     * @throws SessionException.Conflict when the agent does not offer the configured default.
     */
    fun startingMode(
        agent: String,
        requested: String?,
        offered: AgentModes,
    ): String? {
        val wanted = requested ?: defaultModes[agent]
        return when {
            wanted == null -> {
                null
            }

            offered.offers(wanted) -> {
                wanted.takeIf { it != offered.currentModeId }
            }

            requested != null -> {
                throw unknownMode(requested)
            }

            // Failing closed: starting anyway could run the agent in a mode that never asks.
            else -> {
                throw SessionException.Conflict(
                    DEFAULT_MODE_UNAVAILABLE,
                    "RAFIQ_DEFAULT_MODES names the mode '$wanted' for $agent, which the agent does not offer.",
                )
            }
        }
    }

    /**
     * The mode a resumed session must be in: the one it had, or for a session from before modes were stored,
     * the configured default. Null when neither is known.
     */
    fun storedOrDefault(session: Session): String? = session.permissionMode ?: defaultModes[session.agent]

    companion object {
        const val UNKNOWN_MODE = "unknown_mode"
        const val MODE_NOT_SUPPORTED = "mode_not_supported"
        const val DEFAULT_MODE_UNAVAILABLE = "default_mode_unavailable"
        const val MODE_NOT_RESTORED = "mode_not_restored"

        /**
         * @throws SessionException.Conflict for a tmux session, whose mode is set in the agent itself.
         * @throws SessionException.Invalid when [modeId] is not among [offered].
         */
        fun requireOffered(
            sessionId: String,
            offered: List<SessionMode>,
            modeId: String,
        ) {
            if (isTmuxSessionId(sessionId)) {
                throw SessionException.Conflict(
                    MODE_NOT_SUPPORTED,
                    "The mode of a tmux session is set in the agent itself. Use Terminal.",
                )
            }
            if (offered.none { it.id == modeId }) throw unknownMode(modeId)
        }

        private fun unknownMode(modeId: String) =
            SessionException.Invalid(UNKNOWN_MODE, "The agent does not offer the mode '$modeId' for this session.")
    }
}
