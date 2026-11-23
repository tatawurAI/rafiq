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
 * is damaged or cannot be read counts as both locks being on, until you change them.
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

    /** True when answering an approval needs a strong biometric check first. Off until you turn it on. */
    val approvalLock: Flow<Boolean> =
        prefs.data
            .catch { failure -> if (failure is IOException) emit(LOCKED) else throw failure }
            .map { it[APPROVAL_LOCK] ?: false }

    /** Turning the lock on or off by hand also clears [approvalLockReset]. */
    suspend fun setApprovalLock(on: Boolean) {
        prefs.edit {
            it[APPROVAL_LOCK] = on
            it[APPROVAL_LOCK_RESET] = false
        }
    }

    /**
     * True after the approval lock turned itself off because the phone's biometrics changed (issue #14), until
     * you turn it on or off again. Settings says why the lock is off.
     */
    val approvalLockReset: Flow<Boolean> =
        prefs.data
            .catch { failure -> if (failure is IOException) emit(LOCKED) else throw failure }
            .map { it[APPROVAL_LOCK_RESET] ?: false }

    /** The approval lock's key was destroyed by a biometric change: the lock turns off, and says why. */
    suspend fun approvalLockKeyInvalidated() {
        prefs.edit {
            it[APPROVAL_LOCK] = false
            it[APPROVAL_LOCK_RESET] = true
        }
    }

    companion object {
        private val TERMINAL_LOCK = booleanPreferencesKey("terminal_biometric")
        private val APPROVAL_LOCK = booleanPreferencesKey("approval_biometric")
        private val APPROVAL_LOCK_RESET = booleanPreferencesKey("approval_biometric_reset")
        private val LOCKED = preferencesOf(TERMINAL_LOCK to true, APPROVAL_LOCK to true)

        /**
         * For the settings file: a damaged one is replaced with the lock on, never with defaults,
         * so damage cannot switch the lock off.
         */
        val corruptionHandler: ReplaceFileCorruptionHandler<Preferences> = ReplaceFileCorruptionHandler { LOCKED }
    }
}
