package ai.tatawur.rafiq.bridge.tmux.reader

import ai.tatawur.rafiq.protocol.api.SendRefusal
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Why the bridge did not type, or did not finish typing, a prompt. [code] is one of [SendRefusal]. */
data class Refusal(
    val code: String,
    val message: String,
) {
    companion object {
        val WAITING =
            Refusal(
                SendRefusal.AGENT_WAITING,
                "Claude is waiting on an approval or a question at the desk. Answer it first.",
            )
        val NOT_EMPTY =
            Refusal(
                SendRefusal.INPUT_NOT_EMPTY,
                "There is half-typed text in the terminal. Send or clear it at the desk first.",
            )
        val NOT_RUNNING = Refusal(SendRefusal.AGENT_NOT_RUNNING, "Claude Code is not running in that pane.")
        val UNKNOWN =
            Refusal(
                SendRefusal.STATE_UNKNOWN,
                "Rafiq cannot tell whether it is safe to type into the terminal, so it did not.",
            )
        val NO_HOOKS =
            Refusal(
                SendRefusal.HOOKS_NOT_INSTALLED,
                "This project has no Rafiq hooks, so Chat cannot see what Claude does. Set them up, or use Terminal.",
            )
        val NOT_CONFIRMED =
            Refusal(
                SendRefusal.NOT_CONFIRMED,
                "The message was typed, but Claude did not confirm it. Check Terminal.",
            )

        /** The text is in the input box at the desk, but Enter was not pressed. */
        fun leftInBox(reason: Refusal) = reason.copy(message = reason.message + " Your text was left in the input box.")
    }
}

/**
 * Drops every control character except tab and newline: tmux would treat them as key presses, and an Escape
 * or Ctrl-C would act on the agent. A carriage return is dropped too, so `\r\n` becomes a newline.
 */
internal fun sanitizePrompt(text: String): String = text.filter { it == '\t' || it == '\n' || !it.isISOControl() }

/** Claude Code's input box, as read from the visible pane text. */
internal enum class InputBox {
    /** The box is showing and holds no text. */
    EMPTY,

    /** The box is showing with text someone typed. */
    DRAFT,

    /** No box: a dialog, a menu or something else owns the keys. */
    ABSENT,
}

/**
 * Reads the input box from [screen], the visible pane text with its colour escapes (`capture-pane -e`). The box
 * sits between the last two lines made of box-drawing dashes, and its first line starts with the prompt mark.
 *
 * Claude Code shows grey text in an empty box: the `Try "..."` hint, and after a turn a suggested next prompt.
 * Both are dim (SGR 2). So the box is empty when nothing after the prompt mark is visible, or everything visible is
 * dim. Text you type is not dim. Anything else counts as a draft, so an unclear screen never reads as empty.
 */
@Suppress("ReturnCount") // Each sign that the box is not showing answers ABSENT right away.
internal fun readInputBox(screen: String): InputBox {
    val lines = screen.lines()
    val rules = lines.indices.filter { stripEscapes(lines[it]).trimStart().startsWith(RULE) }
    if (rules.size < 2) return InputBox.ABSENT
    val inside = lines.subList(rules[rules.size - 2] + 1, rules.last()).filter { stripEscapes(it).isNotBlank() }
    val first = inside.firstOrNull() ?: return InputBox.ABSENT
    val mark = first.indexOf(PROMPT_MARK)
    if (mark < 0 || stripEscapes(first.substring(0, mark)).isNotBlank()) return InputBox.ABSENT
    val typed = listOf(first.substring(mark + PROMPT_MARK.length)) + inside.drop(1)
    return if (typed.any(::hasUndimmedText)) InputBox.DRAFT else InputBox.EMPTY
}

private val ESCAPE = Regex("\u001b\\[[0-9;?]*[ -/]*[@-~]")

private fun stripEscapes(line: String): String = ESCAPE.replace(line, "")

/**
 * True when [line] shows a character that is not dim. Dim starts at SGR 2 and ends at SGR 0, 22 or an empty SGR.
 * Each line starts not dim, as tmux writes the escapes again at the start of every captured line.
 */
private fun hasUndimmedText(line: String): Boolean {
    var dim = false
    var index = 0
    while (index < line.length) {
        val escape = ESCAPE.matchAt(line, index)
        if (escape != null) {
            if (escape.value.endsWith("m")) dim = dimAfter(escape.value, dim)
            index = escape.range.last + 1
        } else {
            val char = line[index]
            if (!dim && !char.isWhitespace() && char != '\u00a0') return true
            index++
        }
    }
    return false
}

