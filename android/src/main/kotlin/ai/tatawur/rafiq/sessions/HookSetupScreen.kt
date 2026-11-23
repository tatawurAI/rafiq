@file:Suppress("TooManyFunctions") // One screen: its small parts and their previews.

package ai.tatawur.rafiq.sessions

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.client.HookSetupModel
import ai.tatawur.rafiq.client.HookSetupNotice
import ai.tatawur.rafiq.client.HookSetupState
import ai.tatawur.rafiq.client.SessionRepository
import ai.tatawur.rafiq.protocol.api.HookSetupPreview
import ai.tatawur.rafiq.ui.components.FeedbackBar
import ai.tatawur.rafiq.ui.theme.RafiqTheme
import ai.tatawur.rafiq.ui.theme.plexMono
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow

/** The hook setup screen, wired to its [HookSetupViewModel]. */
@Composable
fun HookSetupRoute(
    viewModel: HookSetupViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HookSetupScreen(state, onBack, viewModel::setGitExclude, viewModel::confirm, viewModel::reload)
}

/**
 * Consent for the reader's hooks in one project (#44). It shows the file your server would write, the
 * whole file exactly as it would be, the hook events and what the hook sends, and says that Claude Code
 * asks its own trust question the first time, which Rafiq never answers. Nothing is written until you tap
 * the button, and removing takes one tap too.
 */
@Composable
fun HookSetupScreen(
    state: HookSetupState,
    onBack: () -> Unit,
    onGitExcludeChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = RafiqTheme.spacing
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .consumeWindowInsets(WindowInsets.safeDrawing),
    ) {
        HookSetupHeader(onBack)
        when (state) {
            HookSetupState.Loading -> {
                CenteredNote(stringResource(R.string.hooks_loading))
            }

            is HookSetupState.Unavailable -> {
                Column(modifier = Modifier.padding(spacing.medium)) {
                    val feedback = state.result.feedback()
                    if (feedback == Feedback.NotFound || feedback == null) {
                        Text(
                            stringResource(R.string.hooks_unavailable),
                            style = MaterialTheme.typography.bodyMedium,
                            color = RafiqTheme.colors.textSecondary,
                        )
                    } else {
                        FeedbackBar(feedback, onDismiss = onRetry)
                    }
                }
            }

            is HookSetupState.Ready -> {
                HookSetupBody(state, onGitExcludeChange, onConfirm)
            }
        }
    }
}

@Composable
private fun HookSetupHeader(onBack: () -> Unit) {
    val spacing = RafiqTheme.spacing
    Row(
        modifier = Modifier.fillMaxWidth().padding(spacing.xSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(R.drawable.ic_back),
                contentDescription = stringResource(R.string.back),
                modifier = Modifier.size(spacing.icon),
                tint = RafiqTheme.colors.text,
            )
        }
        Text(
            stringResource(R.string.hooks_title),
            style = MaterialTheme.typography.titleLarge,
            color = RafiqTheme.colors.text,
        )
    }
}

@Composable
private fun HookSetupBody(
    state: HookSetupState.Ready,
    onGitExcludeChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
) {
    val spacing = RafiqTheme.spacing
    val colors = RafiqTheme.colors
    val preview = state.preview
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.medium, vertical = spacing.small),
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        Text(stringResource(R.string.hooks_intro), style = MaterialTheme.typography.bodyMedium, color = colors.text)
        if (preview.installed) {
            Text(
                stringResource(R.string.hooks_already),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.accent,
            )
        }
        Field(stringResource(R.string.hooks_target), preview.targetFile, code = true)
        Field(stringResource(R.string.hooks_events), preview.events.joinToString(", "), code = true)
        Field(stringResource(R.string.hooks_sends), preview.sends, code = false)
        FileText(
            stringResource(if (preview.installed) R.string.hooks_file_text_remove else R.string.hooks_file_text),
            preview.fileText,
        )
        if (!preview.installed) {
            Text(
                stringResource(R.string.hooks_trust),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.needsYou,
            )
            if (preview.gitExcludeOffered) GitExcludeRow(state.addGitExclude, !state.working, onGitExcludeChange)
        }
        state.notice?.let { NoticeText(it) }
        ConfirmButton(preview.installed, state.working, onConfirm)
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    code: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(RafiqTheme.spacing.xSmall)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = RafiqTheme.colors.textMuted)
        val style = MaterialTheme.typography.bodyMedium
        Text(
            value,
            style = if (code) style.copy(fontFamily = plexMono) else style,
            color = RafiqTheme.colors.text,
        )
    }
}

