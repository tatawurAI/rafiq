package ai.tatawur.rafiq.bridge.setup

import ai.tatawur.rafiq.bridge.BridgeConfig
import ai.tatawur.rafiq.bridge.server.DeviceAdminServer
import java.nio.file.Files
import java.nio.file.Path

/**
 * `rafiq-bridge setup`: the first-run command (ADR 0014). It finds your server's `*.ts.net` name, checks that Claude
 * Code and its ACP adapter are installed, writes `bridge.conf`, offers to publish the bridge with `tailscale serve`
 * and to install it as a service, prints where every setting came from, and ends with a pairing QR code.
 *
 * It asks before every change and before running anything that changes your system. The only programs it runs
 * without asking are read-only: `tailscale status --json` and the lookups on your `PATH`. It never signs you in to
 * anything and never writes a device token: each phone gets its own token when it pairs.
 *
 * @param environment the environment the command runs in.
 * @param programs finds `tailscale`, `claude` and `claude-agent-acp` on your `PATH`.
 * @param runner runs `tailscale`. Tests pass a fake that only records the command.
 * @param pair pairs a phone through the running bridge and prints the code and QR, given the merged settings and a
 *   device name. Returns the exit code.
 * @param installService installs and starts the bridge as a service, or null where that is not possible.
 * @param bridgeRunning true when the bridge's admin socket exists in the given data folder.
 * @param pause waits a moment between checks for a bridge that is still starting.
 */
