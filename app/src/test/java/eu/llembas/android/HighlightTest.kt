package eu.llembas.android

import eu.llembas.android.ui.TokenKind
import eu.llembas.android.ui.highlights
import eu.llembas.android.ui.tokenize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightTest {
    /** Each token as its text and kind, for reading a test at a glance. */
    private fun read(text: String, language: String): List<Pair<String, TokenKind>> =
        tokenize(text, language).map { text.substring(it.start, it.end) to it.kind }

    @Test
    fun kotlinKeywordsStringsCommentsNumbersAndTypes() {
        val got = read("val x: Int = 42 // the answer\nfun f() = \"a \\\" b\"", "kotlin")
        assertTrue("val" to TokenKind.Keyword in got)
        assertTrue("Int" to TokenKind.Type in got)
        assertTrue("42" to TokenKind.Number in got)
        assertTrue("// the answer" to TokenKind.Comment in got)
        assertTrue("fun" to TokenKind.Keyword in got)
        // An escaped quote does not end the string.
        assertTrue("\"a \\\" b\"" to TokenKind.Text in got)
    }

    @Test
    fun aBlockCommentRunsAcrossLines() {
        val text = "/* one\n two */ int x;"
        val got = read(text, "c")
        assertEquals("/* one\n two */" to TokenKind.Comment, got.first())
        assertTrue("int" to TokenKind.Keyword in got)
    }

    @Test
    fun pythonTripleQuotesAndHashComments() {
        val got = read("def f():\n    \"\"\"Doc\n    string\"\"\"\n    return None  # nothing", "py")
        assertTrue("def" to TokenKind.Keyword in got)
        assertTrue("\"\"\"Doc\n    string\"\"\"" to TokenKind.Text in got)
        assertTrue("None" to TokenKind.Keyword in got)
        assertTrue("# nothing" to TokenKind.Comment in got)
    }

    @Test
    fun anUnclosedQuoteColoursOneLineNotTheFile() {
        val got = read("echo don't\nls -la", "bash")
        val string = got.first { it.second == TokenKind.Text }
        assertFalse(string.first.contains('\n'))
    }

    @Test
    fun sqlIgnoresCaseAndShellHasVariables() {
        assertTrue("SELECT" to TokenKind.Keyword in read("SELECT * FROM t -- all", "sql"))
        assertTrue("-- all" to TokenKind.Comment in read("SELECT * FROM t -- all", "sql"))
        assertTrue("\$HOME" to TokenKind.Attribute in read("cd \$HOME", "sh"))
    }

    @Test
    fun markupTagsAttributesAndValues() {
        val got = read("<!-- note -->\n<a href=\"x\">don't</a>", "xml")
        assertEquals("<!-- note -->" to TokenKind.Comment, got.first())
        assertTrue("a" to TokenKind.Tag in got)
        assertTrue("href" to TokenKind.Attribute in got)
        assertTrue("\"x\"" to TokenKind.Text in got)
        // Prose between tags is not a string.
        assertFalse(got.any { it.first.startsWith("'") })
    }

    @Test
    fun yamlKeys() {
        val got = read("name: lembas\ncount: 3\n# end", "yaml")
        assertTrue("name" to TokenKind.Type in got)
        assertTrue("3" to TokenKind.Number in got)
        assertTrue("# end" to TokenKind.Comment in got)
    }

    @Test
    fun anUnknownLanguageIsPlain() {
        assertFalse(highlights("brainfuck"))
        assertTrue(tokenize("anything at all", "").isEmpty())
        assertTrue(highlights("TypeScript"))
        assertTrue(highlights("c++"))
    }
}
