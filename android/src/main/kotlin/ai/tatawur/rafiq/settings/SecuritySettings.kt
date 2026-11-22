package ai.tatawur.rafiq.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Settings that guard what the phone can do on your server. */
class SecuritySettings(
    private val prefs: DataStore<Preferences>,
) {
    /** True when Terminal opens only after a strong biometric check. Off until you turn it on. */
    val terminalLock: Flow<Boolean> = prefs.data.map { it[TERMINAL_LOCK] ?: false }

    suspend fun setTerminalLock(on: Boolean) {
        prefs.edit { it[TERMINAL_LOCK] = on }
    }

    private companion object {
        val TERMINAL_LOCK = booleanPreferencesKey("terminal_biometric")
    }
}
