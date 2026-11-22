package ai.tatawur.rafiq.sessions

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * Where one thread's timeline is scrolled to, and the [ChatFollow] rule that decides when it jumps
 * to the bottom. The session screen holds it above the fold split and above the Chat and Terminal
 * toggle, so folding to Flex Mode or coming back from Terminal keeps your reading position.
 *
 * The list state is made on first use, with the thread's items already loaded, so a thread opens at
 * its latest item without first drawing its oldest.
 */
@Stable
internal class TimelineScroll(
    rule: ChatFollow = ChatFollow(),
    restored: LazyListState? = null,
) {
    private var list: LazyListState? = restored

    var rule by mutableStateOf(rule)
        private set

    /** True while the list owes a scroll to its end. The timeline does it, then calls [scrolledToEnd]. */
    var pendingScrollToEnd by mutableStateOf(false)
        private set

    fun listState(lastIndex: Int): LazyListState =
        list ?: LazyListState(firstVisibleItemIndex = lastIndex.coerceAtLeast(0)).also { list = it }

    fun sent() = apply(rule.sent())

    fun contentChanged(lastKey: String?) = apply(rule.contentChanged(lastKey))

    fun returned() = apply(rule.returned())

    fun userSettled(atBottom: Boolean) = apply(rule.userSettled(atBottom))

    fun jumpedToLatest() = apply(rule.jumpedToLatest())

    fun jumpedAway() = apply(rule.jumpedAway())

    fun scrolledToEnd() {
        pendingScrollToEnd = false
    }

    private fun apply(step: ChatFollow.Step) {
        rule = step.next
        if (step.scrollToEnd) pendingScrollToEnd = true
    }

    companion object {
        private const val FOLLOWING = "following"
        private const val NEW_BELOW = "new_below"
        private const val LAST_KEY = "last_key"
        private const val INDEX = "index"
        private const val OFFSET = "offset"

        val Saver: Saver<TimelineScroll, Any> =
            mapSaver(
                save = {
                    mapOf(
                        FOLLOWING to it.rule.following,
                        NEW_BELOW to it.rule.newBelow,
                        LAST_KEY to it.rule.lastSeenKey,
                        INDEX to it.list?.firstVisibleItemIndex,
                        OFFSET to it.list?.firstVisibleItemScrollOffset,
                    )
                },
                restore = { saved ->
                    val index = saved[INDEX] as Int?
                    val offset = saved[OFFSET] as Int?
                    TimelineScroll(
                        rule =
                            ChatFollow(
                                following = saved[FOLLOWING] as Boolean,
                                newBelow = saved[NEW_BELOW] as Boolean,
                                lastSeenKey = saved[LAST_KEY] as String?,
                            ),
                        restored = if (index != null && offset != null) LazyListState(index, offset) else null,
                    )
                },
            )
    }
}

/** A [TimelineScroll] per selected thread, so each tab opens at its latest item. */
@Composable
internal fun rememberTimelineScroll(threadId: String?): TimelineScroll =
    rememberSaveable(threadId, saver = TimelineScroll.Saver) { TimelineScroll() }
