package ai.tatawur.rafiq.sessions

import ai.tatawur.rafiq.client.CommandResult
import ai.tatawur.rafiq.client.SessionRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The "New session" form.
 *
 * @property agents what the bridge can start, or null while the list loads or after it failed.
 * @property agentsFeedback why the agent list could not load.
 * @property createdSessionId set once the bridge started the session, so the screen can open it.
 */
data class NewSessionUiState(
    val agents: List<String>? = null,
    val agentsFeedback: Feedback? = null,
    val agent: String? = null,
    val projectPath: String = "",
    val title: String = "",
    val creating: Boolean = false,
    val feedback: Feedback? = null,
    val createdSessionId: String? = null,
) {
    val canCreate: Boolean
        get() = agent != null && projectPath.isNotBlank() && !creating
}

/** State holder for "New session". It reads only from [repository]. */
class NewSessionViewModel(
    private val repository: SessionRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(NewSessionUiState())
    val state: StateFlow<NewSessionUiState> = mutableState.asStateFlow()

    init {
        loadAgents()
    }

    fun loadAgents() {
        mutableState.update { it.copy(agentsFeedback = null) }
        viewModelScope.launch {
            when (val result = repository.agents()) {
                is CommandResult.Ok -> {
                    mutableState.update {
                        // With one agent there is nothing to choose, so it is picked for you.
                        it.copy(agents = result.value, agent = it.agent ?: result.value.singleOrNull())
                    }
                }

                else -> {
                    mutableState.update { it.copy(agentsFeedback = result.feedback()) }
                }
            }
        }
    }

    fun selectAgent(agent: String) {
        mutableState.update { it.copy(agent = agent) }
    }

    fun updateProjectPath(path: String) {
        mutableState.update { it.copy(projectPath = path) }
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
