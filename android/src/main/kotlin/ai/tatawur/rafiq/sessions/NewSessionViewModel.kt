package ai.tatawur.rafiq.sessions

import ai.tatawur.rafiq.client.CommandResult
import ai.tatawur.rafiq.client.PathCheck
import ai.tatawur.rafiq.client.ProjectPickerModel
import ai.tatawur.rafiq.client.ProjectPickerState
import ai.tatawur.rafiq.client.SessionListItem
import ai.tatawur.rafiq.client.SessionRepository
import ai.tatawur.rafiq.protocol.SessionMode
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The "New session" form.
 *
 * @property agents what the bridge can start, or null while the list loads or after it failed.
 * @property agentsFeedback why the agent list could not load.
 * @property project the project folder: recent folders, browsing, and the path Start uses.
 * @property modeChoices the permission modes the agent advertised for your last session with it, or empty.
 * @property modeId the mode to start in, or null for your server's default for the agent.
 * @property createdSessionId set once the bridge started the session, so the screen can open it.
 */
data class NewSessionUiState(
    val agents: List<String>? = null,
    val agentsFeedback: Feedback? = null,
    val agent: String? = null,
    val project: ProjectPickerState = ProjectPickerState(),
    val modeChoices: List<SessionMode> = emptyList(),
    val modeId: String? = null,
    val title: String = "",
    val creating: Boolean = false,
    val feedback: Feedback? = null,
    val createdSessionId: String? = null,
) {
    val projectPath: String
        get() = project.path

    val canCreate: Boolean
        get() = startBlocker == null

    /** Why "Start session" is disabled, shown under it, or null when it can start. */
    val startBlocker: StartBlocker?
        get() =
            when {
                creating -> StartBlocker.STARTING
                agent == null -> StartBlocker.NO_AGENT
                projectPath.isBlank() -> StartBlocker.NO_PROJECT
                project.check == PathCheck.Checking -> StartBlocker.CHECKING_PROJECT
                !project.check.allowsStart -> StartBlocker.BAD_PROJECT
                else -> null
            }
}

/** What stops "Start session" from starting, in the order you would fix it. */
enum class StartBlocker {
    STARTING,
    NO_AGENT,
    NO_PROJECT,
    CHECKING_PROJECT,
    BAD_PROJECT,
}

/** State holder for "New session". It reads only from [repository]. */
class NewSessionViewModel(
    private val repository: SessionRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(NewSessionUiState())
    val state: StateFlow<NewSessionUiState> = mutableState.asStateFlow()

    /** The project folder part of the form. The screen calls it directly. */
    val project = ProjectPickerModel(repository, viewModelScope)

    // Modes come from the agent, so they are known only from an earlier session with it.
    private var knownModes: Map<String, List<SessionMode>> = emptyMap()

    init {
        loadAgents()
        project.load()
        viewModelScope.launch { project.state.collect { picker -> mutableState.update { it.copy(project = picker) } } }
        viewModelScope.launch {
            knownModes = modesByAgent(repository.sessions().first())
            mutableState.update { it.copy(modeChoices = knownModes[it.agent].orEmpty()) }
        }
    }

    fun loadAgents() {
        mutableState.update { it.copy(agentsFeedback = null) }
        viewModelScope.launch {
            when (val result = repository.agents()) {
                is CommandResult.Ok -> {
                    mutableState.update {
                        // The first agent is picked for you, so the form starts one field from ready.
                        val agent = it.agent ?: result.value.firstOrNull()
                        it.copy(agents = result.value, agent = agent, modeChoices = knownModes[agent].orEmpty())
                    }
                }

                else -> {
                    mutableState.update { it.copy(agentsFeedback = result.feedback()) }
                }
            }
        }
    }

    fun selectAgent(agent: String) {
        mutableState.update { it.copy(agent = agent, modeChoices = knownModes[agent].orEmpty(), modeId = null) }
    }

    /** The mode to start in, or null for your server's default. */
    fun selectMode(modeId: String?) {
        mutableState.update { it.copy(modeId = modeId) }
    }

    fun updateTitle(title: String) {
        mutableState.update { it.copy(title = title) }
    }

    fun dismissFeedback() {
        mutableState.update { it.copy(feedback = null) }
    }

    fun create() {
        val form = mutableState.value
        val agent = form.agent ?: return
        if (!form.canCreate) return
        mutableState.update { it.copy(creating = true, feedback = null) }
        viewModelScope.launch {
            val result =
                repository.createSession(
                    agent = agent,
                    projectPath = form.projectPath.trim(),
                    title = form.title.trim().ifEmpty { null },
                    modeId = form.modeId,
                )
            mutableState.update {
                it.copy(
                    creating = false,
                    feedback = result.feedback(),
                    createdSessionId = (result as? CommandResult.Ok)?.value?.id,
                )
            }
        }
    }
}

/** Each agent's modes, from its most recently updated session that advertised any. The list is newest first. */
internal fun modesByAgent(sessions: List<SessionListItem>): Map<String, List<SessionMode>> =
    sessions
        .map { it.session }
        .filter { it.availableModes.isNotEmpty() }
        .groupBy { it.agent }
        .mapValues { (_, newestFirst) -> newestFirst.first().availableModes }
