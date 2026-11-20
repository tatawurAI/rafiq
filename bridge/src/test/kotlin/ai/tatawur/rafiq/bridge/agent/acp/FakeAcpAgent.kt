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
 * At the end it exits, or with no `exit-at-end` it waits for stdin to close, as a real agent does.
 */
fun main(args: Array<String>) {
    val script =
        Path
            .of(args[0])
            .readLines()
            .filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject }
    val log = Path.of(args[1]).bufferedWriter()
    val exitAtEnd = args.getOrNull(2) == "exit-at-end"
    val stdin = System.`in`.bufferedReader()
    val stdout = System.out.bufferedWriter()
    val idsSeen = mutableMapOf<JsonElement, JsonElement>()

    fun receive(): JsonObject {
        // The bridge hung up early, for example a test that closes the session mid-script.
        val line = stdin.readLine() ?: exitProcess(0)
        log.write(line)
        log.newLine()
        log.flush()
        return Json.parseToJsonElement(line).jsonObject
    }

    var index = 0
    while (index < script.size) {
        val entry = script[index]
        val message = entry.getValue("msg").jsonObject
        when ((entry["dir"] as? JsonPrimitive)?.content) {
            "in" -> {
                stdout.write(withMappedId(message, idsSeen).toString())
                stdout.newLine()
                stdout.flush()
                index++
            }

            "out" -> {
                val expected = script.drop(index).takeWhile { (it["dir"] as? JsonPrimitive)?.content == "out" }
                val waiting = expected.map { it.getValue("msg").jsonObject }.toMutableList()
                repeat(waiting.size) {
                    val received = receive()
                    val match = waiting.firstOrNull { key(it) == key(received) } ?: fail("unexpected ${key(received)}")
                    waiting.remove(match)
                    if ("method" in match && "id" in match) idsSeen[match.getValue("id")] = received.getValue("id")
                }
                index += expected.size
            }

            else -> {
                index++
            }
        }
    }
    if (!exitAtEnd) {
        while (true) {
            val line = stdin.readLine() ?: break
            log.write(line)
            log.newLine()
            log.flush()
        }
    }
    log.close()
    exitProcess(0)
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
