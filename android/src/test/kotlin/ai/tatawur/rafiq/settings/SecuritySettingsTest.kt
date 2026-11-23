package ai.tatawur.rafiq.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Terminal and approval locks fail closed. */
class SecuritySettingsTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun a_damaged_settings_file_turns_the_lock_on() =
        runTest {
            val file = File(dir, "settings.preferences_pb").apply { writeBytes(byteArrayOf(0x7f, 0x01, 0x02, 0x03)) }
            val store =
                PreferenceDataStoreFactory.create(
                    corruptionHandler = SecuritySettings.corruptionHandler,
                    scope = backgroundScope,
                    produceFile = { file },
                )

            assertTrue(SecuritySettings(store).terminalLock.first())
            assertTrue(SecuritySettings(store).approvalLock.first())
        }

    @Test
    fun a_settings_file_that_cannot_be_read_counts_as_locked() =
        runTest {
            val unreadable =
                object : DataStore<Preferences> {
                    override val data: Flow<Preferences> = flow { throw IOException("read failed") }

                    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences) =
                        throw IOException("write failed")
                }

            assertTrue(SecuritySettings(unreadable).terminalLock.first())
            assertTrue(SecuritySettings(unreadable).approvalLock.first())
        }

    @Test
    fun a_destroyed_approval_key_turns_the_lock_off_and_says_why_until_you_change_it() =
        runTest {
            val store =
                PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { File(dir, "s.preferences_pb") },
                )
            val settings = SecuritySettings(store)
            settings.setApprovalLock(true)

            settings.approvalLockKeyInvalidated()
            assertFalse(settings.approvalLock.first())
            assertTrue(settings.approvalLockReset.first())

            settings.setApprovalLock(true)
            assertFalse(settings.approvalLockReset.first())

            settings.approvalLockKeyInvalidated()
            settings.dismissApprovalLockReset()
            assertFalse(settings.approvalLockReset.first())
            assertFalse(settings.approvalLock.first(), "dismissing the notice must not turn the lock back on")
        }
}
