package ai.tatawur.rafiq.bridge.setup

import java.io.BufferedReader
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The answer of a program that [CommandRunner] ran. */
data class CommandResult(
    val exitCode: Int,
    val output: String,
) {
    /** True when the program exited with 0. */
    val ok: Boolean get() = exitCode == 0
}

/**
 * Runs other programs for `setup` and `service`. Tests pass a fake that records the commands, so no test ever starts
 * `tailscale`, `launchctl` or `systemctl`.
 */
fun interface CommandRunner {
    /** Runs [command] and waits for it. A program that cannot start gives a non-zero exit code, not an exception. */
    fun run(command: List<String>): CommandResult
}

/** Runs programs for real, with their output and errors combined, and gives up on one after [timeout]. */
class ProcessCommandRunner(
    private val timeout: Duration = 30.seconds,
) : CommandRunner {
    override fun run(command: List<String>): CommandResult =
        try {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            process.outputStream.close()
            val output = process.inputStream.bufferedReader().use(BufferedReader::readText)
            if (process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
                CommandResult(process.exitValue(), output)
            } else {
                process.destroyForcibly()
                CommandResult(TIMED_OUT, "${command.first()} did not finish within $timeout.")
            }
        } catch (failure: IOException) {
            CommandResult(NOT_STARTED, failure.message.orEmpty())
        }

    private companion object {
        const val NOT_STARTED = 127
        const val TIMED_OUT = 124
    }
}

/** Finds a program on `PATH`, the way a shell would. */
fun interface ProgramFinder {
    /** The full path of [program], or null when no folder on `PATH` has it. */
    fun find(program: String): Path?

    companion object {
        /** Searches the folders in [path], a `PATH` value, for an executable file. */
        fun onPath(path: String?): ProgramFinder =
            ProgramFinder { program ->
                path
                    .orEmpty()
                    .split(java.io.File.pathSeparatorChar)
                    .filter(String::isNotEmpty)
                    .map { Path.of(it).resolve(program) }
                    .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
            }
    }
}

/**
 * Where `setup` asks its questions. Every change it makes goes through [confirm] first.
 */
interface SetupConsole {
    /** Where `setup` prints. */
    val out: PrintStream

    /** Asks a yes or no [question]. Anything but `y` or `yes`, including the end of input, is no. */
    fun confirm(question: String): Boolean

    /** Asks for a line of text, giving [default] when the answer is empty or input has ended. */
    fun ask(
        question: String,
        default: String,
    ): String
}

/** A [SetupConsole] on a reader and a print stream, normally the terminal. */
class StreamConsole(
    private val input: BufferedReader,
    override val out: PrintStream,
) : SetupConsole {
    override fun confirm(question: String): Boolean {
        out.print("$question [y/N] ")
        out.flush()
        return input.readLine()?.trim()?.lowercase() in YES
    }

    override fun ask(
        question: String,
        default: String,
    ): String {
        out.print("$question [$default] ")
        out.flush()
        return input.readLine()?.trim()?.takeIf { it.isNotEmpty() } ?: default
    }

    private companion object {
        val YES = setOf("y", "yes")
    }
}
