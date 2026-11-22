package ai.tatawur.rafiq.sessions

/**
 * The chat scroll rule, as a value the timeline's scroll holder keeps. Sending always takes you to
 * the bottom. New content and coming back to Chat keep you at the bottom only if you were already
 * there. If you were reading higher up the list stays put, and new messages below raise a pill
 * instead.
 *
 * Each step returns the next value and whether the list must scroll to its end.
 *
 * @property following true while the list sits at the bottom and follows new content.
 * @property newBelow true when a message arrived below while you were reading higher up.
 * @property lastSeenKey the key of the newest item when the rule last looked, so content that only
 *   grows, or a screen that is shown again, is not mistaken for a new message.
 */
internal data class ChatFollow(
    val following: Boolean = true,
    val newBelow: Boolean = false,
    val lastSeenKey: String? = null,
) {
    /** One step of the rule: the next value and whether the list must scroll to the bottom now. */
    data class Step(
        val next: ChatFollow,
        val scrollToEnd: Boolean,
    )

    /** You sent a message or a quick reply. */
    fun sent(): Step = Step(copy(following = true, newBelow = false), scrollToEnd = true)

    /** The newest item is now [lastKey], or it changed in place. */
    fun contentChanged(lastKey: String?): Step {
        if (following) return Step(copy(lastSeenKey = lastKey), scrollToEnd = true)
        val arrived = lastKey != null && lastKey != lastSeenKey
        return Step(copy(newBelow = newBelow || arrived, lastSeenKey = lastKey), scrollToEnd = false)
    }

    /** Chat is shown again, for example after Terminal or another screen. */
    fun returned(): Step = Step(this, scrollToEnd = following)

    /** Your own scroll settled, at the bottom or not. */
    fun userSettled(atBottom: Boolean): Step =
        Step(copy(following = atBottom, newBelow = newBelow && !atBottom), scrollToEnd = false)

    /** You tapped "Jump to latest" or the new messages pill. */
    fun jumpedToLatest(): Step = sent()

    /** The screen brought an older item into view, for example after "Jump to it". */
    fun jumpedAway(): Step = Step(copy(following = false), scrollToEnd = false)
}