/** The whole file as your server would leave it, unwrapped, scrolling sideways for long lines. */
@Composable
private fun FileText(
    label: String,
    text: String,
) {
    val spacing = RafiqTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xSmall)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = RafiqTheme.colors.textMuted)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            color = RafiqTheme.colors.surface,
            border = BorderStroke(spacing.hairline, RafiqTheme.colors.border),
        ) {
            Text(
                text = text,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(spacing.smallMedium),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = plexMono),
                color = RafiqTheme.colors.textSecondary,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun GitExcludeRow(
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = RafiqTheme.spacing.minTouchTarget)
                .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(
            stringResource(R.string.hooks_git_exclude),
            modifier = Modifier.padding(start = RafiqTheme.spacing.small),
            style = MaterialTheme.typography.bodyMedium,
            color = RafiqTheme.colors.text,
        )
    }
}

@Composable
private fun NoticeText(notice: HookSetupNotice) {
    when (notice) {
        is HookSetupNotice.Failed -> {
            FeedbackBar(notice.result.feedback(), onDismiss = {})
        }

        else -> {
            val text =
                when (notice) {
                    HookSetupNotice.Installed -> R.string.hooks_installed
                    HookSetupNotice.Removed -> R.string.hooks_removed
                    else -> R.string.hooks_file_changed
                }
            val color =
                if (notice ==
                    HookSetupNotice.FileChanged
                ) {
                    RafiqTheme.colors.needsYou
                } else {
                    RafiqTheme.colors.accent
                }
            Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = color)
        }
    }
}

@Composable
private fun ConfirmButton(
    installed: Boolean,
    working: Boolean,
    onConfirm: () -> Unit,
) {
    val spacing = RafiqTheme.spacing
    val modifier = Modifier.fillMaxWidth().heightIn(min = spacing.largeTouchTarget)
    val label =
        stringResource(
            when {
                working -> R.string.hooks_working
                installed -> R.string.hooks_remove
                else -> R.string.hooks_install
            },
        )
    if (installed) {
        OutlinedButton(
            onClick = onConfirm,
            enabled = !working,
            modifier = modifier,
            border = BorderStroke(spacing.hairline, RafiqTheme.colors.deletion),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = RafiqTheme.colors.deletion),
        ) { Text(label) }
    } else {
        Button(onClick = onConfirm, enabled = !working, modifier = modifier) { Text(label) }
    }
}

private val previewHooks =
    HookSetupPreview(
        projectPath = "/srv/weather-app",
        targetFile = "/srv/weather-app/.claude/settings.local.json",
        fileText =
            """
            {
              "hooks": {
                "Stop": [
                  { "hooks": [ { "type": "command", "command": "/opt/rafiq/bin/rafiq-hook", "async": true } ] }
                ]
              }
            }
            """.trimIndent(),
        previewHash = "preview",
        events = listOf("UserPromptSubmit", "PreToolUse", "PostToolUse", "Stop", "PermissionRequest"),
        sends = "Each hook event as JSON, to a socket on your server that only the bridge can open.",
        gitExcludeOffered = true,
    )

@Preview(name = "Hook setup", widthDp = 412, heightDp = 1100)
@Composable
private fun HookSetupPreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            HookSetupScreen(HookSetupState.Ready(previewHooks, addGitExclude = true), {}, {}, {}, {})
        }
    }
}

@Preview(name = "Hook setup, installed", widthDp = 412, heightDp = 900)
@Composable
private fun HookSetupInstalledPreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            HookSetupScreen(
                HookSetupState.Ready(previewHooks.copy(installed = true), notice = HookSetupNotice.Installed),
                {},
                {},
                {},
                {},
            )
        }
    }
}
