package ai.tatawur.rafiq.client

/**
 * What an approval card shows of the request. The agent controls this text, and an injected agent can
 * hide a second command after a harmless first line, so the card never shortens a multi-line request to its
 * first line. It shows the line count whenever there is more than one line, says when lines are hidden
 * until you tap, and marks the place where your server cut a text that was too long to send.
 *
 * @property lines the lines to show now, in order.
 * @property lineCount the number of lines in the whole request, including any your server cut off.
 * @property hiddenLines lines of the request that are not in [lines]: hidden until you tap, or cut off.
 * @property canExpand true when tapping shows more than [lines].
 * @property cut true when your server cut the request. The card shows a marker after the last line it has.
 * @property sessionRule the rule that "allow for this session" adds, or null when the agent gave none.
 */
data class ApprovalText(
    val lines: List<String>,
    val lineCount: Int,
    val hiddenLines: Int,
    val canExpand: Boolean,
    val cut: Boolean,
    val sessionRule: String?,
) {
    /** True when the card says how many lines the request has. */
    val showsLineCount: Boolean
        get() = lineCount > 1

    companion object {
        /** Lines shown before you tap. Two short commands always show in full. */
        const val COLLAPSED_LINES = 4

        /** Characters shown before you tap, so one very long line does not fill the screen. */
        const val COLLAPSED_CHARS = 400

        /** The text of [card], collapsed to [COLLAPSED_LINES] and [COLLAPSED_CHARS] unless [expanded]. */
        fun of(
            card: ApprovalCard,
            expanded: Boolean,
        ): ApprovalText {
            val detail = card.detail
            val text = detail?.text ?: card.description
            val all = text.removeSuffix("\n").lines()
            val lineCount = maxOf(detail?.lineCount ?: all.size, all.size)
            val shown = if (expanded) all else collapse(all)
            val shownChars = shown.sumOf { it.length }
            val moreHere = shown.size < all.size || shownChars < all.sumOf { it.length }
            return ApprovalText(
                lines = shown,
                lineCount = lineCount,
                hiddenLines = lineCount - shown.size,
                canExpand = moreHere,
                cut = detail?.truncated == true,
                sessionRule = detail?.sessionRule,
            )
        }

        private fun collapse(all: List<String>): List<String> {
            val shown = mutableListOf<String>()
            var budget = COLLAPSED_CHARS
            for (line in all.take(COLLAPSED_LINES)) {
                if (budget <= 0) break
                shown += line.take(budget)
                budget -= line.length
            }
            return shown
        }
    }
}
