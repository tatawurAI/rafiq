package ai.tatawur.rafiq.settings

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Settings that guard what the phone can do on your server. They fail closed: a settings file that
 * is damaged or cannot be read counts as the Terminal lock being on, until you change it.
 */
class SecuritySettings(
    private val prefs: DataStore<Preferences>,
) {
    /** True when Terminal opens only after a strong biometric check. Off until you turn it on. */
    val terminalLock: Flow<Boolean> =
        prefs.data
            .catch { failure -> if (failure is IOException) emit(LOCKED) else throw failure }
            .map { it[TERMINAL_LOCK] ?: false }

    suspend fun setTerminalLock(on: Boolean) {
        prefs.edit { it[TERMINAL_LOCK] = on }
    }

    companion object {
        private val TERMINAL_LOCK = booleanPreferencesKey("terminal_biometric")
        private val LOCKED = preferencesOf(TERMINAL_LOCK to true)

        /**
         * For the settings file: a damaged one is replaced with the lock on, never with defaults,
         * so damage cannot switch the lock off.
         */
        val corruptionHandler: ReplaceFileCorruptionHandler<Preferences> = ReplaceFileCorruptionHandler { LOCKED }
    }
}
