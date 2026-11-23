package ai.tatawur.rafiq.client

/**
 * [text] with every invisible control made visible as `<U+XXXX>`: format characters (Unicode category Cf,
 * such as the right-to-left override U+202E and zero-width spaces), control characters (Cc), the
 * line and paragraph separators, and letters that draw nothing (the braille blank, the Hangul
 * fillers, the combining grapheme joiner, the Khmer inherent vowels and the Mongolian variation
 * selectors). Text an agent or a pane controls can use them to make one thing read as another, for
 * example a command that displays backwards, so the app shows them instead of obeying them.
 *
 * @param keep characters to leave as they are, such as a tab.
 * @param markVariationSelectors also mark the variation selectors U+FE00 to U+FE0F and U+E0100 to
 *   U+E01EF. On for commands, where they have no honest use. Off for window names and pane history,
 *   where they mostly pick how an emoji looks.
 */
fun visibleControls(
    text: String,
    keep: Set<Char> = setOf('\t'),
    markVariationSelectors: Boolean = false,
): String {
    val hidden = { codePoint: Int -> isHidden(codePoint) || (markVariationSelectors && isVariationSelector(codePoint)) }
    // By code point, so a hidden character outside the first plane is not missed.
    if (text.codePoints().noneMatch { it.toChar() !in keep && hidden(it) }) return text
    val out = StringBuilder(text.length + ESCAPE_ROOM)
    var i = 0
    while (i < text.length) {
        val codePoint = text.codePointAt(i)
        val single = Character.charCount(codePoint) == 1
        if (hidden(codePoint) && !(single && text[i] in keep)) {
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

private fun isVariationSelector(codePoint: Int): Boolean =
    codePoint in VARIATION_SELECTORS || codePoint in VARIATION_SELECTORS_SUPPLEMENT

// Characters that draw nothing but are not format characters, so they pad a line invisibly: the braille
// blank, the Hangul fillers, the combining grapheme joiner, the Khmer inherent vowels and the Mongolian
// free variation selectors.
private val BLANK_LETTERS =
    setOf(0x2800, 0x3164, 0x115F, 0x1160, 0xFFA0, 0x034F, 0x17B4, 0x17B5, 0x180B, 0x180C, 0x180D, 0x180F)

private val VARIATION_SELECTORS = 0xFE00..0xFE0F
private val VARIATION_SELECTORS_SUPPLEMENT = 0xE0100..0xE01EF

private const val ESCAPE_ROOM = 16
