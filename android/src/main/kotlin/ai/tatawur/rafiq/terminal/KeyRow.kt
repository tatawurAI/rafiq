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

/** The keys a phone keyboard lacks, as the emulator's key codes. */
private enum class RowKey(
    val vterm: Int,
    val label: Int,
    val description: Int,
) {
    ESCAPE(VTermKey.ESCAPE, R.string.terminal_key_esc, R.string.terminal_key_esc),
    TAB(VTermKey.TAB, R.string.terminal_key_tab, R.string.terminal_key_tab),
    LEFT(VTermKey.LEFT, R.string.terminal_key_left_symbol, R.string.terminal_key_left),
    UP(VTermKey.UP, R.string.terminal_key_up_symbol, R.string.terminal_key_up),
    DOWN(VTermKey.DOWN, R.string.terminal_key_down_symbol, R.string.terminal_key_down),
    RIGHT(VTermKey.RIGHT, R.string.terminal_key_right_symbol, R.string.terminal_key_right),
}

/**
 * Esc, Tab, sticky Ctrl and Alt (lit while armed), the arrows, Paste and the keyboard switch. It
 * scrolls sideways when the screen is narrow.
 */
@Composable
internal fun KeyRow(
    ctrlArmed: Boolean,
    altArmed: Boolean,
    onKey: (vtermKey: Int) -> Unit,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onPaste: () -> Unit,
    onToggleKeyboard: () -> Unit,
) {
    val spacing = RafiqTheme.spacing
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(RafiqTheme.colors.surface)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = spacing.xSmall, vertical = spacing.xSmall),
        horizontalArrangement = Arrangement.spacedBy(spacing.xSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowKeyButton(RowKey.ESCAPE, onKey)
        RowKeyButton(RowKey.TAB, onKey)
        Key(stringResource(R.string.terminal_key_ctrl), armed = ctrlArmed, onClick = onToggleCtrl)
        Key(stringResource(R.string.terminal_key_alt), armed = altArmed, onClick = onToggleAlt)
        RowKeyButton(RowKey.LEFT, onKey)
        RowKeyButton(RowKey.UP, onKey)
        RowKeyButton(RowKey.DOWN, onKey)
        RowKeyButton(RowKey.RIGHT, onKey)
        Key(stringResource(R.string.terminal_key_paste), onClick = onPaste)
        Key(stringResource(R.string.terminal_key_keyboard), onClick = onToggleKeyboard)
    }
}

@Composable
private fun RowKeyButton(
    key: RowKey,
    onKey: (Int) -> Unit,
) {
    Key(stringResource(key.label), description = stringResource(key.description)) { onKey(key.vterm) }
}

@Composable
private fun Key(
    label: String,
    armed: Boolean = false,
    description: String = label,
    onClick: () -> Unit,
) {
    val spacing = RafiqTheme.spacing
    val colors = RafiqTheme.colors
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraSmall,
        color = if (armed) MaterialTheme.colorScheme.primary else colors.ground,
        contentColor = if (armed) MaterialTheme.colorScheme.onPrimary else colors.text,
        modifier =
            Modifier
                .heightIn(min = spacing.minTouchTarget)
                .widthIn(min = spacing.minTouchTarget)
                .semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = spacing.smallMedium)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Asks before a link from the pane opens, and shows where it really goes. */
@Composable
internal fun LinkConfirmation(
    target: String,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.terminal_link_title)) },
        text = { Text(target, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = plexMono)) },
        confirmButton = { TextButton(onClick = onOpen) { Text(stringResource(R.string.terminal_link_open)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.terminal_link_cancel)) } },
    )
}
