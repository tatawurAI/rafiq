package ai.tatawur.rafiq.terminal

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.client.terminal.TerminalPort
import ai.tatawur.rafiq.client.terminal.TerminalStatus
import ai.tatawur.rafiq.client.terminal.viewportCells
import ai.tatawur.rafiq.ui.fold.FoldSplit
import ai.tatawur.rafiq.ui.fold.Hinge
import ai.tatawur.rafiq.ui.fold.previewHalfFoldHinge
import ai.tatawur.rafiq.ui.theme.RafiqTheme
import ai.tatawur.rafiq.ui.theme.plexMono
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.TextPaint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.VTermKey
import kotlin.math.ceil

private val TERMINAL_FONT = 12.sp

// The emulator shrinks its font to show the pane's real size while it is larger than the viewport.
private val MIN_FORCED_FONT = 4.sp

/**
 * The Terminal view of a tmux-backed session. The socket is open only while this is composed and
 * the app is started, so leaving Terminal for any reason closes it, and the bridge detaches its
 * view client so the desk gets its size back. Screenshots and the recents thumbnail are blocked
 * while it shows.
 *
 * @param header the session header with the Chat and Terminal toggle.
 */
@Composable
fun TerminalRoute(
    port: TerminalPort,
    sessionId: String,
    hinge: Hinge?,
    header: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val colors = RafiqTheme.colors
    val controller =
        remember(sessionId) {
            TerminalController(port, sessionId, colors.text, colors.hinge) {
                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            }
        }
    DisposableEffect(controller) { onDispose { controller.close() } }
    var attempt by remember { mutableIntStateOf(0) }
    LifecycleStartEffect(controller, attempt) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch { controller.run() }
        onStopOrDispose { scope.cancel() }
    }
    GuardWindow()

    val typeface = remember { ResourcesCompat.getFont(context, R.font.plex_mono_regular) ?: Typeface.MONOSPACE }
    var keyboard by remember { mutableStateOf(false) }
    val status by controller.status.collectAsStateWithLifecycle()
    val paste = { controller.paste(clipboardText(context)) }

    TerminalLayout(
        hinge = hinge,
        header = header,
        status = status,
        onRetry = { attempt++ },
        pane = { modifier ->
            TerminalPane(controller, typeface, keyboard, { keyboard = it }, paste, modifier)
        },
        keyRow = {
            KeyRow(
                ctrlArmed = controller.modifiers.ctrl,
                altArmed = controller.modifiers.alt,
                onKey = controller::key,
                onToggleCtrl = { controller.modifiers.ctrl = !controller.modifiers.ctrl },
                onToggleAlt = { controller.modifiers.alt = !controller.modifiers.alt },
                onPaste = paste,
                onToggleKeyboard = { keyboard = !keyboard },
            )
        },
    )
    PendingLink(controller)
}

/** The confirmation for a link you tapped in the pane, while one waits. */
@Composable
private fun PendingLink(controller: TerminalController) {
    val context = LocalContext.current
    val target by controller.pendingLink.collectAsStateWithLifecycle()
    target?.let { url ->
        LinkConfirmation(
            target = url,
            onOpen = {
                controller.dismissLink()
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            },
            onDismiss = controller::dismissLink,
        )
    }
}

/** The clipboard's first item as text, or nothing. */
private fun clipboardText(context: Context): String {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }
    return clip
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()
        .orEmpty()
}

/** Blocks screenshots while Terminal shows, and the pinch gesture with it. */
@Composable
private fun GuardWindow() {
    val window = LocalActivity.current?.window
    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        MultiTouchGate.active = true
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            MultiTouchGate.active = false
        }
    }
}

/**
 * The emulator, sized so its grid is always the pane's real size. The grid the phone would like is
 * worked out the way the emulator does it (whole cells of the font's "M" advance and line height)
 * and goes up as the viewport. While the pane has another size, for example because the desk took
 * it back, the emulator is forced to the pane's size and shrinks its font to fit.
 */
@Composable
private fun TerminalPane(
    controller: TerminalController,
    typeface: Typeface,
    keyboard: Boolean,
    onKeyboardChange: (Boolean) -> Unit,
    onPaste: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val pane by controller.paneSize.collectAsStateWithLifecycle()
    BoxWithConstraints(modifier = modifier.background(RafiqTheme.colors.hinge)) {
        val fontPx = with(density) { TERMINAL_FONT.toPx() }
        val cell =
            remember(typeface, fontPx) {
                val paint =
                    TextPaint().apply {
                        this.typeface = typeface
                        textSize = fontPx
                        isAntiAlias = true
                    }
                paint.measureText("M") to ceil(paint.fontMetrics.descent - paint.fontMetrics.ascent)
            }
        val desired = viewportCells(constraints.maxWidth, constraints.maxHeight, cell.first, cell.second)
        LaunchedEffect(desired) { controller.onViewport(desired) }
        // The emulator's forced size is rows, then columns.
        val forced = pane?.takeIf { it != desired }?.let { it.rows to it.cols }
        Terminal(
            terminalEmulator = controller.emulator,
            modifier = Modifier.fillMaxSize(),
            typeface = typeface,
            initialFontSize = TERMINAL_FONT,
            minFontSize = MIN_FORCED_FONT,
            maxFontSize = TERMINAL_FONT,
            backgroundColor = RafiqTheme.colors.hinge,
            foregroundColor = RafiqTheme.colors.text,
            keyboardEnabled = true,
            showSoftKeyboard = keyboard,
            onTerminalTap = { onKeyboardChange(true) },
            onImeVisibilityChanged = onKeyboardChange,
            forcedSize = forced,
            modifierManager = controller.modifiers,
            onHyperlinkClick = controller::onLinkTapped,
            onPasteRequest = onPaste,
        )
    }
}
