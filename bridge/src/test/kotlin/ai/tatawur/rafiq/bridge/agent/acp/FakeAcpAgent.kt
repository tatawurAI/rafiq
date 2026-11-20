// A stand-in ACP agent for tests. The adapter starts it as a real child process, so the tests cover
// the same stdio, process and shutdown path as a real agent.
package ai.tatawur.rafiq.bridge.agent.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.file.Path
import kotlin.io.path.bufferedWriter
import kotlin.io.path.readLines
import kotlin.io.path.toPath
import kotlin.io.path.writeText
import kotlin.system.exitProcess

/**
 * Replays a fixture as the agent side of an ACP connection.
 *
 * Arguments: the fixture path, a path to log every line the bridge sends, and optionally `exit-at-end`.
 * Each `in` entry is written to stdout. A run of consecutive `out` entries waits for that many lines
 * on stdin, in any order, since the bridge may send a response and a notification concurrently. A
 * request's recorded id is mapped to the id the bridge used, so recorded answers still match. A line
 * that matches no expected entry exits with code 2, which the test sees as the agent failing. Stdin
 * closing early ends the replay, as it would end a real agent.
 *
 * A `raw` entry writes its `line` as is, or `count` copies of `repeat`, to send something that is not
 * a JSON-RPC message. At start it writes its pid to `<log>.pid` and its environment's names to
 * `<log>.env`, so a test can check the very process it started.
 *
 * At the end it exits, or with no `exit-at-end` it waits for stdin to close, as a real agent does.
 */
fun main(args: Array<String>) {
    val script =
        Path
            .of(args[0])
            .readLines()
            .filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject }
    Path.of("${args[1]}.pid").writeText(ProcessHandle.current().pid().toString())
    Path.of("${args[1]}.env").writeText(
        System
            .getenv()
            .keys
            .sorted()
            .joinToString("\n"),
    )
    Replay(script, Path.of(args[1]).bufferedWriter()).run(exitAtEnd = args.getOrNull(2) == "exit-at-end")
}

/** Steps through the script, writing `in` and `raw` entries and waiting for `out` ones. */
private class Replay(
    private val script: List<JsonObject>,
    private val log: java.io.BufferedWriter,
) {
    private val stdin = System.`in`.bufferedReader()
    private val stdout = System.out.bufferedWriter()
    private val idsSeen = mutableMapOf<JsonElement, JsonElement>()

    fun run(exitAtEnd: Boolean) {
        var index = 0
        while (index < script.size) {
            val entry = script[index]
            index +=
                when (dir(entry)) {
                    "raw" -> write(rawLine(entry))
                    "in" -> write(withMappedId(entry.getValue("msg").jsonObject, idsSeen).toString())
                    "out" -> awaitRun(index)
                    else -> 1
                }
        }
        if (!exitAtEnd) {
            while (true) logLine(stdin.readLine() ?: break)
        }
        log.close()
        exitProcess(0)
    }

    /** Waits for the run of `out` entries starting at [start], in any order. Returns its length. */
    private fun awaitRun(start: Int): Int {
        val expected = script.drop(start).takeWhile { dir(it) == "out" }
        val waiting = expected.map { it.getValue("msg").jsonObject }.toMutableList()
        repeat(waiting.size) {
            val received = receive()
            val match = waiting.firstOrNull { key(it) == key(received) } ?: fail("unexpected ${key(received)}")
            waiting.remove(match)
            if ("method" in match && "id" in match) idsSeen[match.getValue("id")] = received.getValue("id")
        }
        return expected.size
    }

    private fun receive(): JsonObject {
        // The bridge hung up early, for example a test that closes the session mid-script.
        val line = stdin.readLine() ?: exitProcess(0)
        logLine(line)
        return Json.parseToJsonElement(line).jsonObject
    }

    private fun write(line: String): Int {
        stdout.write(line)
        stdout.newLine()
        stdout.flush()
        return 1
    }

    private fun logLine(line: String) {
        log.write(line)
        log.newLine()
        log.flush()
    }
}

private fun dir(entry: JsonObject): String? = (entry["dir"] as? JsonPrimitive)?.content

private fun rawLine(entry: JsonObject): String {
    val repeat = (entry["repeat"] as? JsonPrimitive)?.content
    val count = (entry["count"] as? JsonPrimitive)?.content?.toInt() ?: 1
    return repeat?.repeat(count) ?: (entry.getValue("line") as JsonPrimitive).content
}

/** A request or notification matches by method, a response by the id it answers. */
private fun key(message: JsonObject): String =
    (message["method"] as? JsonPrimitive)?.content ?: "response to ${message["id"]}"

/** A recorded answer carries the recorded request id. Swap in the id the bridge actually sent. */
private fun withMappedId(
    message: JsonObject,
    idsSeen: Map<JsonElement, JsonElement>,
): JsonObject {
    val actual = message["id"]?.let(idsSeen::get)
    return if ("method" in message || actual == null) message else JsonObject(message + ("id" to actual))
}

private fun fail(reason: String): Nothing {
    System.err.println("FakeAcpAgent: $reason")
    exitProcess(2)
}

/** The command that starts [main] on [fixture], a resource under `acp/`, logging what the bridge sends to [log]. */
internal fun fakeAcpAgentCommand(
    fixture: String,
    log: Path,
    exitAtEnd: Boolean = true,
): List<String> {
    val resource = requireNotNull(object {}.javaClass.getResource("/acp/$fixture")) { "Missing fixture $fixture" }
    return listOfNotNull(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        // A small, quick-starting JVM: the fake only shuffles a few lines of JSON.
        "-Xmx64m",
        "-XX:TieredStopAtLevel=1",
        "-cp",
        System.getProperty("java.class.path"),
        "ai.tatawur.rafiq.bridge.agent.acp.FakeAcpAgentKt",
        resource.toURI().toPath().toString(),
        log.toString(),
        "exit-at-end".takeIf { exitAtEnd },
    )
}
