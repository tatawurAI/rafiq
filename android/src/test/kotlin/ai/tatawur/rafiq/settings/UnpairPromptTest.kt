package ai.tatawur.rafiq.settings

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Settings Unpair row reaches unpair, but only after you confirm, and only once. */
class UnpairPromptTest {
    private var unpairs = 0
    private val prompt = UnpairPrompt { unpairs++ }

    @Test
    fun confirming_the_prompt_unpairs_once() {
        prompt.ask()
        assertTrue(prompt.asking.value)

        prompt.confirm()
        prompt.confirm()
        prompt.ask()

        assertEquals(1, unpairs)
        assertFalse(prompt.asking.value)
    }

    @Test
    fun backing_out_or_confirming_without_asking_never_unpairs() {
        prompt.confirm()
        prompt.ask()
        prompt.cancel()
        prompt.confirm()

        assertEquals(0, unpairs)
        assertFalse(prompt.asking.value)
    }
}
