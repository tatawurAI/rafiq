package ai.tatawur.rafiq.bridge.tmux

import org.junit.jupiter.api.Timeout
import java.nio.file.Files
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

    @Test
    fun a_pane_on_a_tmux_server_the_bridge_starts_has_no_rafiq_variable() {
        TestTmux().use { tmux ->
            val out = Files.createTempFile("rafiq-pane-env", ".txt")
            try {
                // The bridge's own environment holds the device token. Its tmux processes must not pass it on.
                val builder =
                    ProcessBuilder(
                        tmux.server.commandLine("new-session", "-d", "-s", "env-check", "env > '$out'; sleep 60"),
                    )
                builder.environment()["RAFIQ_DEVICE_TOKEN"] = "placeholder-device-token"
                cleanEnvironment(builder)
                assertEquals(0, builder.start().waitFor())
                runCapturing(tmux.server.commandLine("new-window", "-t", "=env-check", "env >> '$out'; sleep 60"), 10)

                eventually("both panes wrote their environment") {
                    Files.readAllLines(out).count { it.startsWith("PATH=") } == 2
                }
                assertEquals(emptyList(), Files.readAllLines(out).filter { it.startsWith("RAFIQ_") })
                assertEquals(emptyList(), inheritedRafiqVariables(tmux.server))
            } finally {
                Files.deleteIfExists(out)
            }
        }
    }

    @Test
    fun a_rafiq_variable_already_in_the_tmux_server_is_found_by_name() {
        TestTmux().use { tmux ->
            tmux.newSession("env-check")
            tmux.tmux("set-environment", "-g", "RAFIQ_DEVICE_TOKEN", "placeholder-device-token")
            tmux.tmux("set-environment", "-g", "-r", "RAFIQ_PORT")

            assertEquals(listOf("RAFIQ_DEVICE_TOKEN"), inheritedRafiqVariables(tmux.server))
        }
    }
}
