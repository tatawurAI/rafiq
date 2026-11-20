package ai.tatawur.rafiq.bridge.agent.acp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Path
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
) {
    /** Lines the agent writes to stdout. The flow ends when the agent closes stdout or exits. */
    val lines: Flow<String> =
        process.inputStream
            .bufferedReader()
            .lineSequence()
            .asFlow()
            .flowOn(ioDispatcher)

    private val stdin = process.outputStream.bufferedWriter()

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
        withContext(ioDispatcher) {
            runCatching { stdin.close() }
            if (withTimeoutOrNull(stopGracePeriod) { awaitExit() } != null) return@withContext
            val children = process.descendants().toList()
            children.forEach { it.destroy() }
            process.destroy()
            if (withTimeoutOrNull(stopGracePeriod) { awaitExit() } != null) return@withContext
            children.forEach { it.destroyForcibly() }
            process.destroyForcibly()
            awaitExit()
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AgentProcess::class.java)

        /**
         * Starts [command] in [workingDirectory].
         *
         * @throws IOException when the command cannot be started, for example because it is not installed.
         */
        fun start(
            command: List<String>,
            workingDirectory: Path,
            ioDispatcher: CoroutineDispatcher,
            stopGracePeriod: Duration,
        ): AgentProcess {
            val process = ProcessBuilder(command).directory(workingDirectory.toFile()).start()
            return AgentProcess(process, ioDispatcher, stopGracePeriod)
        }
    }
}
