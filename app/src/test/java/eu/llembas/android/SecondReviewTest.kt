package eu.llembas.android

import eu.llembas.android.api.Chat
import eu.llembas.android.api.ChatDetail
import eu.llembas.android.api.ChatPage
import eu.llembas.android.api.Frame
import eu.llembas.android.api.LiveReply
import eu.llembas.android.api.Message
import eu.llembas.android.api.Outcome
import eu.llembas.android.api.ReplyFrame
import eu.llembas.android.api.Step
import eu.llembas.android.api.reopened
import eu.llembas.android.api.replyUnrecorded
import eu.llembas.android.api.unrecordedReplies
import eu.llembas.android.ui.chat.ChatState
import eu.llembas.android.ui.chat.KEPT_MOST
import eu.llembas.android.ui.chat.KEPT_ROWS
import eu.llembas.android.ui.chat.Reads
import eu.llembas.android.ui.chat.TranscriptChange
import eu.llembas.android.ui.chat.asksFor
import eu.llembas.android.ui.chat.capped
import eu.llembas.android.ui.chat.movesToEnd
import eu.llembas.android.ui.chat.ownScroll
import eu.llembas.android.ui.chat.personScrolls
import eu.llembas.android.ui.chat.restFollows
import eu.llembas.android.ui.chat.scrollWasPersons
import eu.llembas.android.ui.chat.transcriptChange
import eu.llembas.android.ui.chat.withNewest
import eu.llembas.android.ui.home.ChatsState
import eu.llembas.android.ui.home.ReplyEnds
import eu.llembas.android.ui.home.Soon
import eu.llembas.android.ui.home.ended
import eu.llembas.android.ui.home.readsFor
import eu.llembas.android.ui.home.runningPause
import eu.llembas.android.ui.home.withNewest
import eu.llembas.android.ui.scheduled.listens
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * What a second review found, each as the rule it became: the list of chats is merged by id, a
 * reply nobody asked for is not followed behind a lock, a long transcript is held to a size, a
 * list the person is scrolling is theirs, the list of chats is read when it is seen and no
 * oftener than needed, a reply's end is marked without a read, and several pieces of one news
 * are one read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SecondReviewTest {
    // --- The list of chats, read again -----------------------------------------------------------

    private val noon = OffsetDateTime.of(2026, 1, 2, 12, 0, 0, 0, ZoneOffset.UTC)

    /** The chat [n]: the higher its number, the longer ago it changed -- the list's own order. */
    private fun row(n: Int, changed: Int = n, running: Boolean = false, pinned: Boolean = false) =
        Chat(id = "c$n", title = "Chat $n", running = running, pinned = pinned, updatedAt = noon.minusMinutes(changed.toLong()).toString())

    private fun rows(range: IntRange) = range.map { row(it) }

    @Test
    fun aChatThatComesUpFromALaterPageTakesNoOtherChatWithIt() {
        // Three pages loaded; a reply starts in a chat of the last one, which goes to the top.
        val shown = ChatsState(chats = rows(1..90), more = false)
        val page = ChatPage(chats = listOf(row(80, changed = 0, running = true)) + rows(1..49), more = true)
        val read = shown.withNewest(page)
        assertEquals(listOf("c80") + (1..79).map { "c$it" } + (81..90).map { "c$it" }, read.chats.map { it.id })
        assertTrue(read.chats.first().running)
        // "More" still asks for what comes after the oldest chat shown, and there was none.
        assertEquals("c90", read.chats.last().id)
        assertFalse(read.more)
    }

    @Test
    fun aPinnedChatFurtherDownStaysAndTheNewestPageSaysWhatItIsNow() {
        val shown = ChatsState(chats = rows(1..59) + row(60, pinned = true) + rows(61..70), more = true)
        // Pinned elsewhere meanwhile: the page's own row says so.
        val page = ChatPage(chats = listOf(row(3, changed = 0, pinned = true)) + rows(1..2) + rows(4..50), more = true)
        val read = shown.withNewest(page)
        assertEquals(70, read.chats.size)
        assertEquals(70, read.chats.map { it.id }.toSet().size)
        assertTrue(read.chats.first { it.id == "c60" }.pinned)
        assertTrue(read.chats.first { it.id == "c3" }.pinned)
        assertEquals("c3", read.chats.first().id)
        assertTrue(read.more)
    }

    @Test
    fun aChatDeletedElsewhereGoesFromTheList() {
        val shown = ChatsState(chats = rows(1..70), more = true)
        // c7 is gone on the server: the page reaches one chat further down instead.
        val page = ChatPage(chats = rows(1..6) + rows(8..51), more = true)
        val read = shown.withNewest(page)
        assertFalse(read.chats.any { it.id == "c7" })
        // What lies below the page was not looked at, and stays.
        assertEquals((1..6).map { "c$it" } + (8..70).map { "c$it" }, read.chats.map { it.id })
        // A page with nothing after it is the whole list: whatever it does not name is gone.
        val whole = shown.withNewest(ChatPage(chats = rows(1..3), more = false))
        assertEquals(listOf("c1", "c2", "c3"), whole.chats.map { it.id })
        assertFalse(whole.more)
        // A row with no time the app can read is never taken for gone.
        val undated = ChatsState(chats = rows(1..50) + Chat(id = "x", updatedAt = "yesterday"), more = true)
        assertEquals("x", undated.withNewest(ChatPage(chats = rows(1..50), more = true)).chats.last().id)
    }

    // --- A reply nobody asked for, and the gate ---------------------------------------------------

    private class Door {
        var locked = false
        val gate = InstanceGate { locked }.also { it.activityStarted() }

        fun lock() {
            locked = true
            gate.look()
        }

        fun unlock() {
            locked = false
            gate.look()
        }
    }

    @Test
    fun aReplyNobodyAskedForIsLetGoWhenThePhoneLocks() = runTest {
        val door = Door()
        var open = false
        val following = async {
            door.gate.whileOpen<String> {
                open = true
                try {
                    awaitCancellation()
                } finally {
                    open = false
                }
            }
        }
        runCurrent()
        assertTrue(open)
        door.lock()
        runCurrent()
        // Let go: its stream is closed, and the answer says so.
        assertFalse(open)
        assertNull(following.await())
    }

    @Test
    fun aReplyThatEndsInFrontIsFollowedToItsEnd() = runTest {
        val door = Door()
        assertEquals("done", door.gate.whileOpen { "done" })
        // The app leaving the front is the gate closing as well.
        door.gate.activityStopped()
        var started = false
        assertNull(
            door.gate.whileOpen {
                started = true
                "done"
            },
        )
        // Behind the gate nothing nobody asked for is even started.
        assertFalse(started)
    }

    @Test
    fun thePersonsOwnReplyGoesOnBehindTheLock() = runTest {
        val door = Door()
        val end = CompletableDeferred<String>()
        var own = true
        val following = async { door.gate.whileOpen(stays = { own }) { end.await() } }
        runCurrent()
        door.lock()
        runCurrent()
        assertTrue(following.isActive)
        end.complete("done")
        assertEquals("done", following.await())
        // One that became the person's while it ran (they asked, the events route was first) stays too;
        // one that is not theirs goes at the next closing.
        own = false
        door.unlock()
        val other = async { door.gate.whileOpen<String>(stays = { own }) { awaitCancellation() } }
        runCurrent()
        door.lock()
        runCurrent()
        assertNull(other.await())
    }

    @Test
    fun aFollowThatIsCancelledIsNotTakenForOneLetGo() = runTest {
        val door = Door()
        var after = false
        val following = launch {
            door.gate.whileOpen<String> { awaitCancellation() }
            after = true
        }
        runCurrent()
        following.cancelAndJoin()
        // The caller's own end: nothing of "let go, read again on the return" runs.
        assertFalse(after)
    }

    @Test
    fun aBrokenStreamIsOpenedAgainOnlyAtItsTurn() = runTest {
        val door = Door()
        var opened = 0
        val frames = mutableListOf<Frame>()
        val following = launch {
            reopened(turn = { door.gate.pass() }) {
                opened++
                if (opened == 1) flowOf(Outcome.Event(Frame("tail", "{}")), Outcome.Dropped) else flowOf(Outcome.Event(Frame("close", "{}")))
            }.toList(frames)
        }
        runCurrent()
        assertEquals(1, opened)
        // The connection broke and the phone is locked: an hour later nothing has been asked.
        door.lock()
        advanceTimeBy(3_600_000)
        assertEquals(1, opened)
        door.unlock()
        advanceUntilIdle()
        assertEquals(2, opened)
        assertEquals(listOf("tail", "close"), frames.map { it.event })
        assertTrue(following.isCompleted)
    }

    // --- A transcript left open ------------------------------------------------------------------

    private fun person(id: String) = Message(id = id, role = "user", text = "How many cakes are left?")

    private fun reply(id: String, complete: Boolean = true) = Message(
        id = id,
        role = "assistant",
        complete = complete,
        steps = if (complete) listOf(Step(index = 0, kind = "text", markdown = "Seven.")) else emptyList(),
    )

    private fun note(id: String) = Message(id = id, role = "user", machine = true, origin = "harness", text = "The baker has finished.")

    private val chat = Chat(id = "chat1")

    private fun talk(range: IntRange) = range.map { if (it % 2 == 1) person("m$it") else reply("m$it") }

    @Test
    fun aLongTranscriptIsCutBackOnlyUnderAPersonAtItsEnd() {
        val long = ChatState(chat = chat, messages = talk(1..KEPT_MOST + 2), more = false)
        val cut = long.capped(atEnd = true)
        assertEquals(KEPT_ROWS, cut.messages.size)
        // The newest stay, and "older" reads the rest back.
        assertEquals("m${KEPT_MOST + 2}", cut.messages.last().id)
        assertEquals("m${KEPT_MOST + 2 - KEPT_ROWS + 1}", cut.messages.first().id)
        assertTrue(cut.more)
        // Somebody reading further up keeps every row.
        assertSame(long, long.capped(atEnd = false))
        // Under the limit nothing goes, so it is not cut at every read.
        val fits = ChatState(chat = chat, messages = talk(1..KEPT_MOST))
        assertSame(fits, fits.capped(atEnd = true))
        // The list keeps its rows by their ids: to it this is rows changed in place, and it stays at its end.
        assertEquals(TranscriptChange.None, transcriptChange(cut.messages, cut.messages))
        assertEquals(TranscriptChange.Changed, transcriptChange(long.messages, cut.messages))
    }

    @Test
    fun aRewindElsewhereLeavesNoRowTheServerNoLongerHas() {
        // 120 rows shown, of which the last 50 were the newest page. From another client the
        // chat is rewound to row 60 and answered again: 61..120 are gone, n1 and n2 are new.
        val shown = ChatState(chat = chat, messages = talk(1..120), more = true)
        val after = talk(13..60) + person("n1") + reply("n2")
        val read = shown.withNewest(ChatDetail(chat = chat, messages = after, more = true))
        // The page joins at m13: what is above it is older than anything a rewind that left m13 could touch.
        assertEquals(talk(1..12).map { it.id } + after.map { it.id }, read.messages.map { it.id })
        // Rewound to above everything the page holds: the page joins nothing here and is the transcript.
        val far = listOf(person("x1"), reply("x2"))
        assertEquals(far, shown.withNewest(ChatDetail(chat = chat, messages = far, more = false)).messages)
    }

    // --- Whose scroll it is ------------------------------------------------------------------------

    @Test
    fun aListThePersonIsScrollingIsNotMovedWhenRowsArrive() {
        // A wheel, a key or a screen reader moving the list: no finger, so the following has not
        // ended yet -- and rows that arrive now must not cancel that scroll.
        val wheel = personScrolls(inProgress = true, ownScrolls = 0)
        assertTrue(wheel)
        assertFalse(movesToEnd(TranscriptChange.Appended, following = true, personScrolling = wheel))
        assertFalse(movesToEnd(TranscriptChange.Changed, following = true, personScrolling = wheel))
        // A message the person sends, and a chat opened, still show.
        assertTrue(movesToEnd(TranscriptChange.Sent, following = false, personScrolling = wheel))
        assertTrue(movesToEnd(TranscriptChange.Opened, following = false, personScrolling = wheel))
        // The app's own scroll to the end, still under way when more arrives, is not the person's.
        assertFalse(personScrolls(inProgress = true, ownScrolls = 1))
        assertTrue(movesToEnd(TranscriptChange.Appended, following = true, personScrolling = false))
        // A list at rest is nobody's scroll.
        assertFalse(personScrolls(inProgress = false, ownScrolls = 0))
    }

    @Test
    fun aScrollTheAppDidNotStartIsThePersonsWhereItRests() {
        // Not a finger and not (by where it rests) a move back -- but nothing of the app's started it.
        assertTrue(scrollWasPersons(touched = false, wentBack = false, startedByApp = false))
        assertFalse(
            restFollows(following = true, byPerson = scrollWasPersons(touched = false, wentBack = false, startedByApp = false), distance = 900, slack = 48),
        )
        // The app's own, left short of the end by a line that arrived: nobody left.
        assertFalse(scrollWasPersons(touched = false, wentBack = false, startedByApp = true))
        assertTrue(restFollows(following = true, byPerson = false, distance = 900, slack = 48))
        // A finger or a move back on a scroll the app started is still the person's.
        assertTrue(scrollWasPersons(touched = true, wentBack = false, startedByApp = true))
        assertTrue(scrollWasPersons(touched = false, wentBack = true, startedByApp = true))
    }

    @Test
    fun theAppsOwnScrollsAreCountedWhileTheyRun() = runTest {
        val own = intArrayOf(0)
        val first = launch { ownScroll(own) { awaitCancellation() } }
        val second = launch { ownScroll(own) { awaitCancellation() } }
        runCurrent()
        assertEquals(2, own[0])
        // One overtaken by the other: its end does not unmark the one still running.
        first.cancelAndJoin()
        assertEquals(1, own[0])
        assertFalse(personScrolls(inProgress = true, ownScrolls = own[0]))
        second.cancelAndJoin()
        assertEquals(0, own[0])
    }

    // --- The drawer's list and the replies' frames -----------------------------------------------

    private fun said(chatId: String, message: String = "r1", live: Boolean = true) = ReplyFrame(chatId = chatId, messageId = message, live = live)

    @Test
    fun theListIsReadOnlyWhenItIsSeenAndOnlyForWhatItDoesNotShow() {
        val list = ChatsState(chats = listOf(row(1, running = true), row(2)), more = false)
        // The drawer is closed: it reads the list when it opens, and nothing before.
        assertFalse(list.readsFor(said("c2"), shown = false))
        // Open: a reply started in a chat not shown as running, or in one not listed yet.
        assertTrue(list.readsFor(said("c2"), shown = true))
        assertTrue(list.readsFor(said("c9"), shown = true))
        // The route says every reply being written again at each reconnection: that row runs already.
        assertFalse(list.readsFor(said("c1"), shown = true))
        // An end in a listed chat is marked on its row; one in a chat not listed is read.
        assertFalse(list.readsFor(said("c1", live = false), shown = true))
        assertTrue(list.readsFor(said("c9", live = false), shown = true))
        // A list not read yet is left to its first read.
        assertFalse(ChatsState().readsFor(said("c1"), shown = true))
    }

    @Test
    fun aReplysEndClearsTheRunningMarkAndShowsSomethingNewWithoutARead() {
        val list = ChatsState(chats = listOf(row(1, running = true), row(2, running = true), row(3)), more = false)
        val ended = list.ended(said("c1", live = false), open = "c2")
        assertFalse(ended.chats[0].running)
        assertTrue(ended.chats[0].unread)
        // The others are as they were.
        assertEquals(list.chats.drop(1), ended.chats.drop(1))
        // The chat on screen: the person watched it end, so nothing is new.
        val watched = list.ended(said("c2", live = false), open = "c2")
        assertFalse(watched.chats[1].running)
        assertFalse(watched.chats[1].unread)
        // A start is not an end, and a chat not listed changes nothing.
        assertSame(list, list.ended(said("c1"), open = null))
        assertSame(list, list.ended(said("c9", live = false), open = null))
    }

    @Test
    fun anInstanceProvesItSaysTheEndsWithOneEndOfAReplyItSaidStarted() {
        val ends = ReplyEnds()
        // "Already ended" at the start, which instances before the end frame say too, proves nothing.
        ends.hear(said("c1", "r0", live = false))
        assertFalse(ends.said)
        ends.hear(said("c1", "r1"))
        assertFalse(ends.said)
        ends.hear(said("c1", "r1", live = false))
        assertTrue(ends.said)
    }

    @Test
    fun aRunningRowIsReadAgainAtABoundedRate() {
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L), (0..5).map { runningPause(it) })
        assertEquals(60_000L, runningPause(1_000))
        assertEquals(5_000L, runningPause(-1))
    }

    @Test
    fun framesThatNeverFallQuietStillGetTheirRead() = runTest {
        var reads = 0
        val soon = Soon(backgroundScope, pause = 1_500, most = 6_000) { reads++ }
        // A team: a reply starts every second, for nineteen seconds.
        repeat(19) {
            soon.ask()
            advanceTimeBy(1_000)
        }
        runCurrent()
        // At the latest six seconds after the first asking, and again six after the next: never "not until it is quiet".
        assertEquals(2, reads)
        // And once more when it has fallen quiet.
        advanceTimeBy(1_600)
        runCurrent()
        assertEquals(3, reads)
        // Nothing asked, nothing read.
        advanceTimeBy(60_000)
        assertEquals(3, reads)
    }

    @Test
    fun askingsCloseTogetherAreOneRead() = runTest {
        var reads = 0
        val soon = Soon(backgroundScope, pause = 1_500, most = 6_000) { reads++ }
        repeat(5) {
            soon.ask()
            advanceTimeBy(200)
        }
        assertEquals(0, reads)
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(1, reads)
    }

    @Test
    fun aReadUnderWayIsNotCutShortByTheNextFrame() = runTest {
        var began = 0
        var finished = 0
        val soon = Soon(backgroundScope, pause = 1_500, most = 6_000) {
            began++
            delay(5_000)
            finished++
        }
        soon.ask()
        advanceTimeBy(1_600)
        assertEquals(1, began)
        // Frames while the list is being read: that read finishes, and one more follows it.
        soon.ask()
        advanceTimeBy(1_000)
        soon.ask()
        advanceTimeBy(4_100)
        assertEquals(1, finished)
        advanceTimeBy(1_600)
        assertEquals(2, began)
        advanceTimeBy(5_100)
        assertEquals(2, finished)
        advanceTimeBy(60_000)
        assertEquals(2, began)
    }

    @Test
    fun whatIsAskedWhileAReadWaitsForItsTurnIsThatRead() = runTest {
        val turn = CompletableDeferred<Unit>()
        var reads = 0
        val soon = Soon(backgroundScope, pause = 1_500, most = 6_000, before = { turn.await() }) { reads++ }
        soon.ask()
        advanceTimeBy(1_600)
        // Waiting at the gate (the phone is locked): more is heard meanwhile.
        soon.ask()
        soon.ask()
        turn.complete(Unit)
        advanceTimeBy(60_000)
        // One read on the return, not one per frame and not two.
        assertEquals(1, reads)
        // The drawer closed before the pause was over: nothing is read for it.
        soon.ask()
        soon.forget()
        advanceTimeBy(60_000)
        assertEquals(1, reads)
    }

    // --- A reply's end, and one news read once -----------------------------------------------------

    @Test
    fun theEndOfAReplyThisScreenFollowsOrHasDrawnAsksForNoRead() {
        val following = ChatState(chat = chat, messages = listOf(person("q1"), reply("a1", complete = false)), live = LiveReply("a1"))
        // The stream's own `done` brings the end: the events route's word for it costs nothing.
        assertFalse(following.asksFor(said("chat1", "a1", live = false)))
        val drawn = ChatState(chat = chat, messages = listOf(person("q1"), reply("a1")))
        assertFalse(drawn.asksFor(said("chat1", "a1", live = false)))
        // The end of a reply this screen never heard of (it was shorter than the news of it) is read once.
        assertTrue(drawn.asksFor(said("chat1", "a2", live = false)))
    }

    @Test
    fun aBurstOfNewsIsOneRead() = runTest {
        val reads = Reads()
        var made = 0
        val answer = CompletableDeferred<Unit>()
        // Five frames before anything was read.
        val asked = List(5) { reads.mark() }.map { mark ->
            launch {
                reads.since(mark) {
                    made++
                    answer.await()
                    true
                }
            }
        }
        runCurrent()
        assertEquals(1, made)
        // News that arrives while that read is under way is newer than it.
        val later = reads.mark()
        answer.complete(Unit)
        advanceUntilIdle()
        assertTrue(asked.all { it.isCompleted })
        assertEquals(1, made)
        assertTrue(reads.since(later) { true })
        // And what was learned before a read that has answered is not read again.
        assertFalse(reads.since(0) { true })
    }

    @Test
    fun aReadThatFailedStandsForNobodyAndOneNoLongerWantedIsNotMade() = runTest {
        val reads = Reads()
        val first = reads.mark()
        val second = reads.mark()
        assertTrue(reads.since(first) { false })
        // The first one's read got no answer: the second still makes its own.
        var made = false
        assertTrue(
            reads.since(second) {
                made = true
                true
            },
        )
        assertTrue(made)
        // Asked again when its turn comes: no longer news, no read.
        assertFalse(reads.since(reads.mark(), wanted = { false }) { error("not made") })
    }

    // --- The line that says a reply was not recorded -----------------------------------------------

    @Test
    fun aReplyThatHoldsOnlyAPlanWasRecorded() {
        val empty = Message(id = "a1", role = "assistant")
        assertTrue(replyUnrecorded(empty, note("n1")))
        val plan = empty.copy(plan = JsonObject(mapOf("title" to JsonPrimitive("Bake more"))))
        assertFalse(replyUnrecorded(plan, note("n1")))
        assertEquals(emptySet<String>(), unrecordedReplies(listOf(note("n1"), plan)))
    }

    @Test
    fun theReplyBeingWrittenIsNeverNotRecordedWhateverItsRowSays() {
        // A row that says nothing of `complete` reads as complete; the screen knows better.
        val row = Message(id = "a1", role = "assistant")
        assertFalse(replyUnrecorded(row, note("n1"), live = "a1"))
        assertEquals(emptySet<String>(), unrecordedReplies(listOf(note("n1"), row), live = "a1"))
        // Once it has ended, or for another row, the rule is as it was.
        assertEquals(setOf("a1"), unrecordedReplies(listOf(note("n1"), row), live = "a9"))
        assertEquals(setOf("a1"), unrecordedReplies(listOf(note("n1"), row)))
    }

    // --- The Scheduled list ------------------------------------------------------------------------

    @Test
    fun theScheduledListListensOnlyWhileItIsInFront() {
        assertTrue(listens(inFront = true, listening = false))
        // A read that comes back after the screen was left opens nothing.
        assertFalse(listens(inFront = false, listening = false))
        assertFalse(listens(inFront = true, listening = true))
    }
}
