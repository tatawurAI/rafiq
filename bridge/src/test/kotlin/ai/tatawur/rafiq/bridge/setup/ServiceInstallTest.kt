package ai.tatawur.rafiq.bridge.setup

import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Every test writes into a temporary HOME, and the runner is a fake, so no service is ever loaded. */
class ServiceInstallTest {
    @TempDir
    lateinit var home: Path

    private val commands = mutableListOf<List<String>>()
    private val runner =
        CommandRunner { command ->
            commands += command
            CommandResult(0, if (command == listOf("id", "-u")) "501\n" else "")
        }
    private val out = ByteArrayOutputStream()

    private fun service(
        platform: ServiceInstall.Platform,
        environment: Map<String, String> = emptyMap(),
    ) = ServiceInstall(
        platform,
        home,
        mapOf("HOME" to home.toString(), "PATH" to "/opt/homebrew/bin:/usr/bin") + environment,
        runner,
        PrintStream(out),
        PrintStream(ByteArrayOutputStream()),
    )

    @Test
    fun the_launchd_agent_starts_the_installed_bridge_with_your_path_and_no_rafiq_settings() {
        val program = Path.of("/opt/rafiq & co/bin/rafiq-bridge")
        val shellOnly = mapOf("RAFIQ_DEVICE_TOKEN" to "replace-with-a-long-random-value")
        val install = service(ServiceInstall.Platform.MACOS, shellOnly)

        assertEquals(0, install.install(program))

        val plist = home.resolve("Library/LaunchAgents/ai.tatawur.rafiq-bridge.plist")
        assertEquals(plist, install.unitPath)
        val values = plistValues(plist)
        assertEquals("ai.tatawur.rafiq-bridge", values["Label"])
        assertEquals(program.toString(), values["ProgramArguments"])
        assertEquals("/opt/homebrew/bin:/usr/bin", values["PATH"])
        assertFalse("RAFIQ_DEVICE_TOKEN" in Files.readString(plist))
        assertEquals(listOf("launchctl", "bootstrap", "gui/501", plist.toString()), commands.last())
        // A setting only your shell has would vanish under launchd, so the install names it. By name only.
        assertTrue("RAFIQ_DEVICE_TOKEN" in out.toString() && "replace-with" !in out.toString())
    }

    @Test
    fun the_systemd_unit_quotes_a_path_with_spaces_and_specifiers_and_uninstall_removes_it() {
        val install = service(ServiceInstall.Platform.LINUX)

        install.install(Path.of("/home/me/rafiq 100%/bin/rafiq-bridge"))

        val unit = home.resolve(".config/systemd/user/rafiq-bridge.service")
        val text = Files.readString(unit)
        assertTrue("ExecStart=\"/home/me/rafiq 100%%/bin/rafiq-bridge\"\n" in text, text)
        assertTrue("Environment=\"PATH=/opt/homebrew/bin:/usr/bin\"\n" in text, text)
        assertEquals(listOf("systemctl", "--user", "enable", "--now", "rafiq-bridge.service"), commands.last())

        assertEquals(0, install.uninstall())
        assertFalse(Files.exists(unit))
        assertTrue(listOf("systemctl", "--user", "disable", "--now", "rafiq-bridge.service") in commands)
    }

    @Test
    fun install_without_an_installed_bridge_or_on_another_system_refuses_and_runs_nothing() {
        val environment = mapOf("HOME" to home.toString())
        val err = PrintStream(ByteArrayOutputStream())

        fun run(
            action: String,
            program: Path?,
            osName: String,
        ) = ServiceInstall.run(listOf("service", action), environment, PrintStream(out), err, runner, program, osName)
        val program = Path.of("/opt/rafiq/bin/rafiq-bridge")

        assertEquals(1, run("install", null, "Linux"))
        assertEquals(1, run("install", program, "Windows 11"))
        assertEquals(2, run("start", program, "Linux"))
        assertTrue(commands.isEmpty())
    }

    /** The plist's string values by key, with the program arguments joined and the environment flattened in. */
    private fun plistValues(plist: Path): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance()
        // The DOCTYPE names Apple's DTD. The test must not fetch it.
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        val document = factory.newDocumentBuilder().parse(plist.toFile())
        val values = mutableMapOf<String, String>()
        val keys = document.getElementsByTagName("key")
        for (index in 0 until keys.length) {
            val key = keys.item(index) as Element
            var next = key.nextSibling
            while (next != null && next !is Element) next = next.nextSibling
            val element = next ?: continue
            values[key.textContent] =
                when (element.tagName) {
                    "array" -> {
                        val strings = element.getElementsByTagName("string")
                        (0 until strings.length).joinToString(" ") { strings.item(it).textContent }
                    }

                    else -> {
                        element.textContent
                    }
                }
        }
        return values
    }

    @Test
    fun a_homebrew_cellar_program_is_written_with_the_opt_path_that_survives_upgrades() {
        assertEquals(
            Path.of("/opt/homebrew/opt/rafiq-bridge/libexec/bin/rafiq-bridge"),
            ServiceInstall.stablePath(Path.of("/opt/homebrew/Cellar/rafiq-bridge/0.3.0/libexec/bin/rafiq-bridge")),
        )
        assertEquals(null, ServiceInstall.stablePath(Path.of("/opt/rafiq/bin/rafiq-bridge")))
    }
}
