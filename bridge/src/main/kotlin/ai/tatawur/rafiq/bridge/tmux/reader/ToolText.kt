package ai.tatawur.rafiq.bridge.tmux.reader

import ai.tatawur.rafiq.protocol.ApprovalDetail
import ai.tatawur.rafiq.protocol.ToolCallKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * How Claude Code's tool calls read on the phone: the icon kind, the one-line summary on a card, the output a
 * finished card shows, and the full text and session rule on an approval card.
 */

/** The tool Claude Code uses to ask you a question. It arrives as a permission request. */
internal const val ASK_USER_QUESTION = "AskUserQuestion"

/** The longest output a tool card carries. A card is not a file viewer, and the event log keeps every byte. */
internal const val TOOL_OUTPUT_CHARS = 4000

/** The longest one-line summary on a tool card. */
private const val SUMMARY_CHARS = 200

/** The most text an approval card carries. The rest is cut, and the card says so. */
internal const val APPROVAL_TEXT_CHARS = 8000

private val prettyJson = Json { prettyPrint = true }

internal fun toolKind(tool: String): ToolCallKind =
    when (tool) {
        "Bash", "PowerShell" -> ToolCallKind.COMMAND
        "Write", "Edit", "MultiEdit", "NotebookEdit" -> ToolCallKind.EDIT
        "Read" -> ToolCallKind.READ
        "Grep", "Glob" -> ToolCallKind.SEARCH
        "WebFetch", "WebSearch" -> ToolCallKind.FETCH
        else -> ToolCallKind.OTHER
    }

/**
 * One line that says what a tool call does, for a card. Never a file's contents. A subagent's step carries the
 * subagent's type in front, so it reads apart from the main conversation's steps.
 */
internal fun summarise(
    tool: String,
    input: JsonObject,
    agentType: String? = null,
): String {
    val field =
        when (tool) {
            "Bash", "PowerShell" -> input.str("command")
            "Write", "Edit", "MultiEdit", "Read", "NotebookEdit" -> input.str("file_path")
            "Grep", "Glob" -> input.str("pattern")
            "Agent", "Task" -> input.str("description")
            "WebFetch" -> input.str("url")
            "WebSearch" -> input.str("query")
            ASK_USER_QUESTION -> firstQuestion(input)?.str("question")
            else -> null
        }
    val line = field?.lineSequence()?.firstOrNull()?.take(SUMMARY_CHARS)
    val summary = if (line.isNullOrBlank()) tool else "$tool: $line"
    return if (agentType.isNullOrBlank()) summary else "[$agentType] $summary"
}

/** What a finished tool call shows as its output: the text a person would look at, never a file's contents. */
internal fun describeResult(
    tool: String,
    response: JsonElement?,
): String? {
    val text =
        when (response) {
            null, JsonNull -> {
                null
            }

            is JsonPrimitive -> {
                response.content
            }

            is JsonArray -> {
                response.joinToString("\n") { (it as? JsonObject)?.str("text") ?: "" }.trim()
            }

            is JsonObject -> {
                when (tool) {
                    "Bash", "PowerShell" -> {
                        listOfNotNull(response.str("stdout"), response.str("stderr"))
                            .filter(String::isNotBlank)
                            .joinToString("\n")
                    }

                    "Write", "Edit", "MultiEdit" -> {
                        response.str("filePath")?.let { "Wrote $it" }
                    }

                    "Agent", "Task" -> {
                        response.str("status")
                    }

                    else -> {
                        null
                    }
                }
            }
        }
    return text?.takeIf(String::isNotEmpty)?.take(TOOL_OUTPUT_CHARS)
}

/**
 * The full text you approve, for [ApprovalDetail]. A command is shown whole, every line of it, because the
 * agent controls it. Any other tool shows its whole input. Cut at [APPROVAL_TEXT_CHARS], with the line count
 * of the whole text.
 */
internal fun approvalDetail(
    tool: String,
    input: JsonObject,
    sessionRule: String?,
): ApprovalDetail {
    val whole =
        when (tool) {
            "Bash", "PowerShell" -> {
                input.str("command")?.let { it + commandOptions(input) }
                    ?: prettyJson.encodeToString(JsonObject.serializer(), input)
            }

            ASK_USER_QUESTION -> {
                questionText(input)
            }

            else -> {
                prettyJson.encodeToString(JsonObject.serializer(), input)
            }
        }
    val truncated = whole.length > APPROVAL_TEXT_CHARS
    return ApprovalDetail(
        text = if (truncated) whole.take(APPROVAL_TEXT_CHARS) else whole,
        lineCount = whole.lines().size,
        truncated = truncated,
        sessionRule = sessionRule,
    )
}

/**
 * The settings of a command that change what running it means, as one more line under it, so the card does not
 * hide them: running outside the sandbox, in the background, or with a time limit. Empty when all are default.
 */
private fun commandOptions(input: JsonObject): String {
    val options =
        listOfNotNull(
            "dangerouslyDisableSandbox".takeIf { input.bool(it) == true }?.let { "$it=true" },
            "run_in_background".takeIf { input.bool(it) == true }?.let { "$it=true" },
            input.long("timeout")?.let { "timeout=$it" },
        )
    return if (options.isEmpty()) "" else "\n\nOptions: " + options.joinToString(", ")
}

/**
 * The plain description of an approval request, for an app that does not read [ApprovalDetail]. It says when
 * the text has more lines than its first, or was cut, so an older app never shows a multi-line command as one line.
 */
internal fun approvalDescription(
    tool: String,
    input: JsonObject,
    detail: ApprovalDetail,
): String {
    val summary =
        if (tool ==
            ASK_USER_QUESTION
        ) {
            summarise(tool, input).removePrefix("$tool: ")
        } else {
            summarise(tool, input)
        }
    val lines = if (detail.lineCount > 1) " (${detail.lineCount} lines)" else ""
    val cut = if (detail.truncated) " (cut)" else ""
    return summary + lines + cut
}

/** The single question of an `AskUserQuestion` call, or null when it asks none or several. */
internal fun singleQuestion(input: JsonObject): JsonObject? =
    input.arr("questions")?.takeIf { it.size == 1 }?.firstOrNull() as? JsonObject

private fun firstQuestion(input: JsonObject): JsonObject? = input.arr("questions")?.firstOrNull() as? JsonObject

private fun questionText(input: JsonObject): String =
    input.arr("questions").orEmpty().mapNotNull { it as? JsonObject }.joinToString("\n\n") { question ->
        val options =
            question.arr("options").orEmpty().mapNotNull { (it as? JsonObject)?.str("label") }.joinToString("\n") {
                "- $it"
            }
        listOfNotNull(question.str("question"), options.takeIf(String::isNotEmpty)).joinToString("\n")
    }
