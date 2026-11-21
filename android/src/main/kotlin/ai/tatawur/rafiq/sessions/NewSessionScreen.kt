package ai.tatawur.rafiq.sessions

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.ui.components.FeedbackBar
import ai.tatawur.rafiq.ui.theme.RafiqTheme
import ai.tatawur.rafiq.ui.theme.plexMono
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** "New session", wired to its [NewSessionViewModel]. Opens the session once the bridge started it. */
@Composable
fun NewSessionRoute(
    viewModel: NewSessionViewModel,
    onBack: () -> Unit,
    onCreated: (sessionId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val latestOnCreated by rememberUpdatedState(onCreated)
    LaunchedEffect(state.createdSessionId) {
        state.createdSessionId?.let { latestOnCreated(it) }
    }
    val actions =
        remember(viewModel, onBack) {
            NewSessionActions(
                onBack = onBack,
                onRetryAgents = viewModel::loadAgents,
                onSelectAgent = viewModel::selectAgent,
                onProjectPathChange = viewModel::updateProjectPath,
                onTitleChange = viewModel::updateTitle,
                onCreate = viewModel::create,
                onDismissFeedback = viewModel::dismissFeedback,
            )
        }
    NewSessionScreen(state, actions, modifier)
}

/**
 * Pick an agent, give the project folder on your server and an optional title, then start. The
 * form scrolls while "Start session" stays at the bottom, above the keyboard when it is up, with
 * the reason it is disabled underneath.
 */
@Composable
fun NewSessionScreen(
    state: NewSessionUiState,
    actions: NewSessionActions,
    modifier: Modifier = Modifier,
) {
    val spacing = RafiqTheme.spacing
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .consumeWindowInsets(WindowInsets.safeDrawing)
                .padding(horizontal = spacing.medium, vertical = spacing.small),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.medium),
        ) {
            FormHeader(actions.onBack)
            AgentPicker(state, actions)
            FormFields(state, actions)
            FeedbackBar(state.feedback, actions.onDismissFeedback)
        }
        Button(
            onClick = actions.onCreate,
            enabled = state.canCreate,
            modifier = Modifier.fillMaxWidth().heightIn(min = spacing.largeTouchTarget),
            shape = MaterialTheme.shapes.small,
            colors =
                ButtonDefaults.buttonColors(
                    disabledContainerColor = RafiqTheme.colors.border,
                    disabledContentColor = RafiqTheme.colors.textSecondary,
                ),
        ) { Text(stringResource(R.string.new_session_start)) }
        state.startBlocker?.let { blocker ->
            Text(
                text = stringResource(blockerText(blocker)),
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall,
                color = RafiqTheme.colors.textMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The project folder and title fields, each with a label that stays and a line of help. */
@Composable
private fun FormFields(
    state: NewSessionUiState,
    actions: NewSessionActions,
) {
    val fieldColors = formFieldColors()
    OutlinedTextField(
        value = state.projectPath,
        onValueChange = actions.onProjectPathChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.new_session_project)) },
        placeholder = { Text(stringResource(R.string.new_session_project_hint)) },
        supportingText = { Text(stringResource(R.string.new_session_project_help)) },
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = plexMono),
        singleLine = true,
        keyboardOptions =
            KeyboardOptions(
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
            ),
        colors = fieldColors,
    )
    OutlinedTextField(
        value = state.title,
        onValueChange = actions.onTitleChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.new_session_name)) },
        supportingText = { Text(stringResource(R.string.new_session_name_help)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        colors = fieldColors,
    )
}

@StringRes
private fun blockerText(blocker: StartBlocker): Int =
    when (blocker) {
        StartBlocker.STARTING -> R.string.new_session_starting
        StartBlocker.NO_AGENT -> R.string.new_session_needs_agent
        StartBlocker.NO_PROJECT -> R.string.new_session_needs_project
    }

@Composable
private fun formFieldColors(): TextFieldColors {
    val colors = RafiqTheme.colors
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = colors.accent,
        unfocusedBorderColor = colors.borderStrong,
        focusedLabelColor = colors.accent,
        unfocusedLabelColor = colors.textMuted,
        cursorColor = colors.accent,
    )
}

@Composable
private fun FormHeader(onBack: () -> Unit) {
    val spacing = RafiqTheme.spacing
    val colors = RafiqTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(R.drawable.ic_back),
                contentDescription = stringResource(R.string.back),
                modifier = Modifier.size(spacing.icon),
                tint = colors.text,
            )
        }
        Text(
            stringResource(R.string.new_session_title),
            style = MaterialTheme.typography.titleLarge,
            color = colors.text,
        )
    }
}

@Composable
private fun AgentPicker(
    state: NewSessionUiState,
    actions: NewSessionActions,
) {
    val spacing = RafiqTheme.spacing
    val colors = RafiqTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        Text(
            stringResource(R.string.new_session_agent),
            style = MaterialTheme.typography.labelMedium,
            color = colors.textMuted,
        )
        val agents = state.agents
        when {
            state.agentsFeedback != null -> {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
                    FeedbackBar(state.agentsFeedback, onDismiss = actions.onRetryAgents)
                    TextButton(
                        onClick = actions.onRetryAgents,
                        modifier = Modifier.heightIn(min = spacing.minTouchTarget),
                    ) {
                        Text(stringResource(R.string.new_session_agents_retry))
                    }
                }
            }

            agents == null -> {
                Text(
                    stringResource(R.string.new_session_agents_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted,
                )
            }

            agents.isEmpty() -> {
                Text(
                    stringResource(R.string.new_session_agents_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                )
            }

            else -> {
                AgentChips(agents, state.agent, actions.onSelectAgent)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentChips(
    agents: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    val spacing = RafiqTheme.spacing
    val colors = RafiqTheme.colors
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(spacing.small),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        agents.forEach { agent ->
            FilterChip(
                selected = selected == agent,
                onClick = { onSelect(agent) },
                label = { Text(agent) },
                shape = RafiqTheme.shapes.pill,
                colors =
                    FilterChipDefaults.filterChipColors(
                        labelColor = colors.textSecondary,
                        selectedContainerColor = colors.text,
                        selectedLabelColor = colors.ground,
                    ),
            )
        }
    }
}

@Preview(name = "New session, tall", widthDp = 412, heightDp = 920)
@Composable
private fun NewSessionTallPreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            NewSessionScreen(
                NewSessionUiState(
                    agents = listOf("claude-code", "codex"),
                    agent = "claude-code",
                    projectPath = "/srv/weather-app",
                ),
                NewSessionActions(),
            )
        }
    }
}

@Preview(name = "New session, cover screen", widthDp = 399, heightDp = 353)
@Composable
private fun NewSessionCoverPreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            NewSessionScreen(
                NewSessionUiState(agents = listOf("claude-code", "codex"), agent = "claude-code"),
                NewSessionActions(),
            )
        }
    }
}