private fun dimAfter(
    sgr: String,
    dim: Boolean,
): Boolean {
    val params = sgr.removePrefix("\u001b[").removeSuffix("m").split(';')
    var now = dim
    for (param in params) {
        when (param) {
            "", "0", "22" -> now = false
            "2" -> now = true
        }
    }
    return now
}

private const val PROMPT_MARK = "❯"
private val RULE = "─".repeat(20)

/** The pane a prompt goes to and the agent process that must still run in it. */
data class PaneTarget(
    val paneId: String,
    val agentPid: Long,
)

/** What the ready check found. */
internal sealed interface Readiness {
    data object Ready : Readiness

    /** A turn is running. The prompt waits in the bridge until the agent is idle. */
    data object Busy : Readiness

    data class Refused(
        val reason: Refusal,
    ) : Readiness
}

/**
 * The send guard (ADR 0013 guard 8, issue #50): decides whether the bridge may type into a pane, and types.
 * It protects against accidents, not against you, so it never looks at what the text says. A leading `!` or
 * `/` is typed exactly as sent.
 *
 * @param settle how long the agent gets to take in a paste before the bridge checks again and presses Enter.
 */
internal class SendGuard(
    private val io: PaneIo,
    private val statuses: AgentStatusSource,
    private val settle: Duration = 150.milliseconds,
) {
    /** The checks before typing: idle, no dialog open, input box empty. Anything unclear is a refusal. */
    suspend fun readiness(
        target: PaneTarget,
        approvalOpen: Boolean,
    ): Readiness {
        val status = if (approvalOpen) null else statusOf(target)
        return when {
            approvalOpen -> Readiness.Refused(Refusal.WAITING)
            status == null -> Readiness.Refused(missingStatus(target))
            status.status == AgentStatus.WAITING -> Readiness.Refused(Refusal.WAITING)
            status.status == AgentStatus.BUSY -> Readiness.Busy
            status.status == AgentStatus.IDLE -> boxReadiness(target)
            else -> Readiness.Refused(Refusal.UNKNOWN)
        }
    }

    /**
     * Types [text] and presses Enter once, in its own key event. Right before Enter it checks again that the
     * agent is idle and still runs in the pane, because if it had exited the Enter would run the text in a
     * shell. Null when Enter was pressed.
     */
    suspend fun type(
        target: PaneTarget,
        text: String,
        beforeEnter: suspend () -> Unit = {},
    ): Refusal? {
        if (!io.paste(target.paneId, text)) return Refusal.UNKNOWN
        delay(settle)
        val stillRuns = io.runsAgent(target.paneId, target.agentPid)
        val status = if (stillRuns) statusOf(target)?.status else null
        val refusal =
            when {
                !stillRuns -> Refusal.NOT_RUNNING
                status == AgentStatus.WAITING -> Refusal.WAITING
                status != AgentStatus.IDLE -> Refusal.UNKNOWN
                else -> enter(target, beforeEnter)
            }
        return refusal?.let(Refusal::leftInBox)
    }

    private suspend fun enter(
        target: PaneTarget,
        beforeEnter: suspend () -> Unit,
    ): Refusal? {
        beforeEnter()
        return if (io.pressEnter(target.paneId)) null else Refusal.UNKNOWN
    }

    /** Whether the input box is empty now, for the confirmation of a prompt that starts no turn. */
    suspend fun boxIsEmpty(paneId: String): Boolean = io.capture(paneId)?.let(::readInputBox) == InputBox.EMPTY

    private suspend fun statusOf(target: PaneTarget): AgentStatus? = statuses.statuses()?.get(target.agentPid)

    private suspend fun missingStatus(target: PaneTarget): Refusal =
        if (io.runsAgent(target.paneId, target.agentPid)) Refusal.UNKNOWN else Refusal.NOT_RUNNING

    private suspend fun boxReadiness(target: PaneTarget): Readiness =
        when (io.capture(target.paneId)?.let(::readInputBox)) {
            InputBox.EMPTY -> Readiness.Ready
            InputBox.DRAFT -> Readiness.Refused(Refusal.NOT_EMPTY)
            InputBox.ABSENT, null -> Readiness.Refused(Refusal.UNKNOWN)
        }
}
