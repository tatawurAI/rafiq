package ai.tatawur.rafiq.bridge.hook

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What the bridge writes into a project's `.claude/settings.local.json` (ADR 0013 "Installing the hooks", issue
 * #44), and how it takes exactly that out again. Pure functions over the parsed file, so every merge case is
 * tested without a disk.
 *
 * Every handler the bridge adds runs [command], the installed hook program, which is what marks it as the
 * bridge's. Install never touches another tool's handler. Remove takes out only handlers that are exactly what
 * install writes, drops the events, `hooks`, `deny` and `permissions` keys that became empty, and never sets
 * `disableAllHooks`.
 *
 * @param command the absolute path of `rafiq-hook.py`.
 * @param dataDir the bridge's data folder, passed to the hook and covered by the deny rules.
 */
class HookSettings(
    private val command: String,
    private val dataDir: String,
) {
    /**
     * The read and edit deny rules for the data folder: the database, the hook credential and the sockets. In
     * Claude Code's rule syntax a path that starts with `//` is absolute.
     */
    val denyRules: List<String> = listOf("Read(/$dataDir/**)", "Edit(/$dataDir/**)")

    /** The handler for [event]. Observers are async, so they never hold Claude Code up. */
    fun handler(event: String): JsonObject =
        buildJsonObject {
            put("type", "command")
            put("command", command)
            put(
                "args",
                buildJsonArray {
                    add(JsonPrimitive("--data-dir"))
                    add(JsonPrimitive(dataDir))
                },
            )
            if (event == PERMISSION_EVENT) put("timeout", PERMISSION_TIMEOUT_SECONDS) else put("async", true)
        }

    /** True when the file has at least one handler that runs the hook program. */
    fun isInstalled(settings: JsonObject): Boolean =
        hooksOf(settings).values.any { groups -> groups.groups().any { group -> group.handlers().any(::isOurs) } }

    /** [settings] with the bridge's handlers and deny rules added once each. Everything else stays as it was. */
    fun install(settings: JsonObject): JsonObject {
        val cleared = strip(settings).settings
        val hooks = hooksOf(cleared).toMutableMap()
        for (event in EVENTS) {
            val group = buildJsonObject { put("hooks", JsonArray(listOf(handler(event)))) }
            hooks[event] = JsonArray(hooks[event].groups() + group)
        }
        val permissions = (cleared["permissions"] as? JsonObject).orEmpty().toMutableMap()
        val deny = (permissions["deny"] as? JsonArray).orEmpty()
        permissions["deny"] = JsonArray(deny + denyRules.map(::JsonPrimitive).filter { it !in deny })
        return JsonObject(cleared + ("hooks" to JsonObject(hooks)) + ("permissions" to JsonObject(permissions)))
    }

    /** What [remove] found. */
    sealed interface Removal {
        /** [settings] is the file without the bridge's entries, or null when nothing else is left in it. */
        data class Done(
            val settings: JsonObject?,
        ) : Removal

        /** Something with the hook program's path is not what install writes. Removing it could lose your edits. */
        data object Changed : Removal
    }

    /** [settings] without the bridge's entries, or [Removal.Changed] if removing them would take anything else. */
    fun remove(settings: JsonObject): Removal {
        val stripped = strip(settings)
        return if (stripped.foreign) Removal.Changed else Removal.Done(stripped.settings.takeIf { it.isNotEmpty() })
    }

    private class Stripped(
        val settings: JsonObject,
        val foreign: Boolean,
    )

    /**
     * Takes out every handler that runs [command] and the deny rules. [Stripped.foreign] is true when one of those
     * handlers is not exactly what install writes, or an event has more than one.
     */
    private fun strip(settings: JsonObject): Stripped {
        var foreign = false
        val result = settings.toMutableMap()
        (settings["hooks"] as? JsonObject)?.let { hooks ->
            val kept = linkedMapOf<String, JsonElement>()
            hooks.forEach { (event, value) ->
                val stripped = stripEvent(event, value)
                foreign = foreign || stripped.foreign
                stripped.value?.let { kept[event] = it }
            }
            if (kept.isEmpty()) result.remove("hooks") else result["hooks"] = JsonObject(kept)
        }
        (settings["permissions"] as? JsonObject)?.let { permissions ->
            val rest = stripDeny(permissions)
            if (rest.isEmpty()) result.remove("permissions") else result["permissions"] = JsonObject(rest)
        }
        return Stripped(JsonObject(result), foreign)
    }

    private class StrippedEvent(
        /** What stays under the event's key, or null to drop the key. */
        val value: JsonElement?,
        val foreign: Boolean,
    )

    private fun stripEvent(
        event: String,
        value: JsonElement,
    ): StrippedEvent {
        val groups = value as? JsonArray
        val ours = groups.orEmpty().flatMap { it.handlers() }.filter(::isOurs)
        if (groups == null || ours.isEmpty()) return StrippedEvent(value, foreign = false)
        val keptGroups =
            groups.mapNotNull { group ->
                val handlers = group.handlers()
                val kept = handlers.filterNot(::isOurs)
                when {
                    kept.size == handlers.size -> group

                    // A group left with no handlers held only ours. Its matcher means nothing on its own.
                    kept.isEmpty() -> null

                    else -> JsonObject((group as JsonObject) + ("hooks" to JsonArray(kept)))
                }
            }
        val foreign = ours.size > 1 || event !in EVENTS || ours.single() != handler(event)
        return StrippedEvent(JsonArray(keptGroups).takeIf { keptGroups.isNotEmpty() }, foreign)
    }

    /** [permissions] without the deny rules install adds. */
    private fun stripDeny(permissions: JsonObject): Map<String, JsonElement> {
        val rest = permissions.toMutableMap()
        (permissions["deny"] as? JsonArray)?.let { deny ->
            val kept = deny.filterNot { it is JsonPrimitive && it.isString && it.content in denyRules }
            if (kept.isEmpty()) rest.remove("deny") else rest["deny"] = JsonArray(kept)
        }
        return rest
    }

    private fun isOurs(handler: JsonElement): Boolean {
        val value = (handler as? JsonObject)?.get("command")
        return value is JsonPrimitive && value.isString && value.content == command
    }

    private fun hooksOf(settings: JsonObject): Map<String, JsonElement> = (settings["hooks"] as? JsonObject).orEmpty()

    companion object {
        /** The only event whose handler waits for an answer. */
        const val PERMISSION_EVENT = "PermissionRequest"

        /**
         * Above the longest wait for your phone (600 s), so the bridge, not Claude Code, ends a held request.
         * Claude Code's default of 600 s would kill the hook at the same moment.
         */
        const val PERMISSION_TIMEOUT_SECONDS = 900

        /**
         * The events the reader uses. `FileChanged` needs a matcher, and `WorktreeCreate` replaces Claude Code's own
         * git behaviour, so neither is registered.
         */
        val EVENTS: List<String> =
            listOf(
                "SessionStart",
                "SessionEnd",
                "UserPromptSubmit",
                "PreToolUse",
                PERMISSION_EVENT,
                "PostToolUse",
                "PostToolUseFailure",
                "PostToolBatch",
                "Notification",
                "SubagentStart",
                "SubagentStop",
                "Stop",
                "StopFailure",
                "PreCompact",
                "PostCompact",
                "MessageDisplay",
            )
    }
}

private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

private fun JsonElement?.groups(): List<JsonElement> = (this as? JsonArray).orEmpty()

private fun JsonElement.handlers(): List<JsonElement> = ((this as? JsonObject)?.get("hooks") as? JsonArray).orEmpty()
