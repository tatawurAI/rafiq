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

/**
 * The Terminal view's layout. Flat, the header, the pane and the key row stack in one column. Half
 * folded, the pane sits above the hinge at a fixed height and the key row and keyboard below it,
 * so opening the keyboard never changes the pane's rows or columns.
 */
@Composable
internal fun TerminalLayout(
    hinge: Hinge?,
    header: @Composable () -> Unit,
    status: TerminalStatus,
    onRetry: () -> Unit,
    pane: @Composable (Modifier) -> Unit,
    keyRow: @Composable () -> Unit,
) {
    val top: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier = modifier) {
            header()
            StatusStrip(status, onRetry)
            pane(Modifier.weight(1f).fillMaxWidth())
        }
    }
    FoldSplit(
        hinge = hinge,
        single = {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .consumeWindowInsets(WindowInsets.safeDrawing),
            ) {
                top(Modifier.weight(1f).fillMaxWidth())
                keyRow()
            }
        },
        top = { top(Modifier.fillMaxSize()) },
        bottom = { Column(modifier = Modifier.fillMaxWidth()) { keyRow() } },
    )
}

@Composable
private fun StatusStrip(
    status: TerminalStatus,
    onRetry: () -> Unit,
) {
    val text =
        when (status) {
            TerminalStatus.Live -> {
                null
            }

            TerminalStatus.Detached, TerminalStatus.Connecting -> {
                stringResource(R.string.terminal_connecting)
            }

            is TerminalStatus.Ended -> {
                status.reason?.let { stringResource(R.string.terminal_ended, it) }
                    ?: stringResource(R.string.terminal_closed)
            }

            is TerminalStatus.Failed -> {
                stringResource(R.string.terminal_failed)
            }
        } ?: return
    val spacing = RafiqTheme.spacing
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(RafiqTheme.colors.surface)
                .padding(horizontal = spacing.medium, vertical = spacing.xSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = RafiqTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        if (status is TerminalStatus.Ended || status is TerminalStatus.Failed) {
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = spacing.minTouchTarget)) {
                Text(stringResource(R.string.terminal_reconnect))
            }
        }
    }
}

@Composable
private fun PreviewPane(modifier: Modifier) {
    Box(modifier = modifier.background(RafiqTheme.colors.hinge))
}

@Composable
private fun PreviewKeyRow() {
    KeyRow(ctrlArmed = true, altArmed = false, onKey = {}, onToggleCtrl = {}, onToggleAlt = {}, onPaste = {}) {}
}

@Preview(name = "Terminal, flat", widthDp = 412, heightDp = 840)
@Composable
private fun TerminalFlatPreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            TerminalLayout(null, {}, TerminalStatus.Connecting, {}, { PreviewPane(it) }) { PreviewKeyRow() }
        }
    }
}

@Preview(name = "Terminal, half folded", widthDp = 412, heightDp = 840)
@Composable
private fun TerminalHalfFoldedPreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            TerminalLayout(previewHalfFoldHinge, {}, TerminalStatus.Live, {}, { PreviewPane(it) }) { PreviewKeyRow() }
        }
    }
}

@Preview(name = "Terminal link confirmation", widthDp = 360)
@Composable
private fun LinkConfirmationPreview() {
    RafiqTheme { LinkConfirmation("https://example.com/docs/setup", {}, {}) }
}
