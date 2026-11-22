package ai.tatawur.rafiq.terminal

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When Terminal shows. Hiding it is what closes its socket (see TerminalLinkTest), so each way of
 * leaving has to land here as "not shown".
 */
class TerminalChoiceTest {
    private fun TerminalChoice.shows(
        cover: Boolean = false,
        lockOn: Boolean = false,
    ) = shows(capable = true, cover = cover, lockOn = lockOn)

    @Test
    fun the_toggle_and_the_cover_screen_both_leave_terminal() {
        val open = TerminalChoice().terminal(lockOn = false)
        assertTrue(open.shows())

        assertFalse(open.chat().shows())
        assertFalse(open.shows(cover = true))
    }

    @Test
    fun a_session_that_is_not_a_terminal_never_shows_one() {
        assertFalse(TerminalChoice().terminal(lockOn = false).shows(capable = false, cover = false, lockOn = false))
    }

    @Test
    fun with_the_lock_on_terminal_opens_only_after_the_biometric_check() {
        assertFalse(TerminalChoice().terminal(lockOn = true).shows(lockOn = true))
        assertTrue(TerminalChoice().passedCheck().shows(lockOn = true))
    }

    @Test
    fun the_check_lasts_only_until_you_leave_terminal() {
        val unlocked = TerminalChoice().passedCheck()

        assertFalse(unlocked.leftScreen().shows(lockOn = true), "background or cover screen")
        assertFalse(unlocked.chat().terminal(lockOn = true).shows(lockOn = true), "Chat, then Terminal again")
        // With the lock off, coming back from the background reopens Terminal.
        assertTrue(TerminalChoice().terminal(lockOn = false).leftScreen().shows(lockOn = false))
    }
}
