package ai.tatawur.rafiq.bridge.server

/**
 * The audit log for what a phone does through Chat (ADR 0013 guard 11, issue #53), and for each read of
 * Terminal scrollback. Terminal opens and closes are already logged by the terminal route. Text is never
 * written, only its SHA-256 as lowercase hex, and keystrokes are never logged at all.
 */
interface AuditLog {
    /** A prompt sent from Chat. */
    fun chatPrompt(
        deviceId: String,
        sessionId: String,
        textSha256: String,
    )

    /** An approval answered from a phone. [optionId] is the option chosen. */
    fun approvalAnswer(
        deviceId: String,
        sessionId: String,
        approvalId: String,
        optionId: String,
    )

    /**
     * A read of Terminal scrollback (`GET /terminal/history`). [window] is the window index asked for, or null
     * for the session's current window. [lines] is the number of history lines asked for, after the cap.
     */
    fun terminalHistory(
        deviceId: String,
        sessionId: String,
        window: Int?,
        lines: Int,
    )
}
