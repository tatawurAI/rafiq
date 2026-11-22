package ai.tatawur.rafiq.client.terminal

import ai.tatawur.rafiq.client.CommandResult
import ai.tatawur.rafiq.client.withoutValue
import ai.tatawur.rafiq.protocol.api.TerminalHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the Terminal view shows over the live screen. */
sealed interface ScrollbackState {
    /** The live screen, with nothing over it. */
    data object Live : ScrollbackState

    /** The pane's history, oldest line first, over the live screen. */
    data class Showing(
        val lines: List<String>,
    ) : ScrollbackState

    /** The history could not be read. The live screen stays. */
    data class Failed(
        val result: CommandResult<Nothing>,
    ) : ScrollbackState
}

/**
 * Scrolling back in Terminal without touching what the desk sees. tmux copy mode on the shared pane would
 * show at the desk, so a swipe reads the pane's history from your server instead and shows it over the live
 * screen. Nothing about the window or its size changes.
 *
 * A full-screen program on the alternate screen has no history. There a swipe scrolls the program itself:
 * with the mouse wheel when the program turned mouse mode on, otherwise with Page Up and Page Down. Which of
 * the two applies is asked of your server at the start of each swipe, because a program can leave the
 * alternate screen at any time.
 *
 * The view reports a swipe as [gestureStarted], then one [step] for each few rows the finger moves. New
 * output and typing call [backToLive].
 *
 * @param fetch reads the history of the pane the phone shows.
 * @param sendKeys sends scroll keys to the pane, through the key allow-list.
 */
class Scrollback(
    private val scope: CoroutineScope,
    private val fetch: suspend () -> CommandResult<TerminalHistory>,
    private val sendKeys: (ByteArray) -> Unit,
) {
    private enum class Gesture { NONE, ASKING, KEYS, OVERLAY }

    private val current = MutableStateFlow<ScrollbackState>(ScrollbackState.Live)
    val state: StateFlow<ScrollbackState> = current.asStateFlow()

    private var mouse = false
    private var gesture = Gesture.NONE
    private val waitingSteps = mutableListOf<Boolean>()
    private var keySteps = 0
    private var asking: Job? = null

    /** A fresh screen arrived, for example after a reconnect or a window switch. [mouse] is the program's mode. */
    fun onSnapshot(mouse: Boolean) {
        this.mouse = mouse
    }

    /** A new swipe began. */
    fun gestureStarted() {
        asking?.cancel()
        waitingSteps.clear()
        keySteps = 0
        gesture = if (current.value is ScrollbackState.Showing) Gesture.OVERLAY else Gesture.NONE
    }

    /** The finger moved a step: toward older lines when [older], toward newer ones otherwise. */
    fun step(older: Boolean) {
        when (gesture) {
            Gesture.NONE -> {
                waitingSteps += older
                gesture = Gesture.ASKING
                asking = scope.launch { answer(fetch()) }
            }

            Gesture.ASKING -> {
                waitingSteps += older
            }

            Gesture.KEYS -> {
                scrollKeys(older)
            }

            // The overlay scrolls itself.
            Gesture.OVERLAY -> {}
        }
    }

    /** New output arrived, you typed, or you tapped "Back to live". */
    fun backToLive() {
        if (current.value is ScrollbackState.Showing || current.value is ScrollbackState.Failed) {
            current.value = ScrollbackState.Live
            gesture = Gesture.NONE
        }
    }

    private fun answer(result: CommandResult<TerminalHistory>) {
        val steps = waitingSteps.toList()
        waitingSteps.clear()
        when {
            result !is CommandResult.Ok -> {
                gesture = Gesture.NONE
                current.value = ScrollbackState.Failed(result.withoutValue())
            }

            result.value.alternateScreen -> {
                gesture = Gesture.KEYS
                steps.forEach(::scrollKeys)
            }

            // Only a swipe toward older lines opens the overlay. The live screen already is the newest.
            steps.firstOrNull() == true -> {
                gesture = Gesture.OVERLAY
                current.value = ScrollbackState.Showing(historyLines(result.value.text))
            }

            else -> {
                gesture = Gesture.NONE
            }
        }
    }

    private fun scrollKeys(older: Boolean) {
        val keys =
            when {
                mouse -> if (older) WHEEL_UP else WHEEL_DOWN

                // One page per few steps, so a short swipe moves one page and not a dozen.
                keySteps % STEPS_PER_PAGE == 0 -> if (older) PAGE_UP else PAGE_DOWN

                else -> null
            }
        keySteps++
        keys?.let { sendKeys(it.copyOf()) }
    }

    companion object {
        /** Steps of one swipe per Page Up or Page Down. */
        const val STEPS_PER_PAGE = 6

        /** Lines of history the overlay asks for: the bridge's cap. */
        const val HISTORY_LINES = ai.tatawur.rafiq.protocol.api.MAX_HISTORY_LINES

        val PAGE_UP = "\u001b[5~".toByteArray(Charsets.US_ASCII)
        val PAGE_DOWN = "\u001b[6~".toByteArray(Charsets.US_ASCII)

        // The SGR wheel at the top left cell. A pane shows one program, so where the wheel turns does not matter.
        val WHEEL_UP = "\u001b[<64;1;1M".toByteArray(Charsets.US_ASCII)
        val WHEEL_DOWN = "\u001b[<65;1;1M".toByteArray(Charsets.US_ASCII)
    }
}

/**
 * The history text as plain lines to show. The text comes from the pane, so it is untrusted: every escape
 * sequence and control character except Tab is removed, and so are the empty lines tmux pads the end with.
 */
fun historyLines(text: String): List<String> {
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == ESC) {
            i = skipEscape(text, i)
        } else {
            if (c == '\n' || c == '\t' || !c.isISOControl()) out.append(c)
            i++
        }
    }
    return out.lines().dropLastWhile { it.isBlank() }
}

/** The index after the escape sequence at [at]. An unfinished one runs to the end. */
private fun skipEscape(
    text: String,
    at: Int,
): Int {
    val next = text.getOrNull(at + 1) ?: return text.length
    return when (next) {
        '[' -> {
            var i = at + 2
            while (i < text.length && text[i].code in CSI_BODY) i++
            minOf(i + 1, text.length)
        }

        ']', 'P', '_', '^', 'X' -> {
            var i = at + 2
            while (i < text.length && !endsString(text, i)) i++
            when {
                i >= text.length -> text.length
                text[i] == BEL -> i + 1
                else -> i + 2
            }
        }

        else -> {
            at + 2
        }
    }
}

/** True when a BEL or an ST (`ESC \\`) at [at] ends a string sequence. */
private fun endsString(
    text: String,
    at: Int,
): Boolean = text[at] == BEL || (text[at] == ESC && text.getOrNull(at + 1) == '\\')

private const val ESC = '\u001b'
private const val BEL = '\u0007'

// Parameter and intermediate bytes of a CSI sequence. The final byte follows them.
private val CSI_BODY = 0x20..0x3f
