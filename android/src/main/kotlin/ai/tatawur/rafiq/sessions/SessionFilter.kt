package ai.tatawur.rafiq.sessions

import ai.tatawur.rafiq.client.SessionListItem
import ai.tatawur.rafiq.protocol.AgentState

/**
 * The filter chips above the session list.
 *
 * A session waiting on you counts under [NEEDS_YOU] and not under [WORKING], even while its agent
 * keeps running, so the two chips never show the same row. [DONE] holds every finished session,
 * including ones that ended in an error.
 */
enum class SessionFilter {
    ALL,
    NEEDS_YOU,
    WORKING,
    DONE,
    ;

    /** True when [item] belongs under this filter. */
    fun matches(item: SessionListItem): Boolean =
        when (this) {
            ALL -> true
            NEEDS_YOU -> item.needsYou
            WORKING -> !item.needsYou && item.session.status == AgentState.WORKING
            DONE -> !item.needsYou && item.session.status in finishedStates
        }

    private companion object {
        val finishedStates = setOf(AgentState.DONE, AgentState.ERROR)
    }
}

/** How many sessions each chip would show, for the counts on the chips. */
fun List<SessionListItem>.countsByFilter(): Map<SessionFilter, Int> =
    SessionFilter.entries.associateWith { filter -> count(filter::matches) }
