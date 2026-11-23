package ai.tatawur.rafiq.bridge.setup

import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SetupCliTest {
    @TempDir
    lateinit var home: Path

    private val file get() = home.resolve(".config/rafiq/bridge.conf")

    /**
     * Answers questions from a script, in order, and records each one. An empty answer takes the question's default,
     * as at a terminal. Running out of answers is the end of input, which is no.
     */
    private class ScriptedConsole(
        answers: List<String>,
    ) : SetupConsole {
        private val remaining = ArrayDeque(answers)
        private val bytes = ByteArrayOutputStream()
        override val out = PrintStream(bytes, true)
        val questions = mutableListOf<String>()
        val printed: String get() = bytes.toString()

        override fun confirm(
            question: String,
            default: Boolean,
        ): Boolean {
            questions += question
            out.println(question)
            val answer = remaining.removeFirstOrNull() ?: return false
            return if (answer.isEmpty()) default else answer == "y"
        }

        override fun ask(
            question: String,
            default: String,
        ): String {
            questions += question
            return remaining.removeFirstOrNull() ?: default
        }
    }

    private val tailscaleJson = """{"BackendState":"Running","Self":{"DNSName":"box.example.ts.net."}}"""
    private val commands = mutableListOf<List<String>>()
    private val runner =
        CommandRunner { command ->
            commands += command
            if (command.take(2) ==
                listOf("tailscale", "status")
            ) {
                CommandResult(0, tailscaleJson)
            } else {
                CommandResult(0, "")
            }
        }
    private val everyProgram = ProgramFinder { Path.of("/usr/local/bin").resolve(it) }
    private val paired = mutableListOf<Pair<Map<String, String>, String>>()
    private var servicesInstalled = 0

    private fun setup(
        console: SetupConsole,
        environment: Map<String, String> = mapOf("HOME" to home.toString()),
        bridgeRunning: () -> Boolean = { true },
    ) = SetupCli(
        environment = environment,
        console = console,
        programs = everyProgram,
        runner = runner,
        pair = { settings, name ->
            paired += settings to name
            0
        },
        installService = {
            servicesInstalled++
            0
        },
        bridgeRunning = { bridgeRunning() },
        pause = {},
    )

    @Test
    fun answering_no_to_everything_writes_nothing_and_runs_only_the_read_only_status_check() {
        val console = ScriptedConsole(emptyList())

        setup(console, bridgeRunning = { false }).run()

        assertFalse(Files.exists(file))
        assertEquals(listOf(listOf("tailscale", "status", "--json")), commands)
        // One question each for the data folder, the agent and the host, and one for tailscale serve. The agent's
        // default mode is not asked once the agent is declined. With no data folder there is nothing to pair with.
        assertEquals(4, console.questions.size, console.printed)
        assertTrue(paired.isEmpty())
    }

    @Test
    fun yes_writes_the_found_settings_runs_tailscale_serve_and_ends_by_pairing_with_the_merged_settings() {
        // Data folder, agent, its default mode, host, tailscale serve, then the phone's name.
        val console = ScriptedConsole(listOf("y", "y", "y", "y", "y", "pixel"))

        val exit = setup(console).run()

        assertEquals(0, exit, console.printed)
        val written = ConfigFile.read(file)
        assertEquals("box.example.ts.net", written["RAFIQ_ALLOWED_HOSTS"])
        assertEquals("claude-code=claude-agent-acp", written["RAFIQ_AGENTS"])
        // Without it claude-agent-acp starts sessions in a mode that edits without asking.
        assertTrue("RAFIQ_DEFAULT_MODES=claude-code=default\n" in Files.readString(file))
        assertEquals(home.resolve(".local/share/rafiq").toString(), written["RAFIQ_DATA_DIR"])
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        assertEquals(listOf("tailscale", "serve", "--bg", "8740"), commands.last())
        val (settings, name) = paired.single()
        assertEquals("pixel", name)
        assertEquals(written["RAFIQ_DATA_DIR"], settings["RAFIQ_DATA_DIR"])
    }

    @Test
    fun the_summary_says_which_values_come_from_the_environment_and_which_from_the_file_and_hides_the_token() {
        ConfigFile.write(file, "RAFIQ_DATA_DIR=/srv/rafiq\nRAFIQ_AGENTS=claude-code=claude-agent-acp\n")
        val console = ScriptedConsole(emptyList())
        val token = "replace-with-a-long-random-value-0123456789"

        setup(
            console,
            environment =
                mapOf(
                    "HOME" to home.toString(),
                    "RAFIQ_PORT" to "9100",
                    "RAFIQ_DEVICE_TOKEN" to token,
                    "RAFIQ_ALLOWED_HOSTS" to "box.example.ts.net",
                ),
            bridgeRunning = { false },
        ).run()

        val summary = console.printed.substringAfter("Where each setting comes from")
        assertTrue(Regex("RAFIQ_PORT +9100 \\(environment\\)").containsMatchIn(summary), summary)
        assertTrue(Regex("RAFIQ_DATA_DIR +/srv/rafiq \\(bridge.conf\\)").containsMatchIn(summary), summary)
        assertTrue(Regex("RAFIQ_BIND_ADDRESS +127.0.0.1 \\(default\\)").containsMatchIn(summary), summary)
        assertFalse(token in console.printed)
        // Only the agent's default mode is missing, then tailscale serve and the service.
        assertEquals(3, console.questions.size, console.printed)
        assertEquals(0, servicesInstalled)
    }

    @Test
    fun a_settings_file_others_can_read_is_made_private_only_after_a_yes() {
        ConfigFile.write(file, "RAFIQ_PORT=9000\n")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"))

        assertEquals(1, setup(ScriptedConsole(listOf("n"))).run())
        assertEquals("rw-r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))

        setup(ScriptedConsole(listOf("y")), bridgeRunning = { false }).run()
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
    }

    @Test
    fun a_yes_to_the_service_installs_it_and_setup_pairs_once_the_bridge_answers() {
        ConfigFile.write(file, "RAFIQ_DATA_DIR=/srv/rafiq\nRAFIQ_AGENTS=claude-code=claude-agent-acp\n")
        // Default mode: no. tailscale serve: no. The service: yes. Then the phone's name.
        val console = ScriptedConsole(listOf("n", "n", "y", "pixel"))
        var checks = 0

        val exit =
            setup(
                console,
                environment = mapOf("HOME" to home.toString(), "RAFIQ_ALLOWED_HOSTS" to "box.example.ts.net"),
                bridgeRunning = { servicesInstalled > 0 && ++checks > 2 },
            ).run()

        assertEquals(0, exit, console.printed)
        assertEquals(1, servicesInstalled)
        assertEquals("pixel", paired.single().second)
    }

    @Test
    fun an_empty_answer_writes_the_safe_default_mode_but_leaves_other_settings_alone() {
        ConfigFile.write(file, "RAFIQ_AGENTS=claude-code=claude-agent-acp\n")
        // Enter on every settings question, then no to tailscale serve.
        val console = ScriptedConsole(listOf("", "", "", "n"))

        setup(console, bridgeRunning = { false }).run()

        val written = ConfigFile.read(file)
        assertEquals("claude-code=default", written["RAFIQ_DEFAULT_MODES"])
        assertEquals(null, written["RAFIQ_DATA_DIR"])
        assertEquals(null, written["RAFIQ_ALLOWED_HOSTS"])
    }

    @Test
    fun at_the_terminal_enter_takes_the_default_and_closed_input_is_always_no() {
        fun answer(
            input: String,
            default: Boolean,
        ) = StreamConsole(input.reader().buffered(), PrintStream(ByteArrayOutputStream())).confirm("Go?", default)

        assertTrue(answer("\n", default = true))
        assertFalse(answer("\n", default = false))
        assertFalse(answer("n\n", default = true))
        assertFalse(answer("", default = true))
    }
}
