package ai.tatawur.rafiq.bridge.server

import org.slf4j.LoggerFactory
import java.security.MessageDigest

/**
 * The [AuditLog] the bridge runs with: one line per action in the bridge's log, next to the Terminal open and
 * close lines, in the same `key=value` style. A line names the device, the session and what was done. It never
 * holds the text of a prompt, only its SHA-256, so the log can show that a prompt was sent and match it to one you
 * have, without keeping what it said.
 *
 * @param write where a line goes. The bridge's log by default.
 */
class LoggingAuditLog(
    private val write: (String) -> Unit = { line -> logger.info(line) },
) : AuditLog {
    override fun chatPrompt(
        deviceId: String,
        sessionId: String,
        textSha256: String,
    ) = write("Chat prompt: device=${clean(deviceId)} session=${clean(sessionId)} sha256=${clean(textSha256)}")

    override fun chatPromptRefused(
        deviceId: String,
        sessionId: String,
        code: String,
        textSha256: String,
    ) = write(
        "Chat prompt refused: device=${clean(deviceId)} session=${clean(sessionId)} code=${clean(code)} " +
            "sha256=${clean(textSha256)}",
    )

    override fun approvalAnswer(
        deviceId: String,
        sessionId: String,
        approvalId: String,
        optionId: String,
    ) = write(
        "Approval answered: device=${clean(deviceId)} session=${clean(sessionId)} approval=${clean(approvalId)} " +
            "option=${clean(optionId)}",
    )

    override fun terminalHistory(
        deviceId: String,
        sessionId: String,
        window: Int?,
        lines: Int,
    ) = write(
        "Terminal history read: device=${clean(deviceId)} session=${clean(sessionId)} " +
            "window=${window ?: "current"} lines=$lines",
    )

    override fun hooksInstalled(
        deviceId: String,
        projectPath: String,
        fileSha256: String,
    ) = write(
        "Hooks installed: device=${clean(deviceId)} project=${cleanPath(projectPath)} sha256=${clean(fileSha256)}",
    )

    override fun hooksRemoved(
        deviceId: String,
        projectPath: String,
        fileSha256: String?,
    ) = write(
        "Hooks removed: device=${clean(deviceId)} project=${cleanPath(projectPath)} " +
            "sha256=${fileSha256?.let(::clean) ?: "deleted"}",
    )

    companion object {
        private val logger = LoggerFactory.getLogger("ai.tatawur.rafiq.bridge.Audit")
        private const val MAX_FIELD = 128
        private const val MAX_PATH = 512

        /** The SHA-256 of [text] as lowercase hex, which is what the audit log keeps of a prompt. */
        fun sha256Hex(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        // Ids come from requests. Keep each on one line, short, and without spaces that would fake another field.
        private fun cleanPath(value: String): String =
            value.take(MAX_PATH).map { if (it.isLetterOrDigit() || it in "_-.:%/") it else '?' }.joinToString("")

        private fun clean(value: String): String =
            value.take(MAX_FIELD).map { if (it.isLetterOrDigit() || it in "_-.:%") it else '?' }.joinToString("")
    }
}
