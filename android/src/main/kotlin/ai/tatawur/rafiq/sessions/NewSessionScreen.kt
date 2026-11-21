package ai.tatawur.rafiq.sessions

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.ui.components.FeedbackBar
import ai.tatawur.rafiq.ui.theme.RafiqTheme
import ai.tatawur.rafiq.ui.theme.plexMono
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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

/** Pick an agent, give the project folder on your server and an optional title, then start. */
@Composable
fun NewSessionScreen(
    state: NewSessionUiState,
    actions: NewSessionActions,
    modifier: Modifier = Modifier,
) {
    val spacing = RafiqTheme.spacing
    val colors = RafiqTheme.colors
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.medium, vertical = spacing.small),
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        FormHeader(actions.onBack)
        AgentPicker(state, actions)

        val fieldColors = formFieldColors()
        OutlinedTextField(
            value = state.projectPath,
            onValueChange = actions.onProjectPathChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.new_session_project)) },
            placeholder = { Text(stringResource(R.string.new_session_project_hint)) },
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
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            colors = fieldColors,
        )
        FeedbackBar(state.feedback, actions.onDismissFeedback)
        Button(
            onClick = actions.onCreate,
            enabled = state.canCreate,
            modifier = Modifier.fillMaxWidth().heightIn(min = spacing.composerHeight),
            shape = MaterialTheme.shapes.small,
        ) { Text(stringResource(R.string.new_session_start)) }
    }
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

@Preview(name = "New session, cover screen", widthDp = 360, heightDp = 400)
@Composable
private fun NewSessionCoverPreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            NewSessionScreen(NewSessionUiState(agents = listOf("claude-code", "codex")), NewSessionActions())
        }
    }
}
