package ai.tatawur.rafiq.client

/**
 * [text] with every invisible control made visible as `<U+XXXX>`: format characters (Unicode category Cf,
 * such as the right-to-left override U+202E and zero-width spaces), control characters (Cc), the
 * line and paragraph separators, and letters that draw nothing (the braille blank, the Hangul
 * fillers). Text an agent or a pane controls can use them to make one thing read as
 * another, for example a command that displays backwards, so the app shows them instead of obeying them.
 *
 * @param keep characters to leave as they are, such as a tab.
 */
fun visibleControls(
    text: String,
    keep: Set<Char> = setOf('\t'),
): String {
    if (text.none { it !in keep && isHidden(it.code) }) return text
    val out = StringBuilder(text.length + ESCAPE_ROOM)
    var i = 0
    while (i < text.length) {
        val codePoint = text.codePointAt(i)
        val single = Character.charCount(codePoint) == 1
        if (isHidden(codePoint) && !(single && text[i] in keep)) {
            out.append("<U+").append("%04X".format(codePoint)).append('>')
        } else {
            out.appendCodePoint(codePoint)
        }
        i += Character.charCount(codePoint)
    }
    return out.toString()
}

private fun isHidden(codePoint: Int): Boolean =
    codePoint in BLANK_LETTERS ||
        when (Character.getType(codePoint).toByte()) {
            Character.FORMAT, Character.CONTROL, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
            else -> false
        }

// Characters that draw nothing but are not format characters, so they pad a line invisibly: the braille
// blank and the Hangul fillers.
private val BLANK_LETTERS = setOf(0x2800, 0x3164, 0x115F, 0x1160, 0xFFA0)

private const val ESCAPE_ROOM = 16
