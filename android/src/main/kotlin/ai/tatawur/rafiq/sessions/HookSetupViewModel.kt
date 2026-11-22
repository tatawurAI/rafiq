package ai.tatawur.rafiq.sessions

import ai.tatawur.rafiq.client.HookSetupModel
import ai.tatawur.rafiq.client.HookSetupState
import ai.tatawur.rafiq.client.SessionRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow

/** The hook setup screen's state holder: [HookSetupModel] in the screen's scope. */
class HookSetupViewModel(
    repository: SessionRepository,
    sessionId: String,
) : ViewModel() {
    private val model = HookSetupModel(repository, sessionId, viewModelScope)

    val state: StateFlow<HookSetupState> = model.state

    init {
        model.load()
    }

    fun setGitExclude(on: Boolean) = model.setGitExclude(on)

    fun confirm() = model.confirm()

    fun reload() = model.load()
}
