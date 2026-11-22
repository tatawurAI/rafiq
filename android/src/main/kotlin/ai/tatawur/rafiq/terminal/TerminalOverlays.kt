package ai.tatawur.rafiq.terminal

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.client.CommandResult
import ai.tatawur.rafiq.client.terminal.MirroredWindow
import ai.tatawur.rafiq.client.terminal.ScrollbackState
import ai.tatawur.rafiq.ui.theme.RafiqTheme
import ai.tatawur.rafiq.ui.theme.plexMono
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.abs

// What Terminal shows over or beside the pane: the history overlay and the swipe that opens it, the
// window picker and your server's notices.

@Composable
internal fun NoticeStrip(
    text: String,
    onDismiss: () -> Unit,
) {
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
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = spacing.minTouchTarget)) {
            Text(stringResource(R.string.feedback_dismiss))
        }
    }
}

/** The session's windows, by index and name. Picking one changes only the phone's view. */
@Composable
internal fun WindowPickerDialog(controller: TerminalController) {
    val picker by controller.picker.collectAsStateWithLifecycle()
    val open = picker ?: return
    AlertDialog(
        onDismissRequest = controller::closeWindowPicker,
        title = { Text(stringResource(R.string.terminal_windows_title)) },
        text = { WindowList(open, controller::selectWindow) },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = controller::closeWindowPicker,
            ) { Text(stringResource(R.string.terminal_windows_close)) }
        },
    )
}

@Composable
private fun WindowList(
    picker: WindowPicker,
    onSelect: (Int) -> Unit,
) {
    val spacing = RafiqTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xSmall)) {
        Text(
            stringResource(R.string.terminal_windows_hint),
            style = MaterialTheme.typography.bodySmall,
            color = RafiqTheme.colors.textSecondary,
        )
        val windows = picker.windows
        if (windows == null) {
            Text(stringResource(R.string.terminal_connecting), style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        Column(modifier = Modifier.heightIn(max = WINDOW_LIST_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
            windows.forEach { window ->
                val label = windowName(window)
                val shown = window.index == picker.shown
                TextButton(
                    onClick = { onSelect(window.index) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = spacing.minTouchTarget),
                ) {
                    Text(
                        if (shown) stringResource(R.string.terminal_window_shown, label) else label,
                        modifier = Modifier.fillMaxWidth(),
                        color = if (shown) RafiqTheme.colors.accent else RafiqTheme.colors.text,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun windowName(window: MirroredWindow): String =
    if (window.name.isEmpty()) {
        stringResource(R.string.terminal_window_unnamed, window.index)
    } else {
        stringResource(R.string.terminal_window, window.index, window.name)
    }

private val WINDOW_LIST_MAX_HEIGHT = 320.dp

/** Rows of finger travel per scroll step: one wheel turn, or a share of a page. */
internal const val ROWS_PER_SCROLL_STEP = 2f

/**
 * Reports a vertical swipe as a start and then one step for each [stepPx] of travel, finger down
 * meaning older lines. It watches before the emulator does and takes the swipe only once it is
 * clearly vertical, so taps, long presses and sideways moves still reach the emulator.
 */
internal fun Modifier.scrollSwipes(
    stepPx: Float,
    onStart: () -> Unit,
    onStep: (older: Boolean) -> Unit,
): Modifier =
    pointerInput(stepPx) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var travel = Offset.Zero
            var dragging = false
            var pending = 0f
            var watching = true
            while (watching) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                val delta = change?.takeIf { it.pressed }?.positionChange()
                when {
                    change == null || delta == null -> {
                        watching = false
                    }

                    dragging -> {
                        pending = steps(pending + delta.y, stepPx, onStep)
                        change.consume()
                    }

                    else -> {
                        travel += delta
                        val slop = viewConfiguration.touchSlop
                        // Sideways first: leave it to the emulator.
                        watching = abs(travel.x) <= slop || abs(travel.x) < abs(travel.y)
                        if (watching && abs(travel.y) > slop) {
                            dragging = true
                            onStart()
                            pending = steps(travel.y, stepPx, onStep)
                            change.consume()
                        }
                    }
                }
            }
        }
    }

/** Reports a step for each whole [stepPx] in [travel] and returns what is left over. */
private fun steps(
    travel: Float,
    stepPx: Float,
    onStep: (older: Boolean) -> Unit,
): Float {
    var left = travel
    while (left >= stepPx) {
        onStep(true)
        left -= stepPx
    }
    while (left <= -stepPx) {
        onStep(false)
        left += stepPx
    }
    return left
}

/**
 * The pane's history over the live screen, oldest line first and opened at the newest. Reading it
 * changes nothing on your server. It stays open while the pane keeps drawing, with a "New output"
 * chip, and closes when you type, tap "Back to live" or the chip, or press Back.
 */
@Composable
internal fun HistoryOverlay(controller: TerminalController) {
    val scrollback = controller.scrollback
    val state by scrollback.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state != ScrollbackState.Live, onBack = scrollback::backToLive)
    when (val shown = state) {
        ScrollbackState.Live -> {}

        ScrollbackState.Loading -> {
            NoticeStrip(stringResource(R.string.terminal_history_loading), scrollback::backToLive)
        }

        is ScrollbackState.Failed -> {
            NoticeStrip(stringResource(historyFailure(shown.result)), scrollback::backToLive)
        }

        is ScrollbackState.Showing -> {
            HistoryLines(shown, scrollback::backToLive)
        }
    }
}

@Composable
private fun HistoryLines(
    shown: ScrollbackState.Showing,
    onLive: () -> Unit,
) {
    val spacing = RafiqTheme.spacing
    Box(modifier = Modifier.fillMaxSize().background(RafiqTheme.colors.ground)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(
                            RafiqTheme.colors.surface,
                        ).padding(start = spacing.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.terminal_history_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = RafiqTheme.colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onLive, modifier = Modifier.heightIn(min = spacing.minTouchTarget)) {
                    Text(stringResource(R.string.terminal_back_to_live))
                }
            }
            val list = rememberLazyListState(initialFirstVisibleItemIndex = shown.lines.lastIndex.coerceAtLeast(0))
            LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(horizontal = spacing.small)) {
                items(shown.lines) { line ->
                    Text(
                        line.ifEmpty { " " },
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = plexMono),
                        color = RafiqTheme.colors.text,
                    )
                }
            }
        }
        if (shown.newOutput) {
            Surface(
                onClick = onLive,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(spacing.small)
                        .heightIn(min = spacing.minTouchTarget),
                shape = RafiqTheme.shapes.pill,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = spacing.medium)) {
                    Text(stringResource(R.string.terminal_new_output), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** A short plain reason the history could not be read. */
@StringRes
private fun historyFailure(result: CommandResult<Nothing>): Int =
    when (result) {
        CommandResult.NotFound -> {
            R.string.terminal_history_not_found
        }

        CommandResult.Offline -> {
            R.string.terminal_history_offline
        }

        is CommandResult.RateLimited -> {
            R.string.terminal_history_rate_limited
        }

        is CommandResult.Failed -> {
            if (result.code ==
                INVALID_QUERY
            ) {
                R.string.terminal_history_bad_request
            } else {
                R.string.terminal_history_failed
            }
        }

        else -> {
            R.string.terminal_history_failed
        }
    }

// The history route's answer to a request it cannot read (400).
private const val INVALID_QUERY = "invalid_query"
