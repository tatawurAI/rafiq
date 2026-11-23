package ai.tatawur.rafiq.bridge.tmux.reader

import kotlinx.serialization.json.JsonObject

/**
 * The rule that "allow for this session" adds, in Claude Code's rule syntax, or null when the tool gets no such
 * option. A command is allowed only exactly as written, `Bash(<command>)`: Claude's own suggestion can be a prefix
 * rule such as `npm test:*`, which allows more than you saw, so it is not used. A command with a wildcard, several
 * lines or a `)` gets no rule, since the rule would not mean exactly that command. Another tool is allowed by name,
 * for every call, and the option says so ([sessionRuleLabel]). Questions get none.
 */
internal fun sessionRuleFor(
    tool: String,
    input: JsonObject,
): String? =
    when (tool) {
        ASK_USER_QUESTION, "PowerShell" -> {
            null
        }

        "Bash" -> {
            input
                .str("command")
                ?.takeIf { '\n' !in it && ')' !in it && '*' !in it && !it.endsWith(":*") }
                ?.let { "Bash($it)" }
        }

        else -> {
            tool
        }
    }

/** The label of the "allow for this session" option, which says how much it allows. */
internal fun sessionRuleLabel(tool: String): String =
    if (tool == "Bash") "Allow this exact command this session" else "Allow every $tool this session"
