package ai.tatawur.rafiq.sessions

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class PreviewLineTest {
    // A real reply started like this, and the row showed every hash and asterisk.
    @Test
    fun markdown_syntax_is_stripped_and_whitespace_collapsed() {
        val markdown =
            """
            # How Git Branches Work
            ## What a Branch *Really* Is
            - A **pointer** to a commit, see [the docs](https://git-scm.com/docs)
            > Use `git switch`, not ~~checkout~~.
            1. Keep snake_case and 2*3 as written
            """.trimIndent()

        assertEquals(
            "How Git Branches Work What a Branch Really Is A pointer to a commit, see the docs " +
                "Use git switch, not checkout. Keep snake_case and 2*3 as written",
            previewLine(markdown),
        )
    }
}
