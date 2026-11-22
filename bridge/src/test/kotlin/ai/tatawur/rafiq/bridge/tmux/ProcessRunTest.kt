package ai.tatawur.rafiq.bridge.tmux

import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@Timeout(60)
class ProcessRunTest {
    @Test
    fun output_and_errors_are_read_together_so_a_noisy_stderr_cannot_stall_the_run() {
        // 300 KB on stderr is far more than a pipe holds. Read one after the other, this never finishes.
        val result = runCapturing(listOf("sh", "-c", "head -c 300000 /dev/zero >&2; echo done"), timeoutSeconds = 20)

        assertNotNull(result)
        assertEquals("done\n", result.out)
        assertEquals(300_000, result.err.length)
    }

    @Test
    fun a_program_that_hangs_is_killed_with_its_children_and_the_caller_is_released() {
        // The shell and the sleep under it both hold the output pipe.
        // Killing only the shell would leave the read waiting.
        val result = runCapturing(listOf("sh", "-c", "sleep 300; echo never"), timeoutSeconds = 1)

        assertNull(result)
    }

    @Test
    fun the_tmux_variable_and_every_rafiq_variable_stay_out_of_a_child() {
        val builder = ProcessBuilder("true")
        builder.environment().putAll(
            mapOf(
                "TMUX" to "x",
                "RAFIQ_DEVICE_TOKEN" to "x",
                "RAFIQ_PORT" to "1",
                "PATH_KEEP" to "y",
            ),
        )

        cleanEnvironment(builder)

        assertEquals(
            setOf("PATH_KEEP"),
            builder.environment().keys.intersect(setOf("TMUX", "RAFIQ_DEVICE_TOKEN", "RAFIQ_PORT", "PATH_KEEP")),
        )
        assertFalse(builder.environment().keys.any { it.startsWith("RAFIQ_") })
    }
}
