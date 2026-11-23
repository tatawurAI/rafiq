package ai.tatawur.rafiq.client.terminal

import ai.tatawur.rafiq.client.CommandResult
import ai.tatawur.rafiq.client.visibleControls
import ai.tatawur.rafiq.client.withoutValue
import ai.tatawur.rafiq.protocol.api.TerminalHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** What the Terminal view shows over the live screen. */
sealed interface ScrollbackState {
    /** The live screen, with nothing over it. */
    data object Live : ScrollbackState

    /** A swipe toward older lines asked your server for the history, and the answer has not come yet. */
    data object Loading : ScrollbackState

    /**
     * The pane's history, oldest line first, over the live screen. It stays open while the pane keeps
     * drawing, for example an agent's spinner. [newOutput] is true once the pane drew something since it
     * opened, so the view can offer to go back to live.
     */
    data class Showing(
        val lines: List<String>,
        val newOutput: Boolean = false,
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
 * output calls [onOutput], which only marks the overlay. Typing, "Back to live" and Back call [backToLive].
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
        if (current.value == ScrollbackState.Loading) current.value = ScrollbackState.Live
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
                if (older) current.value = ScrollbackState.Loading
                asking = scope.launch { answer(fetchOnceMore()) }
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

    /** The pane drew something. An open overlay stays, and says new output is waiting. */
    fun onOutput() {
        val shown = current.value
        if (shown is ScrollbackState.Showing && !shown.newOutput) current.value = shown.copy(newOutput = true)
    }

    /** You typed, tapped "Back to live" or "New output", or pressed Back. */
    fun backToLive() {
        if (current.value != ScrollbackState.Live) {
            asking?.cancel()
            current.value = ScrollbackState.Live
            gesture = Gesture.NONE
        }
    }

    /**
     * Reads the history, and once more after the wait your server asks for when it is rate limiting reads,
     * capped at [MAX_RETRY_WAIT]. The loading line stays meanwhile. A second refusal is shown as such.
     */
    private suspend fun fetchOnceMore(): CommandResult<TerminalHistory> {
        val first = fetch()
        if (first !is CommandResult.RateLimited) return first
        delay((first.retryAfterMs?.milliseconds ?: DEFAULT_RETRY_WAIT).coerceIn(Duration.ZERO, MAX_RETRY_WAIT))
        return fetch()
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
                current.value = ScrollbackState.Live
                steps.forEach(::scrollKeys)
            }

            // Only a swipe toward older lines opens the overlay. The live screen already is the newest.
            steps.firstOrNull() == true -> {
                gesture = Gesture.OVERLAY
                current.value = ScrollbackState.Showing(historyLines(result.value.text))
            }

            else -> {
                gesture = Gesture.NONE
                current.value = ScrollbackState.Live
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
        /** The longest wait before reading the history again after your server said too many reads. */
        val MAX_RETRY_WAIT: Duration = 5.seconds

        private val DEFAULT_RETRY_WAIT: Duration = 1.seconds

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
 * sequence and carriage return is removed, any other invisible control shows as `<U+XXXX>`, and the empty
 * lines tmux pads the end with go.
 */
fun historyLines(text: String): List<String> {
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == ESC) {
            i = skipEscape(text, i)
        } else {
            // A carriage return only moves the cursor. Any other control shows as an escape below.
            if (c != '\r') out.append(c)
            i++
        }
    }
    return out.lines().map { visibleControls(it) }.dropLastWhile { it.isBlank() }
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
