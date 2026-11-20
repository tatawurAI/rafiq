package ai.tatawur.rafiq.bridge.agent.acp

import com.agentclientprotocol.model.InitializeRequest
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Builds the `initialize` params with `clientCapabilities.subagents` set to `{}`.
 *
 * ACP v1 sends a parent's helper agents as child sessions only to a client that opts in with that
 * field. The SDK's `ClientCapabilities` has no `subagents` property, and `_meta` cannot stand in for
 * it because the spec names the real field. So the request is encoded with the SDK's own serializer
 * and the field is added to the resulting JSON. Everything else in the request stays typed.
 *
 * This file can go once the SDK's `ClientCapabilities` has a `subagents` field: set it there and
 * send the typed request.
 */
internal fun initializeParamsWithSubagents(request: InitializeRequest): JsonObject {
    val encoded = ACPJson.encodeToJsonElement(InitializeRequest.serializer(), request).jsonObject
    val capabilities = encoded[CLIENT_CAPABILITIES]?.jsonObject ?: JsonObject(emptyMap())
    val withSubagents = JsonObject(capabilities + (SUBAGENTS to JsonObject(emptyMap())))
    return JsonObject(encoded + (CLIENT_CAPABILITIES to withSubagents))
}

private const val CLIENT_CAPABILITIES = "clientCapabilities"
private const val SUBAGENTS = "subagents"
