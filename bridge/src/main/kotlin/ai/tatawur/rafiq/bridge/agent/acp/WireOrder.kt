package ai.tatawur.rafiq.bridge.agent.acp

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onCompletion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Hands everything the agent sends to [sink], in the order it appeared on stdout.
 *
 * The SDK hands a response to the coroutine that sent the request, and a notification to a handler
 * on another coroutine, so the two can be seen in either order. The app's badges depend on the order
 * the agent chose, for example a turn that ends before a background helper finishes. So the session
 * maps nothing from the SDK's callbacks. It maps what this class passes to [sink], which it takes
 * from the raw lines before the SDK parses them. The SDK still does the JSON-RPC work: matching
 * answers to requests, and sending the answers to the agent's own requests.
 *
 * Requests the bridge sends are noted by id on the way out, so their answers can be told apart.
 * Lines that are not a JSON object, such as stray log output, are logged and never reach the SDK.
 */
internal class WireOrder(
    private val sink: (Inbound) -> Unit,
) {
    /** One message from the agent, reduced to what the session acts on. */
    sealed interface Inbound {
        data class Notification(
            val method: String,
            val params: JsonObject?,
        ) : Inbound

        /** @property requestKey the JSON-RPC id as JSON text, see [requestKey]. */
        data class Request(
            val requestKey: String,
            val method: String,
            val params: JsonObject?,
        ) : Inbound

        /**
         * The answer to a request the bridge sent.
         *
         * @property method the method of that request, or null when it was not one the bridge noted.
         * @property error the agent's error message, or null when the request succeeded. It is for the
         *   bridge's log, never for an event.
         */
        data class Response(
            val method: String?,
            val result: JsonElement?,
            val error: String?,
        ) : Inbound

        /** A line too long to read was dropped. */
        data object Dropped : Inbound

        /** The agent closed stdout, or reading it failed. Nothing follows. */
        data object End : Inbound
    }

    private val sentMethods = ConcurrentHashMap<String, String>()

    /** Passes the agent's JSON lines on to the SDK unchanged, noting each one first. */
    fun inbound(lines: Flow<AgentProcess.Line>): Flow<String> =
        lines
            .mapNotNull { line ->
                when (line) {
                    is AgentProcess.Line.Text -> {
                        line.text.takeIf(::note)
                    }

                    AgentProcess.Line.TooLong -> {
                        sink(Inbound.Dropped)
                        null
                    }
                }
            }.catch { failure ->
                // Any failure ends the output like a closed pipe. The process exit says what happened.
                if (failure is CancellationException) throw failure
                logger.warn("reading agent output failed: {}", failure.toString())
            }.onCompletion { sink(Inbound.End) }

    /** Wraps the writer the SDK sends with, noting the id and method of each request. */
    fun outbound(write: suspend (String) -> Unit): suspend (String) -> Unit =
        { line ->
            val message = parse(line)
            val method = message?.string("method")
            val id = message?.get("id")
            if (method != null && id != null) sentMethods[requestKey(id)] = method
            write(line)
        }

    /** Hands [line] to [sink]. Returns false for a line that is not a JSON object. */
    private fun note(line: String): Boolean {
        val message = parse(line)
        if (message == null) {
            logger.warn("dropped a line from the agent that is not a JSON object ({} chars)", line.length)
            return false
        }
        val method = message.string("method")
        val id = message["id"]
        val params = message["params"] as? JsonObject
        when {
            method != null && id != null -> {
                sink(Inbound.Request(requestKey(id), method, params))
            }

            method != null -> {
                sink(Inbound.Notification(method, params))
            }

            id != null -> {
                sink(
                    Inbound.Response(
                        method = sentMethods.remove(requestKey(id)),
                        result = message["result"],
                        error = (message["error"] as? JsonObject)?.let { it.string("message") ?: it.toString() },
                    ),
                )
            }
        }
        return true
    }

    private fun parse(line: String): JsonObject? =
        try {
            Json.parseToJsonElement(line).jsonObject
        } catch (_: IllegalArgumentException) {
            // Not a JSON object. SerializationException is one of these too.
            null
        }

    private companion object {
        val logger = LoggerFactory.getLogger(WireOrder::class.java)
    }
}

/** A JSON-RPC id as JSON text, so the number 3 and the string "3" stay different keys. */
internal fun requestKey(id: JsonElement): String = id.toString()
