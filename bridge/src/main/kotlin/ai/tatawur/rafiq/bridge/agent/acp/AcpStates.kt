// How ACP's enums and state strings map onto Rafiq's. Each function takes the wire value as a string
// where an agent may send one this build has never seen, so a new value degrades instead of failing.
package ai.tatawur.rafiq.bridge.agent.acp

import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.ApprovalOptionKind
import ai.tatawur.rafiq.protocol.ToolCallKind
import ai.tatawur.rafiq.protocol.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.agentclientprotocol.model.ToolCallStatus as AcpToolCallStatus

/** Rafiq has no separate kinds for delete and move, so they show as edits. Thinking and mode switches are other. */
internal fun toolCallKind(kind: ToolKind?): ToolCallKind =
    when (kind) {
        ToolKind.READ -> ToolCallKind.READ
        ToolKind.EDIT, ToolKind.DELETE, ToolKind.MOVE -> ToolCallKind.EDIT
        ToolKind.SEARCH -> ToolCallKind.SEARCH
        ToolKind.EXECUTE -> ToolCallKind.COMMAND
        ToolKind.FETCH -> ToolCallKind.FETCH
        ToolKind.THINK, ToolKind.SWITCH_MODE, ToolKind.OTHER, null -> ToolCallKind.OTHER
    }

internal fun toolCallStatus(status: AcpToolCallStatus): ToolCallStatus =
    when (status) {
        AcpToolCallStatus.PENDING -> ToolCallStatus.PENDING
        AcpToolCallStatus.IN_PROGRESS -> ToolCallStatus.IN_PROGRESS
        AcpToolCallStatus.COMPLETED -> ToolCallStatus.COMPLETED
        AcpToolCallStatus.FAILED -> ToolCallStatus.FAILED
    }

/** Both reject kinds are a deny. Whether it sticks is in the option's label. */
internal fun approvalOptionKind(kind: String?): ApprovalOptionKind =
    when (kind) {
        "allow_once" -> ApprovalOptionKind.ALLOW_ONCE
        "allow_always" -> ApprovalOptionKind.ALLOW_ALWAYS
        "reject_once", "reject_always" -> ApprovalOptionKind.DENY
        else -> ApprovalOptionKind.UNKNOWN
    }

/**
 * The state a turn ends in, from the ACP stop reason. A cancelled turn is done, because you asked for
 * it. Running out of tokens or turns, a refusal and a failure all need your attention.
 */
internal fun stopState(stopReason: String?): AgentState =
    when (stopReason) {
        null, "end_turn", "cancelled" -> AgentState.DONE
        "max_tokens", "max_turn_requests", "refusal", "error" -> AgentState.ERROR
        else -> AgentState.UNKNOWN
    }

/** The main thread's state after a turn: your move, unless the turn ended in a way that needs a look. */
internal fun turnEndState(stopReason: String?): AgentState =
    when (stopReason) {
        null, "end_turn", "cancelled" -> AgentState.WAITING_ON_YOU
        else -> stopState(stopReason)
    }

/**
 * A child session's work state from the `state` object of a `subagent_update`. `idle` is the end of a
 * piece of work, so it maps through its stop reason. An absent or cleared state is unconfirmed.
 */
internal fun subagentState(state: JsonElement?): AgentState {
    val obj = state as? JsonObject ?: return AgentState.UNKNOWN
    return when (obj.string("state")) {
        "running" -> AgentState.WORKING
        "requires_action" -> AgentState.WAITING_ON_YOU
        "idle" -> stopState(obj.string("stopReason"))
        else -> AgentState.UNKNOWN
    }
}

/** The string value of [key], or null when it is absent or not a string. */
internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
