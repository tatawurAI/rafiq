@file:Suppress("MatchingDeclarationName") // The file is about running processes. The result type is a detail.

package ai.tatawur.rafiq.bridge.tmux

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** What a finished process printed. */
internal class ProcessResult(
    val exit: Int,
    val out: String,
    val err: String,
)

private val killer =
    Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "process-watchdog").apply { isDaemon = true }
    }

/**
 * `TMUX` would point tmux at another server. Every `RAFIQ_` variable, the device token among them, stays out
 * of the processes the bridge starts for tmux and for the agent list: they have no use for it.
 */
internal fun cleanEnvironment(builder: ProcessBuilder) {
    builder.environment().keys.removeIf { it == "TMUX" || it.startsWith("RAFIQ_") }
}

/**
 * The names of the `RAFIQ_` variables in the global environment of [server], which every new pane inherits.
 * The bridge keeps them out of every tmux process it starts, but a server started from a shell that exported
 * one already has it. Names only: the values are never read out. Empty when there is no server or none is set.
 */
internal fun inheritedRafiqVariables(server: TmuxServer): List<String> {
    val result = runCapturing(server.commandLine("show-environment", "-g"), SHOW_ENVIRONMENT_TIMEOUT_SECONDS)
    if (result == null || result.exit != 0) return emptyList()
    // A line is `NAME=value`, or `-NAME` for a variable tmux removes from new panes.
    return result.out
        .lineSequence()
        .map { it.substringBefore('=') }
        .filter { it.startsWith("RAFIQ_") }
        .distinct()
        .toList()
}

private const val SHOW_ENVIRONMENT_TIMEOUT_SECONDS = 5L

/**
 * Runs [command] to the end and returns what it printed. Blocks the caller.
 *
 * A process that outlives [timeoutSeconds] is killed together with its children, which also ends the
 * reads, so a hung program cannot hold the caller. Output and errors are read at the same time, so neither
 * can fill its pipe while the other is waited for.
 *
 * @return null when the program could not be started or had to be killed.
 */
@Suppress("TooGenericExceptionCaught") // A program that cannot start is the same answer as one that fails.
internal fun runCapturing(
    command: List<String>,
    timeoutSeconds: Long,
): ProcessResult? {
    val process =
        try {
            ProcessBuilder(command).also(::cleanEnvironment).start()
        } catch (_: Exception) {
            return null
        }
    process.outputStream.close()
    val timedOut = AtomicBoolean(false)
    val watchdog =
        killer.schedule(
            {
                timedOut.set(true)
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
            },
            timeoutSeconds,
            TimeUnit.SECONDS,
        )
    var err = ByteArray(0)
    val errReader = Thread { err = runCatching { process.errorStream.readBytes() }.getOrDefault(ByteArray(0)) }
    errReader.isDaemon = true
    errReader.start()
    try {
        val out = runCatching { process.inputStream.readBytes() }.getOrDefault(ByteArray(0))
        errReader.join()
        val exit = process.waitFor()
        return if (timedOut.get()) {
            null
        } else {
            ProcessResult(
                exit,
                out.toString(Charsets.UTF_8),
                err.toString(Charsets.UTF_8),
            )
        }
    } finally {
        watchdog.cancel(false)
    }
}
