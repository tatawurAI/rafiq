package ai.tatawur.rafiq.sessions

import ai.tatawur.rafiq.client.CommandResult
import ai.tatawur.rafiq.client.ConnectionState
import ai.tatawur.rafiq.client.SessionListItem
import ai.tatawur.rafiq.client.SessionRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Everything the session list shows.
 *
 * @property isLoaded false until the repository's first list arrives, so an empty cache is not
 *   mistaken for "no sessions".
 * @property items the rows under the current [filter], newest update first.
 * @property counts how many rows each filter chip would show.
 * @property oldestWaiting the session that has waited on you longest, whatever the filter. The
 *   half-folded layout shows it below the hinge with full-size buttons.
 * @property answering approval ids whose answer is on its way. Their buttons are disabled until the
 *   repository drops the approval, or until the answer fails.
 */
data class SessionListUiState(
    val isLoaded: Boolean = false,
    val items: List<SessionListItem> = emptyList(),
    val filter: SessionFilter = SessionFilter.ALL,
    val counts: Map<SessionFilter, Int> = emptyMap(),
    val connection: ConnectionState = ConnectionState.Connecting,
    val answering: Set<String> = emptySet(),
    val feedback: Feedback? = null,
    val oldestWaiting: SessionListItem? = null,
)

/** State holder for the session list. It reads only from [repository]. */
class SessionListViewModel(
    private val repository: SessionRepository,
) : ViewModel() {
    private val filter = MutableStateFlow(SessionFilter.ALL)
    private val answering = MutableStateFlow<Set<String>>(emptySet())
    private val feedback = MutableStateFlow<Feedback?>(null)

    val state: StateFlow<SessionListUiState> =
        combine(
            repository.sessions(),
            filter,
            answering,
            feedback,
            repository.connection,
        ) { all, filter, answering, feedback, connection ->
            val openIds = all.flatMapTo(mutableSetOf()) { item -> item.openApprovals.map { it.id } }
            SessionListUiState(
                isLoaded = true,
                items = all.filter(filter::matches),
                filter = filter,
                counts = all.countsByFilter(),
                connection = connection,
                answering = answering intersect openIds,
                feedback = feedback,
                oldestWaiting = all.oldestWaiting(),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SessionListUiState())

    fun selectFilter(value: SessionFilter) {
        filter.value = value
    }

    /**
     * Answers an approval from its row. A second tap while the first answer is on its way does
     * nothing. After a successful answer the buttons stay disabled until `approval_resolved` makes the
     * repository drop the approval.
     */
    fun approve(
        approvalId: String,
        optionId: String,
    ) {
        if (approvalId in answering.value) return
        answering.update { it + approvalId }
        viewModelScope.launch {
            val result = repository.approve(approvalId, optionId)
            if (result !is CommandResult.Ok) answering.update { it - approvalId }
            feedback.value = result.feedback()
        }
    }

    fun dismissFeedback() {
        feedback.value = null
    }

    private companion object {
        // Keeps the upstream flow alive across a configuration change such as unfolding.
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
