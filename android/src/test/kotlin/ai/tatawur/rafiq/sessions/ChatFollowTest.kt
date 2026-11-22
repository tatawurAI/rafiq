package ai.tatawur.rafiq.sessions

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatFollowTest {
    private val readingUp = ChatFollow(lastSeenKey = "m1").userSettled(atBottom = false).next

    @Test
    fun sending_scrolls_to_the_bottom_even_while_reading_higher_up() {
        val step = readingUp.contentChanged("m2").next.sent()

        assertTrue(step.scrollToEnd)
        assertTrue(step.next.following)
        assertFalse(step.next.newBelow)
    }

    @Test
    fun a_message_arriving_while_at_the_bottom_scrolls_to_it() {
        val step = ChatFollow(lastSeenKey = "m1").contentChanged("m2")

        assertTrue(step.scrollToEnd)
        assertFalse(step.next.newBelow)
    }

    @Test
    fun a_message_arriving_while_scrolled_up_stays_put_and_raises_the_pill() {
        val step = readingUp.contentChanged("m2")

        assertFalse(step.scrollToEnd)
        assertTrue(step.next.newBelow)
    }

    @Test
    fun the_last_message_growing_while_scrolled_up_is_not_a_new_message() {
        val step = readingUp.contentChanged("m1")

        assertFalse(step.scrollToEnd)
        assertFalse(step.next.newBelow)
    }

    @Test
    fun returning_to_chat_scrolls_only_if_you_were_at_the_bottom() {
        assertTrue(ChatFollow().returned().scrollToEnd)

        val back = readingUp.returned()
        assertFalse(back.scrollToEnd)
        assertEquals(readingUp, back.next)
    }

    @Test
    fun the_pill_scrolls_to_the_bottom_and_reaching_the_bottom_by_hand_clears_it() {
        val withPill = readingUp.contentChanged("m2").next

        val tapped = withPill.jumpedToLatest()
        assertTrue(tapped.scrollToEnd)
        assertFalse(tapped.next.newBelow)

        val scrolledDown = withPill.userSettled(atBottom = true).next
        assertTrue(scrolledDown.following)
        assertFalse(scrolledDown.newBelow)
    }
}
