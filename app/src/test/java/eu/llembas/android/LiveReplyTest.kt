package eu.llembas.android

import eu.llembas.android.api.LiveReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveReplyTest {
    @Test
    fun aFrameReplacesItsBlockNeverAppends() {
        val once = LiveReply("m").apply("tail", """{"thinking":"","text":"Lem","label":""}""")
        val twice = once.apply("tail", """{"thinking":"","text":"Lembas","label":""}""")
        assertEquals("Lembas", twice.tail.text)
        assertEquals(listOf("text"), twice.shown.map { it.kind })
    }

    @Test
    fun theOpenTailFollowsTheClosedSteps() {
        val live = LiveReply("m")
            .apply("steps", """{"steps":[{"index":0,"kind":"text","markdown":"One."}],"tail":{"thinking":"hm","text":"Two","label":"3s"}}""")
        assertEquals(listOf("text", "thinking", "text"), live.shown.map { it.kind })
        assertEquals(listOf(0, 1, 1), live.shown.map { it.index })
        assertTrue(live.shown.drop(1).all { it.open })
    }

    @Test
    fun anEmptyAskFrameClearsTheCard() {
        val asked = LiveReply("m").apply("ask", """{"cards":[{"id":"c","kind":"question","items":[{"key":"q0","kind":"question"}]}]}""")
        assertEquals(1, asked.cards.size)
        assertTrue(asked.apply("ask", """{"cards":[]}""").cards.isEmpty())
    }

    @Test
    fun anUnknownOrBrokenFrameChangesNothing() {
        val live = LiveReply("m").apply("status", """{"text":"Working"}""")
        assertEquals(live, live.apply("something-newer", "{}"))
        assertEquals(live, live.apply("status", "not json"))
    }

    @Test
    fun aDrainedQueueNamesTheNextReply() {
        val done = LiveReply("m").apply(
            "done",
            """{"message":{"id":"m","role":"assistant"},"chat":{"id":"c","title":"T"},"next":"m2"}""",
        )
        assertEquals("m2", done.next)
    }
}
