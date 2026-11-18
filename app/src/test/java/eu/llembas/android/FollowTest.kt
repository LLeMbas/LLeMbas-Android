package eu.llembas.android

import eu.llembas.android.api.Message
import eu.llembas.android.ui.chat.TranscriptChange
import eu.llembas.android.ui.chat.followsAt
import eu.llembas.android.ui.chat.keptRow
import eu.llembas.android.ui.chat.movesToEnd
import eu.llembas.android.ui.chat.transcriptChange
import eu.llembas.android.ui.chat.yielding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The view follows a reply only for a person who is at the end; scrolled away, it stays where
 * they are, whatever the transcript does; back at the end, it follows again.
 */
class FollowTest {
    private fun person(id: String) = Message(id = id, role = "user", text = "A question")

    private fun reply(id: String, text: String = "") = Message(id = id, role = "assistant", text = text)

    private fun note(id: String) = Message(id = id, role = "user", machine = true, text = "A task finished")

    private val talk = listOf(person("q1"), reply("a1"), person("q2"), reply("a2"))

    @Test
    fun aChatOpenedGoesToItsEnd() {
        assertEquals(TranscriptChange.Opened, transcriptChange(null, talk))
        assertEquals(TranscriptChange.Opened, transcriptChange(emptyList(), talk))
        // Another chat in the same place is opened too: nothing of the old one is left.
        assertEquals(TranscriptChange.Opened, transcriptChange(talk, listOf(person("x1"), reply("y1"))))
        assertTrue(movesToEnd(TranscriptChange.Opened, following = false))
    }

    @Test
    fun aRotationKeepsThePlaceOfAPersonWhoScrolledAway() {
        val change = transcriptChange(null, talk, restored = true)
        assertEquals(TranscriptChange.Changed, change)
        assertFalse(movesToEnd(change, following = false))
        assertTrue(movesToEnd(change, following = true))
    }

    @Test
    fun aMessageSentAlwaysShows() {
        val change = transcriptChange(talk, talk + person("q3") + reply("a3"), sent = "q3")
        assertEquals(TranscriptChange.Sent, change)
        assertTrue(movesToEnd(change, following = false))
        // A prompt queued behind a running reply: the person's row alone.
        assertEquals(TranscriptChange.Sent, transcriptChange(talk, talk + person("q3"), sent = "q3"))
    }

    @Test
    fun aMessageWrittenElsewhereDoesNotDragAReaderDown() {
        // The person's row arrives from the page or another device: this app did not send it.
        val elsewhere = transcriptChange(talk, talk + person("q3") + reply("a3"))
        assertEquals(TranscriptChange.Appended, elsewhere)
        assertFalse(movesToEnd(elsewhere, following = false))
        assertTrue(movesToEnd(elsewhere, following = true))
        // Nor is it "sent" because this app sent something earlier.
        assertEquals(TranscriptChange.Appended, transcriptChange(talk, talk + person("q3"), sent = "q2"))
    }

    @Test
    fun aScrollAFingerInterruptsDoesNotEndTheFollower() = runTest {
        // Compose ends a scroll of ours by cancelling it: the follower goes on.
        var after = false
        yielding { throw CancellationException("a finger took the list") }
        after = true
        assertTrue(after)
        // The follower itself cancelled: it ends.
        var went = false
        val follower = launch(start = CoroutineStart.UNDISPATCHED) {
            yielding { awaitCancellation() }
            went = true
        }
        follower.cancelAndJoin()
        assertFalse(went)
    }

    @Test
    fun aReplyTheQueueStartedDoesNotDragAReaderDown() {
        // The transcript read again after `done` with a `next`: the queued prompt was there, its reply is new.
        val queued = talk + person("q3")
        val change = transcriptChange(queued, queued + reply("a3"))
        assertEquals(TranscriptChange.Appended, change)
        assertFalse(movesToEnd(change, following = false))
        assertTrue(movesToEnd(change, following = true))
    }

    @Test
    fun theInstancesOwnLineIsNotThePersonSending() {
        val change = transcriptChange(talk, talk + note("n1") + reply("a3"))
        assertEquals(TranscriptChange.Appended, change)
        assertFalse(movesToEnd(change, following = false))
    }

    @Test
    fun anOlderPageNeverMovesToTheEnd() {
        val older = listOf(person("q0"), reply("a0"))
        val change = transcriptChange(talk, older + talk)
        assertEquals(TranscriptChange.Older, change)
        assertFalse(movesToEnd(change, following = true))
        assertFalse(movesToEnd(change, following = false))
        // The row that was first keeps its place.
        assertEquals(2, keptRow(talk, older + talk))
        assertNull(keptRow(talk, older))
        assertNull(keptRow(emptyList(), talk))
    }

    @Test
    fun theFinishedReplyInPlaceOfTheWrittenOneFollowsOnlyAtTheEnd() {
        val done = talk.dropLast(1) + reply("a2", text = "The whole answer.")
        val change = transcriptChange(talk, done)
        assertEquals(TranscriptChange.Changed, change)
        assertFalse(movesToEnd(change, following = false))
        assertTrue(movesToEnd(change, following = true))
    }

    @Test
    fun aReplyMadeAgainIsNotAMessageSent() {
        // Regenerate puts a new row where the old reply was; an edit cuts the turns after it.
        assertEquals(TranscriptChange.Appended, transcriptChange(talk, talk.dropLast(1) + reply("a2b")))
        assertEquals(TranscriptChange.Appended, transcriptChange(talk, talk.take(1) + reply("a1b")))
        assertEquals(TranscriptChange.Changed, transcriptChange(talk, talk.take(2)))
    }

    @Test
    fun nothingChangedMovesNothing() {
        assertEquals(TranscriptChange.None, transcriptChange(talk, talk.toList()))
        assertEquals(TranscriptChange.None, transcriptChange(talk, emptyList()))
        assertEquals(TranscriptChange.None, transcriptChange(null, emptyList()))
        assertFalse(movesToEnd(TranscriptChange.None, following = true))
    }

    @Test
    fun theEndIsTakenUpAgainWhereTheScrollCameToRest() {
        assertTrue(followsAt(distance = 0, slack = 96))
        // A line that arrived while the list was settling is still the end.
        assertTrue(followsAt(distance = 96, slack = 96))
        assertFalse(followsAt(distance = 97, slack = 96))
        // The last row not on screen at all.
        assertFalse(followsAt(distance = Int.MAX_VALUE, slack = 96))
        // Overscrolled past the end, or a list shorter than its window.
        assertTrue(followsAt(distance = -24, slack = 96))
    }
}
