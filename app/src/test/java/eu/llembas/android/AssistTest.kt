package eu.llembas.android

import eu.llembas.android.api.LiveReply
import eu.llembas.android.api.Step
import eu.llembas.android.api.Tail
import eu.llembas.android.assist.AssistController
import eu.llembas.android.assist.AssistTurn
import eu.llembas.android.assist.Recorder
import eu.llembas.android.assist.SheetStop
import eu.llembas.android.assist.settle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistTest {
    private val half = 1000f
    private val full = 1800f

    @Test
    fun aSlowReleaseGoesToTheNearestStop() {
        assertEquals(SheetStop.Half, settle(1100f, half, full, 0f))
        assertEquals(SheetStop.Full, settle(1500f, half, full, 0f))
        assertEquals(SheetStop.Half, settle(800f, half, full, 0f))
        // Pulled well below the half: it goes.
        assertEquals(SheetStop.Gone, settle(500f, half, full, 0f))
    }

    @Test
    fun aFlingDecidesByItsDirection() {
        assertEquals(SheetStop.Full, settle(1010f, half, full, -3000f))
        // Down from the full height: to the half, not away.
        assertEquals(SheetStop.Half, settle(1700f, half, full, 3000f))
        // Down from the half: away.
        assertEquals(SheetStop.Gone, settle(990f, half, full, 3000f))
    }

    @Test
    fun aTurnShowsWhatIsWrittenThenWhatWasWritten() {
        val asked = AssistTurn("Why?")
        assertFalse(asked.writing)
        val writing = asked.copy(reply = LiveReply("m1", tail = Tail(text = "Because")))
        assertTrue(writing.writing)
        assertEquals("Because", writing.steps.single().markdown)
        val earlier = AssistTurn("Why?", earlier = listOf(Step(kind = "text", markdown = "Because.")))
        assertFalse(earlier.writing)
        assertEquals("Because.", earlier.steps.single().markdown)
    }

    @Test
    fun aVoiceReadsOnlyTheWords() {
        val steps = listOf(
            Step(kind = "thinking", text = "Hmm."),
            Step(kind = "text", markdown = "**Two** days."),
            Step(kind = "tools"),
            Step(kind = "text", markdown = "See [this](https://example.org)."),
        )
        assertEquals("Two days. See this.", AssistController.spokenWords(steps))
    }

    @Test
    fun silenceIsQuietAndAToneIsLoud() {
        assertEquals(0f, Recorder.rms(ByteArray(3200), 3200), 0f)
        // A full-scale square wave: every sample at +-32767.
        val loud = ByteArray(3200) { i ->
            if (i % 2 == 0) {
                0xFF.toByte()
            } else if ((i / 2) % 2 == 0) {
                0x7F
            } else {
                0x80.toByte()
            }
        }
        assertTrue(Recorder.rms(loud, loud.size) > 0.9f)
    }
}
