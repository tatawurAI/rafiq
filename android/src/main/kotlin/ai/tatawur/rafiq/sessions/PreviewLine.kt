package ai.tatawur.rafiq.sessions

/**
 * A session's last line as plain text for the list row. The bridge sends the start of the latest
 * message as the agent wrote it, which is markdown, and a row shows two lines of plain text. So
 * heading and quote marks, list bullets, emphasis markers, backticks and link syntax go, the link
 * text stays, and all whitespace collapses to single spaces.
 *
 * Underscores and asterisks inside words stay, so `snake_case` and `2*3` read as written.
 */
internal fun previewLine(markdown: String): String =
    markdown
        .lineSequence()
        .map { line -> line.replace(BLOCK_MARK, "") }
        .joinToString(" ")
        .replace(IMAGE_OR_LINK, "$1")
        .replace(STRONG_OR_STRIKE, "")
        .replace(EMPHASIS, "$1")
        .replace("`", "")
        .replace(WHITESPACE, " ")
        .trim()

// Leading heading hashes, quote marks, bullets and ordered-list numbers, in any combination.
private val BLOCK_MARK = Regex("""^\s*(?:(?:#{1,6}|>|[-*+]|\d+[.)])\s+)+""")

// `[text](url)` and `![alt](url)` keep only their text.
private val IMAGE_OR_LINK = Regex("""!?\[([^\]]*)]\([^)]*\)""")

private val STRONG_OR_STRIKE = Regex("""\*\*|__|~~""")

// A single `*` or `_` that opens and closes a span outside a word.
private val EMPHASIS = Regex("""(?<![\w*])[*_](\S(?:[^*_]*\S)?)[*_](?![\w*])""")

private val WHITESPACE = Regex("""\s+""")
