package ai.tatawur.rafiq.bridge.hook

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HookSettingsTest {
    private val settings = HookSettings(PROGRAM, "/var/lib/rafiq")

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun handlers(
        file: JsonObject,
        event: String,
    ) = file["hooks"]!!.jsonObject[event]!!.jsonArray.flatMap { it.jsonObject["hooks"]!!.jsonArray }

    private fun commands(
        file: JsonObject,
        event: String,
    ) = handlers(file, event).map { it.jsonObject["command"]!!.jsonPrimitive.content }

    @Test
    fun install_on_an_empty_file_adds_one_handler_per_event_and_the_deny_rules() {
        val file = settings.install(JsonObject(emptyMap()))

        assertEquals(HookSettings.EVENTS.toSet(), file["hooks"]!!.jsonObject.keys)
        for (event in HookSettings.EVENTS) assertEquals(listOf(PROGRAM), commands(file, event), event)
        val permission = handlers(file, "PermissionRequest").single().jsonObject
        assertEquals(JsonPrimitive(900), permission["timeout"])
        assertEquals(null, permission["async"], "the approval handler must wait")
        assertEquals(JsonPrimitive(true), handlers(file, "MessageDisplay").single().jsonObject["async"])
        assertEquals(
            listOf("--data-dir", "/var/lib/rafiq"),
            permission["args"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("Read(//var/lib/rafiq/**)", "Edit(//var/lib/rafiq/**)"),
            file["permissions"]!!.jsonObject["deny"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun install_next_to_another_tool_keeps_its_hooks_and_settings_and_remove_gives_them_back_unchanged() {
        val theirs = json(OTHER_TOOL)

        val installed = settings.install(theirs)

        assertEquals(listOf("/opt/other-tool/notify", PROGRAM), commands(installed, "Stop"))
        assertEquals(listOf("/opt/other-tool/guard"), commands(installed, "Elicitation"))
        assertEquals(json(OTHER_TOOL)["model"], installed["model"])
        assertEquals(
            listOf("Bash(rm -rf:*)", "Read(//var/lib/rafiq/**)", "Edit(//var/lib/rafiq/**)"),
            installed["permissions"]!!.jsonObject["deny"]!!.jsonArray.map { it.jsonPrimitive.content },
        )

        assertEquals(HookSettings.Removal.Done(theirs), settings.remove(installed))
    }

    @Test
    fun installing_twice_changes_nothing_the_second_time() {
        val once = settings.install(json(OTHER_TOOL))

        assertEquals(once, settings.install(once))
        assertTrue(settings.isInstalled(once))
    }

    @Test
    fun removing_from_a_file_that_held_only_ours_leaves_nothing_so_the_file_goes() {
        val removal = settings.remove(settings.install(JsonObject(emptyMap())))

        assertEquals(HookSettings.Removal.Done(null), removal)
    }

    @Test
    fun remove_takes_our_handler_out_of_a_shared_group_and_keeps_the_rest_of_it() {
        // Someone moved our handler into their own group with a matcher. Only our handler goes.
        val shared =
            json(
                """{"hooks":{"PreToolUse":[{"matcher":"Bash","hooks":[
                   {"type":"command","command":"/opt/other-tool/guard"},
                   ${settings.handler("PreToolUse")}]}]}}""",
            )

        val removal = settings.remove(shared) as HookSettings.Removal.Done

        assertEquals(
            json(
                """{"hooks":{"PreToolUse":[{"matcher":"Bash","hooks":[
                   {"type":"command","command":"/opt/other-tool/guard"}]}]}}""",
            ),
            removal.settings,
        )
    }

    @Test
    fun remove_is_refused_when_our_entries_were_edited_or_copied() {
        val installed = settings.install(JsonObject(emptyMap()))
        val edited =
            installed.replaceHandler("PermissionRequest") { JsonObject(it + ("timeout" to JsonPrimitive(30))) }
        val stop = installed["hooks"]!!.jsonObject["Stop"]!!.jsonArray
        val copied = installed.withEvent("Stop", JsonArray(stop + stop))
        val elsewhere = installed.withEvent("WorktreeCreate", stop)

        for (file in listOf(edited, copied, elsewhere)) {
            assertEquals(HookSettings.Removal.Changed, settings.remove(file))
        }
    }

    private fun JsonObject.withEvent(
        event: String,
        groups: JsonArray,
    ): JsonObject = JsonObject(this + ("hooks" to JsonObject(this["hooks"]!!.jsonObject + (event to groups))))

    private fun JsonObject.replaceHandler(
        event: String,
        change: (JsonObject) -> JsonObject,
    ): JsonObject {
        val hooks = this["hooks"]!!.jsonObject
        val group = hooks[event]!!.jsonArray.single().jsonObject
        val handler = change(group["hooks"]!!.jsonArray.single().jsonObject)
        return withEvent(event, JsonArray(listOf(JsonObject(group + ("hooks" to JsonArray(listOf(handler)))))))
    }

    private companion object {
        const val PROGRAM = "/opt/rafiq-bridge/hooks/rafiq-hook.py"

        const val OTHER_TOOL = """{
          "model": "placeholder-model",
          "permissions": {"deny": ["Bash(rm -rf:*)"], "defaultMode": "default"},
          "hooks": {
            "Stop": [{"hooks": [{"type": "command", "command": "/opt/other-tool/notify"}]}],
            "Elicitation": [{"hooks": [{"type": "command", "command": "/opt/other-tool/guard"}]}]
          }
        }"""
    }
}
