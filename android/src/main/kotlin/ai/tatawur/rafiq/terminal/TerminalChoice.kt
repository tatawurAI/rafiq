package ai.tatawur.rafiq.terminal

/**
 * Whether a session screen shows Terminal or Chat, and whether the biometric check has been passed
 * for this visit to Terminal. The unlock lasts only while Terminal shows: switching to Chat, the
 * cover screen and the app going to the background all end it, so the next visit asks again.
 *
 * @property chosen you picked Terminal on the toggle.
 * @property unlocked the biometric check passed since Terminal last opened.
 */
internal data class TerminalChoice(
    val chosen: Boolean = false,
    val unlocked: Boolean = false,
) {
    /**
     * True when Terminal shows. It needs a session the bridge serves as a terminal, the main screen
     * rather than the cover screen, and, with [lockOn], a passed biometric check.
     */
    fun shows(
        capable: Boolean,
        cover: Boolean,
        lockOn: Boolean,
    ): Boolean = capable && chosen && !cover && (unlocked || !lockOn)

    /** You picked Chat on the toggle. */
    fun chat(): TerminalChoice = TerminalChoice()

    /** You picked Terminal. With [lockOn], it shows only after [passedCheck]. */
    fun terminal(lockOn: Boolean): TerminalChoice = if (lockOn) TerminalChoice() else TerminalChoice(chosen = true)

    /** The biometric check passed. */
    fun passedCheck(): TerminalChoice = TerminalChoice(chosen = true, unlocked = true)

    /** The app went to the background, or the cover screen took over. */
    fun leftScreen(): TerminalChoice = copy(unlocked = false)
}