@Suppress("LongParameterList") // Each part of the outside world is passed in, so tests replace every one.
class SetupCli(
    private val environment: Map<String, String>,
    private val console: SetupConsole,
    private val programs: ProgramFinder,
    private val runner: CommandRunner,
    private val pair: (settings: Map<String, String>, name: String) -> Int,
    private val installService: ((settings: Map<String, String>) -> Int)?,
    private val bridgeRunning: (dataDir: Path) -> Boolean = { Files.exists(DeviceAdminServer.socketPathFor(it)) },
    private val pause: () -> Unit = { Thread.sleep(WAIT_STEP_MS) },
) {
    private val out = console.out

    /** Runs the whole setup. Returns 0 when it got to the end, 1 when something stopped it. */
    fun run(): Int =
        try {
            runSteps()
        } catch (stop: Stop) {
            out.println(stop.message)
            FAILED
        }

    private fun runSteps(): Int {
        out.println("Rafiq bridge setup. Nothing on your server changes unless you answer yes.")
        val file =
            ConfigFile.locate(environment)
                ?: throw Stop("Neither HOME nor XDG_CONFIG_HOME is set, so there is no place for bridge.conf.")
        out.println("Settings file: $file")
        makeUsable(file)
        val fileText = if (Files.exists(file)) Files.readString(file) else ""
        val loaded = load()

        out.println()
        val agentFound = checkAgent()
        out.println()
        val tailscale = checkTailscale()
        out.println()
        val fileHosts = runCatching { ConfigFile.read(file)[ALLOWED_HOSTS] }.getOrNull()
        val changes =
            askForChanges(proposedSettings(environment, loaded, agentFound, tailscale?.dnsName, fileHosts), loaded)
        if (changes.isNotEmpty()) {
            ConfigFile.write(file, ConfigFile.update(fileText, changes))
            out.println("Wrote $file. Only you can read it.")
        }
        val settings = load()
        if (tailscale?.running == true) offerTailscaleServe(settings)
        out.println()
        out.println("Where each setting comes from")
        originLines(settings).forEach { out.println("  $it") }
        runCatching { settings.config() }.exceptionOrNull()?.let { problem ->
            throw Stop("\nThe bridge would not start with these settings: ${problem.message}")
        }
        out.println()
        return pairPhone(settings, restartNeeded = changes.isNotEmpty())
    }

    /** An existing file must be private before setup reads or changes it. Offers the fix, and stops without it. */
    private fun makeUsable(file: Path) {
        if (!Files.exists(file) || !ConfigFile.isOpenToOthers(file)) return
        out.println(runCatching { ConfigFile.checkPrivate(file) }.exceptionOrNull()?.message)
        if (!console.confirm("Make $file private, readable and writable by you only (chmod 600)?")) {
            throw Stop("The bridge will not read the file until it is private.")
        }
        ConfigFile.makePrivate(file)
        // chmod cannot fix a file that belongs to someone else or a folder others can write to.
        runCatching { ConfigFile.checkPrivate(file) }.exceptionOrNull()?.let { throw Stop(it.message.orEmpty()) }
    }

    private fun load(): LoadedSettings =
        try {
            LoadedSettings.load(environment)
        } catch (failure: IllegalArgumentException) {
            throw Stop(failure.message.orEmpty(), failure)
        }

    /** Checks for Claude Code and its ACP adapter. Returns true when the adapter is on `PATH`. */
    private fun checkAgent(): Boolean {
        out.println("Claude Code")
        val claude = programs.find("claude")
        val adapter = programs.find(ADAPTER)
        out.println(
            claude?.let { "  Found claude at $it. Make sure it is signed in: run claude once and follow its prompts." }
                ?: "  claude is not on your PATH. Install Claude Code and sign in, then run setup again.",
        )
        out.println(
            adapter?.let { "  Found $ADAPTER at $it." }
                ?: "  $ADAPTER is not on your PATH. Install it with: npm install -g $ADAPTER_PACKAGE",
        )
        return adapter != null
    }

    /** Reads this machine's Tailscale state, or returns null when Tailscale is not installed. */
    private fun checkTailscale(): TailscaleStatus? {
        out.println("Tailscale")
        val status =
            programs.find("tailscale")?.let {
                TailscaleStatus.parse(runner.run(listOf("tailscale", "status", "--json")).output)
            }
        out.println(
            when {
                status == null -> "  tailscale is not on your PATH. Install Tailscale, sign in, and run setup again."
                !status.running -> "  Tailscale is not connected. Run: tailscale up"
                status.dnsName == null -> "  This machine has no ts.net name. Turn on MagicDNS in Tailscale."
                else -> "  This machine is ${status.dnsName}."
            },
        )
        return status
    }

    /** Asks about each of [proposals] and returns the ones you said yes to. */
    private fun askForChanges(
        proposals: List<ProposedSetting>,
        loaded: LoadedSettings,
    ): Map<String, String> {
        if (proposals.isEmpty()) {
            out.println("bridge.conf already has everything setup would add.")
            return emptyMap()
        }
        out.println("Settings for bridge.conf")
        val accepted = mutableSetOf<String>()
        return proposals
            .filter { proposal ->
                if (proposal.needs != null && proposal.needs !in accepted) return@filter false
                out.println("  ${proposal.reason}")
                if (loaded.origins[proposal.name] == SettingOrigin.ENVIRONMENT) {
                    out.println("  Your environment sets ${proposal.name}, which wins over the file while it is set.")
                }
                console
                    .confirm(
                        "  Write ${proposal.name}=${proposal.value} to bridge.conf?",
                        proposal.defaultYes,
                    ).also { yes ->
                        if (yes) accepted += proposal.name
                    }
            }.associate { it.name to it.value }
    }

    private fun offerTailscaleServe(settings: LoadedSettings) {
        val port = settings.values["RAFIQ_PORT"]?.trim()?.toIntOrNull() ?: BridgeConfig.DEFAULT_PORT
        val command = listOf("tailscale", "serve", "--bg", port.toString())
        out.println()
        out.println("Publish the bridge to your tailnet over HTTPS with:")
        out.println("  ${command.joinToString(" ")}")
        if (!console.confirm("Run it now?")) {
            out.println("Run it yourself when you are ready. It stays in place across restarts.")
            return
        }
        val result = runner.run(command)
        if (result.ok) {
            out.println("tailscale serve is on.")
        } else {
            out.println("tailscale serve failed: ${result.output.trim()}")
            out.println("On Linux, run it with sudo, or allow your user once with:")
            out.println("  sudo tailscale set --operator=\$USER")
        }
    }

    /** Ends setup with a pairing code and QR, starting the bridge as a service first if you agree. */
    private fun pairPhone(
        settings: LoadedSettings,
        restartNeeded: Boolean,
    ): Int {
        val dataDir =
            settings.values[DATA_DIR]?.takeIf { it.isNotBlank() }?.let(Path::of)
                ?: throw Stop("Pairing needs $DATA_DIR. Run setup again and let it set one.")
        val alreadyRunning = bridgeRunning(dataDir)
        if (alreadyRunning && restartNeeded) {
            out.println("The bridge is already running. Restart it so it reads the new settings, then pair.")
        }
        val running = alreadyRunning || startAsService(settings, dataDir)
        if (!running) {
            out.println("Start the bridge with rafiq-bridge, then pair your phone with:")
            out.println("  rafiq-bridge pair --name <phone>")
        }
        return if (running) {
            val name = console.ask("Name for your phone, as rafiq-bridge devices will list it:", DEFAULT_DEVICE_NAME)
            pair(settings.values, name)
        } else {
            0
        }
    }

    /** Offers to install the service, and on a yes installs it and waits for the bridge to answer. */
    private fun startAsService(
        settings: LoadedSettings,
        dataDir: Path,
    ): Boolean {
        val installed =
            installService != null &&
                console.confirm("Install the bridge as a service that starts when you log in, and start it now?") &&
                installService.invoke(settings.values) == 0
        // A new service takes a moment to open its admin socket.
        val started =
            installed &&
                (1..WAIT_STEPS).any { attempt ->
                    if (attempt > 1) pause()
                    bridgeRunning(dataDir)
                }
        if (installed && !started) out.println("The bridge did not start within a few seconds. Check its log.")
        return started
    }

    /** Ends the run with [message], which says what to do. */
    private class Stop(
        message: String,
        cause: Throwable? = null,
    ) : Exception(message, cause)

    companion object {
        /** The first argument that means "run setup". */
        const val COMMAND = "setup"

        private const val FAILED = 1
        private const val DEFAULT_DEVICE_NAME = "phone"
        private const val WAIT_STEPS = 20
        private const val WAIT_STEP_MS = 500L
    }
}
