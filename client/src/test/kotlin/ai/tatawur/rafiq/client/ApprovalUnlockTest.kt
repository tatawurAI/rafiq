package ai.tatawur.rafiq.client

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Issue #14: with the lock on, an answer goes only with a recent strong biometric check behind it. */
class ApprovalUnlockTest {
    private val key = FakeKey()
    private var lockTurnedOff = false
    private var asked = 0
    private val unlock = ApprovalUnlock(key) { lockTurnedOff = true }

    @Test
    fun a_recent_fingerprint_unlock_is_enough_and_no_second_check_is_asked() =
        runTest {
            key.state = KeyUse.USABLE

            assertEquals(Unlock.ALLOWED, unlock.check(lockOn = true) { ask(passes = true) })
            assertEquals(0, asked)
        }

    @Test
    fun outside_the_window_it_asks_once_and_sends_only_if_the_check_passed() =
        runTest {
            key.state = KeyUse.NEEDS_CHECK
            assertEquals(Unlock.REFUSED, unlock.check(lockOn = true) { ask(passes = false) })

            assertEquals(Unlock.ALLOWED, unlock.check(lockOn = true) { ask(passes = true) })
            assertEquals(2, asked)
        }

    @Test
    fun a_key_destroyed_by_a_new_fingerprint_sends_nothing_and_turns_the_lock_off() =
        runTest {
            key.state = KeyUse.INVALIDATED

            assertEquals(Unlock.LOCK_TURNED_OFF, unlock.check(lockOn = true) { ask(passes = true) })
            assertEquals(true, lockTurnedOff)
            assertEquals(true, key.deleted)
        }

    @Test
    fun a_keystore_failure_sends_nothing_and_leaves_the_lock_on() =
        runTest {
            key.state = KeyUse.BROKEN

            assertEquals(Unlock.REFUSED, unlock.check(lockOn = true) { ask(passes = true) })
            assertEquals(false, lockTurnedOff)
        }

    @Test
    fun with_the_lock_off_the_key_is_not_touched() =
        runTest {
            key.state = KeyUse.INVALIDATED

            assertEquals(Unlock.ALLOWED, unlock.check(lockOn = false) { ask(passes = false) })
            assertEquals(0, key.uses)
        }

    private fun ask(passes: Boolean): Boolean {
        asked++
        if (passes && key.state == KeyUse.NEEDS_CHECK) key.state = KeyUse.USABLE
        return passes
    }

    private class FakeKey : ApprovalKey {
        var state = KeyUse.MISSING
        var uses = 0
        var deleted = false

        override fun use(): KeyUse = state.also { uses++ }

        override fun create(): Boolean {
            state = KeyUse.NEEDS_CHECK
            return true
        }

        override fun delete() {
            deleted = true
        }
    }
}
