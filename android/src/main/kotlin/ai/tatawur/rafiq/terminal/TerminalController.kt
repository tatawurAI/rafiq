package ai.tatawur.rafiq.terminal

import ai.tatawur.rafiq.client.terminal.CellSize
import ai.tatawur.rafiq.client.terminal.MirroredWindow
import ai.tatawur.rafiq.client.terminal.TerminalEvent
import ai.tatawur.rafiq.client.terminal.TerminalLink
import ai.tatawur.rafiq.client.terminal.TerminalPort
import ai.tatawur.rafiq.client.terminal.TerminalStatus
import ai.tatawur.rafiq.client.terminal.httpsLinkTarget
import ai.tatawur.rafiq.client.terminal.sanitizePaste
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.connectbot.terminal.InlineImages
import org.connectbot.terminal.ModifierManager
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory

/**
 * Ctrl and Alt from the key row. Each is one-shot: the emulator clears them after the next key, so
 * "Ctrl, then c" sends Ctrl-C from the soft keyboard too.
 */
class StickyModifiers : ModifierManager {
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)

    /** The emulator's modifier mask: Alt is bit 1, Ctrl bit 2. */
    val mask: Int get() = (if (alt) ALT_BIT else 0) or (if (ctrl) CTRL_BIT else 0)

    override fun isCtrlActive() = ctrl

    override fun isAltActive() = alt

    override fun isShiftActive() = false

    override fun clearTransients() {
        ctrl = false
        alt = false
    }

    private companion object {
        const val ALT_BIT = 2
        const val CTRL_BIT = 4
    }
}

/**
 * One Terminal view: the emulator and its [TerminalLink]. The emulator always has the pane's real
 * size: snapshots and size messages set it, and the viewport the phone would like goes up as a
 * resize the bridge may or may not follow.
 *
 * The emulator gets an explicit callback for everything the pane could ask of the phone, so the
 * library's defaults never matter. Clipboard writes are ignored. The title is never read. Inline
 * images are off. The bell is at most one haptic a second. A link is only offered, through
 * [pendingLink], when its target is `https`, and opens only after you confirm it.
 *
 * Call [close] when the view is gone for good, to free the emulator's native session.
 */
class TerminalController(
    port: TerminalPort,
    sessionId: String,
    foreground: Color,
    background: Color,
    private val onBell: () -> Unit,
) : AutoCloseable {
    private val link = TerminalLink(port, sessionId)
    private val currentPane = MutableStateFlow<CellSize?>(null)
    private val currentLink = MutableStateFlow<String?>(null)
    private val currentWindow = MutableStateFlow<MirroredWindow?>(null)
    private var lastBellMs = Long.MIN_VALUE / 2

    /** The pane's real size, or null before the first snapshot. */
    val paneSize: StateFlow<CellSize?> = currentPane.asStateFlow()

    val status: StateFlow<TerminalStatus> = link.status

    /** The tmux window being mirrored, or null while unknown or from a bridge that does not say. */
    val window: StateFlow<MirroredWindow?> = currentWindow.asStateFlow()

    /** A link you tapped that is waiting for your confirmation, as its real `https` target. */
    val pendingLink: StateFlow<String?> = currentLink.asStateFlow()

    val modifiers = StickyModifiers()

    val emulator: TerminalEmulator =
        TerminalEmulatorFactory.create(
            initialRows = INITIAL_ROWS,
            initialCols = INITIAL_COLS,
            defaultForeground = foreground,
            defaultBackground = background,
            onKeyboardInput = link::sendKeys,
            onBell = ::bell,
            // OSC 52 never reaches the emulator (see PaneFilter). This is the second wall.
            onClipboardCopy = { _ -> },
            onProgressChange = { _, _ -> },
            autoDetectUrls = true,
            inlineImages = InlineImages.Off,
        )

    /** The viewport in cells that fits the phone's layout. */
    fun onViewport(size: CellSize) = link.setViewport(size)

    /** Sends one key from the key row, with the armed modifiers. */
    fun key(vtermKey: Int) {
        emulator.dispatchKey(modifiers.mask, vtermKey)
        modifiers.clearTransients()
    }

    /** Pastes [text] from the clipboard. The emulator brackets it when the program asked for that. */
    fun paste(text: String) {
        val safe = sanitizePaste(text)
        if (safe.isNotEmpty()) emulator.pasteText(safe)
    }

    /** A tap on a link in the pane. Only an `https` target is offered for confirmation. */
    fun onLinkTapped(url: String) {
        currentLink.value = httpsLinkTarget(url)
    }

    /** Clears the pending link, whether you opened it or not. */
    fun dismissLink() {
        currentLink.value = null
    }

    /** Holds the socket until cancelled. Cancelling closes it, which detaches the view client. */
    suspend fun run() =
        withContext(Dispatchers.Default) {
            link.attach().collect(::apply)
        }

    // Guards the emulator against a write that races [close]: the socket's collector is cancelled
    // when the view goes, but a frame already in hand can still arrive after that.
    private val emulatorLock = Any()
    private var closed = false

    private fun apply(event: TerminalEvent) =
        synchronized(emulatorLock) {
            if (!closed) applyOpen(event)
        }

    private fun applyOpen(event: TerminalEvent) {
        when (event) {
            is TerminalEvent.Snapshot -> {
                emulator.resize(event.size.rows.coerceAtLeast(1), event.size.cols.coerceAtLeast(1))
                emulator.writeInput(event.vt)
                currentPane.value = event.size
                currentWindow.value = event.window
            }

            is TerminalEvent.Output -> {
                emulator.writeInput(event.bytes)
            }

            is TerminalEvent.Size -> {
                emulator.resize(event.size.rows.coerceAtLeast(1), event.size.cols.coerceAtLeast(1))
                currentPane.value = event.size
            }

            is TerminalEvent.Ended -> {
                // An armed Ctrl or Alt must not wait for a key in the next attach.
                modifiers.clearTransients()
            }
        }
    }

    private fun bell() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBellMs >= BELL_INTERVAL_MS) {
            lastBellMs = now
            onBell()
        }
    }

    override fun close() =
        synchronized(emulatorLock) {
            closed = true
            emulator.close()
        }

    private companion object {
        const val INITIAL_ROWS = 24
        const val INITIAL_COLS = 80
        const val BELL_INTERVAL_MS = 1_000L
    }
}
