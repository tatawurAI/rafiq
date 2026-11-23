package ai.tatawur.rafiq.bridge.setup

import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * `rafiq-bridge service install|uninstall`: runs the bridge as a launchd agent on macOS or a systemd user unit on
 * Linux, so it starts when you log in and comes back after a crash or a reboot (ADR 0014).
 *
 * The service gets no `RAFIQ_` variables. It reads its settings from `bridge.conf`, the same file `setup` writes. It
 * does get the `PATH` you install it from, because launchd and systemd start services with a bare `PATH` on which
 * `claude-agent-acp`, `node` and `tmux` are usually missing, and `JAVA_HOME` and `XDG_CONFIG_HOME` when you have them.
 *
 * @param home your home folder. Tests pass a temporary one.
 * @param environment the environment the command runs in.
 * @param runner runs `launchctl`, `systemctl` and `id`. Tests pass a fake, so they never load a service.
 */
class ServiceInstall(
    private val platform: Platform,
    private val home: Path,
    private val environment: Map<String, String>,
    private val runner: CommandRunner,
    private val out: PrintStream,
    private val err: PrintStream,
) {
    /** The init systems the bridge can register with. */
    enum class Platform { MACOS, LINUX }

    /** The service definition's path: the launchd plist or the systemd unit. */
    val unitPath: Path
        get() =
            when (platform) {
                Platform.MACOS -> {
                    home.resolve("Library/LaunchAgents/$LABEL.plist")
                }

                Platform.LINUX -> {
                    val xdg = environment["XDG_CONFIG_HOME"]?.takeIf { it.isNotBlank() }?.let(Path::of)
                    (xdg?.takeIf { it.isAbsolute } ?: home.resolve(".config")).resolve("systemd/user/$UNIT")
                }
            }

    /** Writes the service definition for [program] and starts it. Returns the exit code for the command. */
    fun install(program: Path): Int {
        val serviceEnvironment =
            SERVICE_VARIABLES.associateWith { environment[it].orEmpty() }.filterValues(String::isNotBlank)
        val text =
            when (platform) {
                Platform.MACOS -> launchdPlist(program, serviceEnvironment, logFile)
                Platform.LINUX -> systemdUnit(program, serviceEnvironment)
            }
        Files.createDirectories(unitPath.parent)
        Files.writeString(unitPath, text)
        out.println("Wrote $unitPath, which starts $program.")
        warnAboutShellOnlySettings()
        val started = startCommands()?.let(::runAll) ?: false
        if (started) printStarted()
        return if (started) 0 else FAILED
    }

    /** Stops the service and deletes its definition. Returns the exit code for the command. */
    fun uninstall(): Int {
        if (!Files.exists(unitPath)) {
            out.println("No service is installed at $unitPath.")
            return 0
        }
        val stop =
            when (platform) {
                Platform.MACOS -> userId()?.let { listOf(listOf("launchctl", "bootout", "gui/$it/$LABEL")) }
                Platform.LINUX -> listOf(listOf("systemctl", "--user", "disable", "--now", UNIT))
            }
        if (stop == null || !runAll(stop)) {
            err.println(
                "Could not stop the service. Removing $unitPath anyway, so it does not start at your next log in.",
            )
        }
        Files.delete(unitPath)
        if (platform == Platform.LINUX) runner.run(listOf("systemctl", "--user", "daemon-reload"))
        out.println("Removed the service. Your settings in bridge.conf and the data folder are untouched.")
        return 0
    }

    private val logFile: Path get() = home.resolve("Library/Logs/rafiq-bridge.log")

    /** The commands that load and start the service, or null when they cannot be built. */
    private fun startCommands(): List<List<String>>? =
        when (platform) {
            Platform.MACOS -> {
                userId()?.let { uid ->
                    // Unloading a previous install first makes a reinstall pick up the new file. It fails harmlessly
                    // when nothing is loaded, so its result is not checked.
                    runner.run(listOf("launchctl", "bootout", "gui/$uid/$LABEL"))
                    listOf(listOf("launchctl", "bootstrap", "gui/$uid", unitPath.toString()))
                }
            }

            Platform.LINUX -> {
                listOf(
                    listOf("systemctl", "--user", "daemon-reload"),
                    listOf("systemctl", "--user", "enable", "--now", UNIT),
                )
            }
        }

    private fun printStarted() {
        out.println("The bridge is running as a service and starts again when you log in.")
        when (platform) {
            Platform.MACOS -> {
                out.println("Its log is $logFile.")
            }

            Platform.LINUX -> {
                out.println("Read its log with: journalctl --user -u $UNIT")
                out.println(
                    "To keep it running while you are logged out, and start it at boot, run once: " +
                        "sudo loginctl enable-linger \$USER",
                )
            }
        }
    }

    private fun runAll(steps: List<List<String>>): Boolean =
        steps.all { command ->
            val result = runner.run(command)
            if (!result.ok) {
                err.println("${command.joinToString(" ")} failed (exit ${result.exitCode}): ${result.output.trim()}")
            }
            result.ok
        }

    private fun userId(): String? {
        val result = runner.run(listOf("id", "-u"))
        val id = result.output.trim().takeIf { result.ok && it.all(Char::isDigit) && it.isNotEmpty() }
        if (id == null) err.println("Could not read your user id with id -u.")
        return id
    }

    /** The service sees only the file, so a setting that lives only in your shell would silently disappear. */
    private fun warnAboutShellOnlySettings() {
        val inFile = ConfigFile.locate(environment)?.let { runCatching { ConfigFile.read(it) }.getOrNull() }.orEmpty()
        val shellOnly = environment.keys.filter { it.startsWith("RAFIQ_") && it !in inFile }.sorted()
        if (shellOnly.isNotEmpty()) {
            out.println(
                "The service reads its settings from bridge.conf only. These are set in your shell but not in the " +
                    "file, so the service will not see them: ${shellOnly.joinToString(", ")}. " +
                    "Add them with rafiq-bridge setup or by editing the file.",
            )
        }
    }

    companion object {
        /** The first argument that means "manage the service". */
        const val COMMAND = "service"

        /** The launchd label, also the plist's file name. */
        const val LABEL = "ai.tatawur.rafiq-bridge"

        /** The systemd user unit's name. */
        const val UNIT = "rafiq-bridge.service"

        private const val FAILED = 1
        private const val USAGE_ERROR = 2
        private const val USAGE = "Usage: rafiq-bridge service install | rafiq-bridge service uninstall"
        private val SERVICE_VARIABLES = listOf("PATH", "JAVA_HOME", "XDG_CONFIG_HOME")
        private const val APPLE_DTD = "http://www.apple.com/DTDs/PropertyList-1.0.dtd"

        /** The platform for an `os.name` value, or null for one with neither launchd nor systemd. */
        fun platformFor(osName: String): Platform? =
            when {
                osName.startsWith("Mac", ignoreCase = true) -> Platform.MACOS
                osName.startsWith("Linux", ignoreCase = true) -> Platform.LINUX
                else -> null
            }

        /**
         * Runs `service install` or `service uninstall`. [program] is the installed start script, or null when the
         * bridge is not running from an install.
         */
        @Suppress("LongParameterList") // The command's whole world, passed in so tests can replace each part.
        fun run(
            args: List<String>,
            environment: Map<String, String>,
            out: PrintStream,
            err: PrintStream,
            runner: CommandRunner,
            program: Path?,
            osName: String = System.getProperty("os.name"),
        ): Int {
            val action = args.getOrNull(1)?.takeIf { args.size == 2 && it in setOf("install", "uninstall") }
            val platform = platformFor(osName)
            val home = environment["HOME"]?.takeIf { it.isNotBlank() }?.let(Path::of)
            return when {
                action == null -> {
                    err.println(USAGE)
                    USAGE_ERROR
                }

                platform == null || home == null -> {
                    err.println("A service can be installed on macOS (launchd) or Linux (systemd), with HOME set.")
                    FAILED
                }

                action == "uninstall" -> {
                    ServiceInstall(platform, home, environment, runner, out, err).uninstall()
                }

                program == null -> {
                    err.println(
                        "Run this from an installed bridge, so the service can start its bin/rafiq-bridge. " +
                            "See docs/install.md.",
                    )
                    FAILED
                }

                else -> {
                    ServiceInstall(platform, home, environment, runner, out, err).install(program)
                }
            }
        }

        /**
         * The installed start script, `bin/rafiq-bridge`, found by walking up from the bridge's own jar. Null when the
         * bridge runs from a build folder instead.
         */
        fun locateProgram(): Path? {
            val code =
                runCatching {
                    Path.of(
                        ServiceInstall::class.java.protectionDomain.codeSource.location
                            .toURI(),
                    )
                }.getOrNull()
            return generateSequence(code) { it.parent }
                .map { it.resolve("bin").resolve("rafiq-bridge") }
                .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
                ?.toAbsolutePath()
                ?.normalize()
        }

        /** The launchd agent: starts [program] at login, restarts it if it exits, and logs to [logFile]. */
        fun launchdPlist(
            program: Path,
            environment: Map<String, String>,
            logFile: Path,
        ): String =
            buildString {
                appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
                appendLine("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"$APPLE_DTD\">")
                appendLine("""<plist version="1.0">""")
                appendLine("<dict>")
                appendLine("  <key>Label</key>")
                appendLine("  <string>$LABEL</string>")
                appendLine("  <key>ProgramArguments</key>")
                appendLine("  <array>")
                appendLine("    <string>${xml(program.toString())}</string>")
                appendLine("  </array>")
                if (environment.isNotEmpty()) {
                    appendLine("  <key>EnvironmentVariables</key>")
                    appendLine("  <dict>")
                    environment.forEach { (name, value) ->
                        appendLine("    <key>${xml(name)}</key>")
                        appendLine("    <string>${xml(value)}</string>")
                    }
                    appendLine("  </dict>")
                }
                appendLine("  <key>RunAtLoad</key>")
                appendLine("  <true/>")
                appendLine("  <key>KeepAlive</key>")
                appendLine("  <true/>")
                appendLine("  <key>StandardOutPath</key>")
                appendLine("  <string>${xml(logFile.toString())}</string>")
                appendLine("  <key>StandardErrorPath</key>")
                appendLine("  <string>${xml(logFile.toString())}</string>")
                appendLine("</dict>")
                appendLine("</plist>")
            }

        /** The systemd user unit: starts [program] with the user's session and restarts it if it fails. */
        fun systemdUnit(
            program: Path,
            environment: Map<String, String>,
        ): String =
            buildString {
                appendLine("[Unit]")
                appendLine("Description=Rafiq bridge")
                appendLine()
                appendLine("[Service]")
                appendLine("ExecStart=${systemdQuote(program.toString(), dollar = true)}")
                environment.forEach { (name, value) ->
                    appendLine("Environment=${systemdQuote("$name=$value", dollar = false)}")
                }
                appendLine("Restart=on-failure")
                appendLine("RestartSec=5")
                appendLine()
                appendLine("[Install]")
                appendLine("WantedBy=default.target")
            }

        private fun xml(value: String): String =
            value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")

        /**
         * One double-quoted systemd word. `%` starts a specifier everywhere in a unit, and `$` starts a variable in
         * `ExecStart=` only, so both are doubled where they would be read that way.
         */
        private fun systemdQuote(
            value: String,
            dollar: Boolean,
        ): String {
            require('\n' !in value && '\r' !in value) { "A service setting cannot contain a line break." }
            val escaped =
                value
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("%", "%%")
                    .let { if (dollar) it.replace("$", "$$") else it }
            return "\"$escaped\""
        }
    }
}
