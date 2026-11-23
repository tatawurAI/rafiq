package ai.tatawur.rafiq.bridge.setup

import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class ProcessCommandRunnerTest {
    @Test
    fun a_program_that_prints_and_exits_gives_its_output_and_exit_code() {
        val result = ProcessCommandRunner().run(listOf("sh", "-c", "echo box.example.ts.net; exit 3"))

        assertEquals(CommandResult(3, "box.example.ts.net\n"), result)
    }

    // A hung `tailscale status --json` must not hang setup. SEPARATE_THREAD fails the test at the limit even while
    // the runner is stuck in a read that cannot be interrupted.
    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun a_program_that_never_finishes_is_given_up_on_after_the_timeout() {
        val result = ProcessCommandRunner(timeout = 300.milliseconds).run(listOf("sh", "-c", "echo started; sleep 60"))

        assertEquals(124, result.exitCode)
    }
}
