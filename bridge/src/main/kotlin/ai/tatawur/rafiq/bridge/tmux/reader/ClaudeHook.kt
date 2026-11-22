package ai.tatawur.rafiq.bridge.tmux.reader

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Tolerant reads of Claude Code's hook payloads and session record lines. Neither is a stable contract
 * (ADR 0013, "Risk"), so a field that is missing or has another type reads as null and the caller skips it.
 * Nothing here throws on a shape it does not expect.
 */

/** The hook event names the reader acts on. Every other event is ignored. */
internal object ClaudeHook {
    const val SESSION_START = "SessionStart"
    const val SESSION_END = "SessionEnd"
    const val USER_PROMPT_SUBMIT = "UserPromptSubmit"
    const val MESSAGE_DISPLAY = "MessageDisplay"
    const val PRE_TOOL_USE = "PreToolUse"
    const val POST_TOOL_USE = "PostToolUse"
    const val POST_TOOL_USE_FAILURE = "PostToolUseFailure"
    const val POST_TOOL_BATCH = "PostToolBatch"
    const val PERMISSION_REQUEST = "PermissionRequest"
    const val STOP = "Stop"
    const val STOP_FAILURE = "StopFailure"
    const val SUBAGENT_STOP = "SubagentStop"
    const val PRE_COMPACT = "PreCompact"

    /** `SessionEnd.reason` for `/clear`, which is followed at once by a new session in the same process. */
    const val END_REASON_CLEAR = "clear"
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

internal val EMPTY_OBJECT = JsonObject(emptyMap())
