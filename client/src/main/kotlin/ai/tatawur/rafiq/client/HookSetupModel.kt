package ai.tatawur.rafiq.client

import ai.tatawur.rafiq.client.sync.BridgeApi
import ai.tatawur.rafiq.protocol.api.HookSetupPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the hook setup screen shows. */
sealed interface HookSetupState {
    data object Loading : HookSetupState

    /**
     * The preview, exactly as your server sent it.
     *
     * @property addGitExclude whether the install also lists the file in `.git/info/exclude`. Off until you
     *   tick it.
     * @property working true while an install or remove is on its way.
     * @property notice the outcome of the last tap, or null.
     */
    data class Ready(
        val preview: HookSetupPreview,
        val addGitExclude: Boolean = false,
        val working: Boolean = false,
        val notice: HookSetupNotice? = null,
    ) : HookSetupState

    /** The preview could not be read, for example because the session has no agent with hooks. */
    data class Unavailable(
        val result: CommandResult<Nothing>,
    ) : HookSetupState
}

/** The outcome of an install or remove, for one line on the screen. */
sealed interface HookSetupNotice {
    data object Installed : HookSetupNotice

    data object Removed : HookSetupNotice

    /** The file changed on your server since the preview. The screen now shows the new preview. */
    data object FileChanged : HookSetupNotice

    data class Failed(
        val result: CommandResult<Nothing>,
    ) : HookSetupNotice
}

/**
 * The state holder behind the hook setup screen. It writes nothing until you tap, and each tap sends the
 * hash of the preview you were looking at, so your server refuses to write anything you did not see. When
 * the file changed in between, it loads the new preview and waits for another tap.
 */
class HookSetupModel(
    private val repository: SessionRepository,
    private val sessionId: String,
    private val scope: CoroutineScope,
) {
    private val current = MutableStateFlow<HookSetupState>(HookSetupState.Loading)

    val state: StateFlow<HookSetupState> = current.asStateFlow()

    /** Reads the preview again. */
    fun load() {
        scope.launch { current.value = fetch(notice = null) }
    }

    fun setGitExclude(on: Boolean) {
        current.update { state -> if (state is HookSetupState.Ready) state.copy(addGitExclude = on) else state }
    }

    /** Installs the hooks the preview shows, or removes them when they are installed. Your explicit tap. */
    fun confirm() {
        val ready = current.value as? HookSetupState.Ready ?: return
        if (ready.working) return
        current.value = ready.copy(working = true, notice = null)
        scope.launch {
            val preview = ready.preview
            val result =
                if (preview.installed) {
                    repository.removeHooks(sessionId, preview.previewHash)
                } else {
                    repository.installHooks(sessionId, preview.previewHash, ready.addGitExclude)
                }
            current.value =
                when {
                    result is CommandResult.Ok -> {
                        fetch(if (preview.installed) HookSetupNotice.Removed else HookSetupNotice.Installed)
                    }

                    result is CommandResult.Failed && result.code == BridgeApi.HOOKS_FILE_CHANGED -> {
                        fetch(HookSetupNotice.FileChanged)
                    }

                    else -> {
                        ready.copy(working = false, notice = HookSetupNotice.Failed(result.withoutValue()))
                    }
                }
        }
    }

    private suspend fun fetch(notice: HookSetupNotice?): HookSetupState =
        when (val result = repository.hookSetup(sessionId)) {
            is CommandResult.Ok -> HookSetupState.Ready(result.value, notice = notice)
            else -> HookSetupState.Unavailable(result.withoutValue())
        }
}
