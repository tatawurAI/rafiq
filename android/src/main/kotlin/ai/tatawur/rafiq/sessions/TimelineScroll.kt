package ai.tatawur.rafiq.sessions

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * Where one thread's timeline is scrolled to and whether it follows new content. The session screen
 * holds it above the fold split, so folding to Flex Mode, which moves the timeline into the top
 * pane, keeps your reading position.
 *
 * The list state is made on first use, with the thread's items already loaded, so a thread opens at
 * its latest item without first drawing its oldest.
 */
@Stable
internal class TimelineScroll(
    follow: Boolean = true,
    restored: LazyListState? = null,
) {
    private var list: LazyListState? = restored

    var follow by mutableStateOf(follow)

    fun listState(lastIndex: Int): LazyListState =
        list ?: LazyListState(firstVisibleItemIndex = lastIndex.coerceAtLeast(0)).also { list = it }

    companion object {
        val Saver: Saver<TimelineScroll, Any> =
            listSaver(
                save = {
                    listOfNotNull(
                        it.follow,
                        it.list?.firstVisibleItemIndex,
                        it.list?.firstVisibleItemScrollOffset,
                    )
                },
                restore = { saved ->
                    val index = saved.getOrNull(1) as Int?
                    val offset = saved.getOrNull(2) as Int?
                    TimelineScroll(
                        follow = saved[0] as Boolean,
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
