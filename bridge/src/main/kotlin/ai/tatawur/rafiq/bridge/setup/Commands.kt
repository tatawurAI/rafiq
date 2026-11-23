package ai.tatawur.rafiq.bridge.setup

import ai.tatawur.rafiq.bridge.DeviceCli
import java.io.PrintStream

/**
 * The `rafiq-bridge` subcommands that run and exit instead of starting the bridge: `setup`, `service`, and the device
 * commands `pair`, `devices` and `revoke`.
 */
object Commands {
    /**
     * Runs the subcommand [args] names and returns its exit code, or null when [args] names none and the bridge
     * should start. Device commands get the merged settings, so they find a `RAFIQ_DATA_DIR` from `bridge.conf`.
     */
    fun run(
        args: List<String>,
        environment: Map<String, String>,
        out: PrintStream = System.out,
        err: PrintStream = System.err,
    ): Int? =
        when (args.firstOrNull()) {
            in DeviceCli.COMMANDS -> {
                settingsOrNull(environment, err)?.let { DeviceCli.run(args, it.values, out, err) } ?: FAILED
            }

            SetupCli.COMMAND -> {
                if (args.size != 1) {
                    err.println("Usage: rafiq-bridge setup")
                    USAGE_ERROR
                } else {
                    setupCli(environment, out, err).run()
                }
            }

            ServiceInstall.COMMAND -> {
                ServiceInstall.run(args, environment, out, err, ProcessCommandRunner(), ServiceInstall.locateProgram())
            }

            else -> {
                null
            }
        }

    private fun setupCli(
        environment: Map<String, String>,
        out: PrintStream,
        err: PrintStream,
    ): SetupCli {
        val runner = ProcessCommandRunner()
        val program = ServiceInstall.locateProgram()
        val platform = ServiceInstall.platformFor(System.getProperty("os.name"))
        return SetupCli(
            environment = environment,
            console = StreamConsole(System.`in`.bufferedReader(), out),
            programs = ProgramFinder.onPath(environment["PATH"]),
            runner = runner,
            pair = { settings, name -> DeviceCli.run(listOf("pair", "--name", name), settings, out, err) },
            installService =
                if (program != null && platform != null) {
                    { _ -> ServiceInstall.run(listOf("service", "install"), environment, out, err, runner, program) }
                } else {
                    null
                },
        )
    }

    private fun settingsOrNull(
        environment: Map<String, String>,
        err: PrintStream,
    ): LoadedSettings? =
        try {
            LoadedSettings.load(environment)
        } catch (failure: IllegalArgumentException) {
            err.println(failure.message)
            null
        }

    private const val FAILED = 1
    private const val USAGE_ERROR = 2
}
