package ai.tatawur.rafiq.bridge.agent.acp

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Puts everything the agent sends into one channel, in the order it appeared on stdout.
 *
 * The SDK hands a response to the coroutine that sent the request, and a notification to a handler
 * on another coroutine, so the two can be seen in either order. The app's badges depend on the order
 * the agent chose, for example a turn that ends before a background helper finishes. So the session
 * maps nothing from the SDK's callbacks. It reads [messages], which this class fills from the raw
 * lines before the SDK parses them. The SDK still does the JSON-RPC work: matching answers to
 * requests, and sending the answers to the agent's own requests.
 *
 * Requests the bridge sends are noted by id on the way out, so their answers can be told apart.
 */
internal class WireOrder {
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
         * @property error the error message, or null when the request succeeded.
         */
        data class Response(
            val method: String?,
            val result: JsonElement?,
            val error: String?,
        ) : Inbound

        /** The agent closed stdout. Nothing follows. */
        data object End : Inbound
    }

    private val queue = Channel<Inbound>(Channel.UNLIMITED)
    private val sentMethods = ConcurrentHashMap<String, String>()

    /** The agent's messages in wire order, ending with [Inbound.End]. */
    val messages: ReceiveChannel<Inbound> = queue

    /** Passes the agent's stdout lines on to the SDK unchanged, noting each one first. */
    fun inbound(lines: Flow<String>): Flow<String> =
        lines
            .onEach(::note)
            // A broken pipe ends the output like a closed one. The process exit says what happened.
            .catch { if (it !is IOException) throw it }
            .onCompletion { queue.trySend(Inbound.End) }

    /** Wraps the writer the SDK sends with, noting the id and method of each request. */
    fun outbound(write: suspend (String) -> Unit): suspend (String) -> Unit =
        { line ->
            val message = parse(line)
            val method = message?.string("method")
            val id = message?.get("id")
            if (method != null && id != null) sentMethods[requestKey(id)] = method
            write(line)
        }

    private fun note(line: String) {
        val message = parse(line) ?: return
        val method = message.string("method")
        val id = message["id"]
        val params = message["params"] as? JsonObject
        val inbound =
            when {
                method != null && id != null -> {
                    Inbound.Request(requestKey(id), method, params)
                }

                method != null -> {
                    Inbound.Notification(method, params)
                }

                id != null -> {
                    Inbound.Response(
                        method = sentMethods.remove(requestKey(id)),
                        result = message["result"],
                        error = (message["error"] as? JsonObject)?.let { it.string("message") ?: "The agent refused." },
                    )
                }

                else -> {
                    return
                }
            }
        queue.trySend(inbound)
    }

    private fun parse(line: String): JsonObject? =
        try {
            Json.parseToJsonElement(line).jsonObject
        } catch (_: IllegalArgumentException) {
            // Not a JSON object; SerializationException is one of these too. The SDK answers it as a
            // parse error, and there is nothing to map.
            null
        }
}

/** A JSON-RPC id as JSON text, so the number 3 and the string "3" stay different keys. */
internal fun requestKey(id: JsonElement): String = id.toString()
