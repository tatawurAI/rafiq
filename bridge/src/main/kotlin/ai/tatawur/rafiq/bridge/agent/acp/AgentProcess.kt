package ai.tatawur.rafiq.bridge.agent.acp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.Reader
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration

/**
 * An agent's ACP adapter running as a child process, with its stdio as newline-delimited JSON.
 *
 * Stopping is graceful first: closing stdin is how an ACP agent learns the client has gone, and most
 * exit on it. Whatever is still running after [stopGracePeriod] is killed, children first, so a
 * launcher such as `npx` cannot leave its own child behind. Waiting on the exit reaps the process,
 * so nothing is left as a zombie.
 */
internal class AgentProcess private constructor(
    private val process: Process,
    private val ioDispatcher: CoroutineDispatcher,
    private val stopGracePeriod: Duration,
    private val maxLineChars: Int,
) {
    /** One line of the agent's stdout. */
    sealed interface Line {
        data class Text(
            val text: String,
        ) : Line

        /** A line longer than the limit. Its text is dropped as it is read, so it never fills memory. */
        data object TooLong : Line
    }

    /** Lines the agent writes to stdout. The flow ends when the agent closes stdout or exits. */
    val lines: Flow<Line> =
        flow {
            val reader = process.inputStream.bufferedReader()
            while (true) {
                val line = readLine(reader) ?: break
                emit(line)
            }
        }.flowOn(ioDispatcher)

    private val stdin = process.outputStream.bufferedWriter()

    /** Reads one line, or returns null at the end of the stream. Keeps at most [maxLineChars] of it. */
    private fun readLine(reader: Reader): Line? {
        val text = StringBuilder()
        var tooLong = false
        var char = reader.read()
        if (char == -1) return null
        while (char != -1 && char != '\n'.code) {
            if (!tooLong) text.append(char.toChar())
            if (text.length > maxLineChars) {
                tooLong = true
                text.setLength(0)
            }
            char = reader.read()
        }
        return if (tooLong) Line.TooLong else Line.Text(text.toString())
    }

    /** Writes one JSON-RPC message as a line. */
    suspend fun write(line: String) {
        withContext(ioDispatcher) {
            synchronized(stdin) {
                stdin.write(line)
                stdin.newLine()
                stdin.flush()
            }
        }
    }

    /** Suspends until the process has exited, and returns its exit code. */
    suspend fun awaitExit(): Int = process.onExit().await().exitValue()

    /** Logs the agent's stderr at debug level, until the process closes it. */
    fun drainStderr(scope: CoroutineScope) {
        scope.launch(ioDispatcher) {
            process.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { logger.debug("agent stderr: {}", it) }
            }
        }
    }

    /** Stops the process and waits until it is gone. Safe to call more than once. */
    suspend fun stop() {
        // A write blocked on a full pipe holds the writer's lock, and closing needs it. So the close
        // runs on its own and gets the grace period. Killing the process unblocks a stuck write.
        val closing = CompletableFuture.runAsync({ closeQuietly() }, ioDispatcher.asExecutor())
        withTimeoutOrNull(stopGracePeriod) { closing.await() }
        if (withTimeoutOrNull(stopGracePeriod) { awaitExit() } != null) return
        val children = process.descendants().toList()
        children.forEach { it.destroy() }
        process.destroy()
        if (withTimeoutOrNull(stopGracePeriod) { awaitExit() } != null) return
        children.forEach { it.destroyForcibly() }
        process.destroyForcibly()
        awaitExit()
    }

    private fun closeQuietly() {
        try {
            stdin.close()
        } catch (e: IOException) {
            // The agent already closed its end. Nothing is left to flush.
            logger.debug("closing agent stdin: {}", e.message)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AgentProcess::class.java)

        /**
         * Starts [command] in [workingDirectory] with [environment], minus every `RAFIQ_*` variable.
         * Those hold the bridge's own settings, among them the device token, and the agent's shell
         * tools must never see them.
         *
         * @throws IOException when the command cannot be started, for example because it is not installed.
         */
        fun start(
            command: List<String>,
            workingDirectory: Path,
            environment: Map<String, String>,
            ioDispatcher: CoroutineDispatcher,
            stopGracePeriod: Duration,
            maxLineChars: Int,
        ): AgentProcess {
            val builder = ProcessBuilder(command).directory(workingDirectory.toFile())
            builder.environment().clear()
            builder.environment().putAll(agentEnvironment(environment))
            return AgentProcess(builder.start(), ioDispatcher, stopGracePeriod, maxLineChars)
        }
    }
}

/** [environment] without the bridge's own `RAFIQ_*` settings. */
internal fun agentEnvironment(environment: Map<String, String>): Map<String, String> =
    environment.filterKeys { !it.startsWith("RAFIQ_") }
