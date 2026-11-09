package eu.llembas.android

import eu.llembas.android.ui.Segment
import eu.llembas.android.ui.splitFences
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownSplitTest {
    @Test
    fun proseOnlyIsOnePiece() {
        assertEquals(listOf(Segment.Prose("Hello *there*.")), splitFences("Hello *there*.\n"))
    }

    @Test
    fun aFenceBetweenProse() {
        val split = splitFences("Here is how:\n\n```sh\nbake --slow\n```\n\nDone.")
        assertEquals(
            listOf(Segment.Prose("Here is how:"), Segment.Code("sh", "bake --slow"), Segment.Prose("Done.")),
            split,
        )
    }

    @Test
    fun anUnclosedFenceRunsToTheEndWhileWritten() {
        val split = splitFences("Code:\n```kotlin\nval a = 1\nval b")
        assertEquals(listOf(Segment.Prose("Code:"), Segment.Code("kotlin", "val a = 1\nval b", closed = false)), split)
    }

    @Test
    fun aLongerFenceHoldsAShorterOne() {
        val split = splitFences("````md\n```\ninner\n```\n````")
        assertEquals(listOf(Segment.Code("md", "```\ninner\n```")), split)
    }

    @Test
    fun tildesAndNoLanguage() {
        assertEquals(listOf(Segment.Code("", "x")), splitFences("~~~\nx\n~~~"))
    }

    @Test
    fun aFenceInsideAListStaysProse() {
        val text = "1. Run it:\n\n    ```sh\n    make\n    ```"
        assertEquals(listOf(Segment.Prose(text)), splitFences(text))
    }

    @Test
    fun inlineBackticksAreNotAFence() {
        assertEquals(listOf(Segment.Prose("Use ``` to fence code.")), splitFences("Use ``` to fence code."))
    }
}
