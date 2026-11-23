package eu.llembas.android

import eu.llembas.android.api.Chat
import eu.llembas.android.api.ChatDetail
import eu.llembas.android.api.ChatPage
import eu.llembas.android.api.Drawn
import eu.llembas.android.api.EventsHub
import eu.llembas.android.api.Frame
import eu.llembas.android.api.LembasJson
import eu.llembas.android.api.LiveReply
import eu.llembas.android.api.Message
import eu.llembas.android.api.MessageEnd
import eu.llembas.android.api.ReplyFrame
import eu.llembas.android.api.Step
import eu.llembas.android.api.drawn
import eu.llembas.android.api.replyUnrecorded
import eu.llembas.android.api.unrecordedReplies
import eu.llembas.android.ui.chat.ChatState
import eu.llembas.android.ui.chat.TranscriptChange
import eu.llembas.android.ui.chat.asksFor
import eu.llembas.android.ui.chat.movesToEnd
import eu.llembas.android.ui.chat.pictureRatio
import eu.llembas.android.ui.chat.restFollows
import eu.llembas.android.ui.chat.transcriptChange
import eu.llembas.android.ui.chat.wentBack
import eu.llembas.android.ui.chat.withNewest
import eu.llembas.android.ui.home.ChatsState
import eu.llembas.android.ui.home.withNewest
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A reply this app did not start (the events route's `reply` frame, `contract/event_reply.json`):
 * which frames are news to an open chat, what reading the chat again keeps, how a turn nobody
 * typed and its reply are drawn -- and where the transcript stands through all of it.
 */
class ReplyEventTest {
    private fun text(name: String): String = requireNotNull(javaClass.classLoader!!.getResource("$name.json")) { "contract/$name.json is missing" }.readText()

    private fun person(id: String) = Message(id = id, role = "user", text = "How many cakes are left?")

    private fun reply(id: String, complete: Boolean = true) = Message(
        id = id,
        role = "assistant",
        complete = complete,
        steps = if (complete) listOf(Step(index = 0, kind = "text", markdown = "Seven.")) else emptyList(),
    )

    private fun note(id: String, origin: String = "") = Message(id = id, role = "user", machine = true, origin = origin, text = "The baker has finished.")

    private val chat = Chat(id = "chat1")

    private fun frame(message: String, chatId: String = "chat1", live: Boolean = true) = ReplyFrame(chatId = chatId, messageId = message, live = live)

    @Test
    fun theFrameIsReadAsTheServerWritesIt() {
        val said = LembasJson.decodeFromString(ReplyFrame.serializer(), text("event_reply"))
        assertEquals(ReplyFrame(chatId = "chat1", messageId = "msg2", live = true), said)
        // A frame with more in it than this app knows is still read; one with less says nothing is live.
        assertEquals(frame("m9", live = false), LembasJson.decodeFromString(ReplyFrame.serializer(), """{"chat_id":"chat1","message_id":"m9","kind":"wake"}"""))
    }

    @Test
    fun onlyAReplyThisScreenDoesNotHoldIsNews() {
        val open = ChatState(chat = chat, messages = listOf(person("q1"), reply("a1")))
        // A reply nobody asked for from here: news.
        assertTrue(open.asksFor(frame("a2")))
        // Said as already ended: the chat is still read, the row is not there yet.
        assertTrue(open.asksFor(frame("a2", live = false)))
        // Another chat's, or a frame that names no reply: not this screen's.
        assertFalse(open.asksFor(frame("a2", chatId = "chat2")))
        assertFalse(open.asksFor(frame("")))
        // A chat not made yet has no replies to hear of.
        assertFalse(ChatState().asksFor(frame("a2")))
        // The reply being followed -- this app's own, said back by the server: not news.
        val writing = open.copy(messages = open.messages + person("q2") + reply("a2", complete = false), live = LiveReply("a2"))
        assertFalse(writing.asksFor(frame("a2")))
        // One drawn as finished: not news either, however late its frame is heard.
        assertFalse(open.asksFor(frame("a1")))
        // A row that is there but was never finished and is followed by nobody (its stream was lost): news.
        val lost = open.copy(messages = open.messages + person("q2") + reply("a2", complete = false))
        assertTrue(lost.asksFor(frame("a2")))
        // While another reply is followed, the frame for the next one is still news -- it is taken up after.
        assertTrue(writing.asksFor(frame("a3")))
    }

    @Test
    fun readingTheChatAgainKeepsTheOlderPagesAPersonLoaded() {
        val rows = listOf(person("q1"), reply("a1"), person("q2"), reply("a2"), person("q3"), reply("a3"))
        // Two older pages were loaded: the transcript goes back to the chat's first message.
        val shown = ChatState(chat = chat, messages = rows, more = false)
        // The newest page as the server has it now: a turn nobody typed and its reply are new.
        val page = listOf(person("q3"), reply("a3"), note("n1", "harness"), reply("a4", complete = false))
        val read = shown.withNewest(ChatDetail(chat = chat.copy(title = "Waybread"), messages = page, more = true, live = "a4"))
        assertEquals(listOf("q1", "a1", "q2", "a2", "q3", "a3", "n1", "a4"), read.messages.map { it.id })
        // What is above is what this screen loaded, not what the page says of itself.
        assertFalse(read.more)
        assertEquals("Waybread", read.chat?.title)
        // The rows a reader looks at are the same rows, so the list keeps them where they are.
        assertEquals(TranscriptChange.Appended, transcriptChange(shown.messages, read.messages))

        // The page is the whole chat (nothing above it), or joins nothing shown: it replaces the transcript.
        val whole = shown.withNewest(ChatDetail(chat = chat, messages = page, more = false))
        assertEquals(page.map { it.id }, whole.messages.map { it.id })
        val elsewhere = listOf(person("x1"), reply("y1"))
        assertEquals(elsewhere, shown.withNewest(ChatDetail(chat = chat, messages = elsewhere, more = true)).messages)
        // Only the newest page was shown: it is replaced, with the server's word on what is above.
        val one = ChatState(chat = chat, messages = listOf(person("q3"), reply("a3")), more = true)
        assertEquals(ChatState(chat = chat, messages = page, more = true), one.withNewest(ChatDetail(chat = chat, messages = page, more = true)))
        // A rewind: the rows after the edited turn are gone on the server, and go here.
        val rewound = shown.withNewest(ChatDetail(chat = chat, messages = listOf(reply("a1"), person("q2"), reply("a5")), more = true))
        assertEquals(listOf("q1", "a1", "q2", "a5"), rewound.messages.map { it.id })
        // No row twice, whatever the page repeats of the rows above it.
        val repeating = shown.withNewest(ChatDetail(chat = chat, messages = listOf(person("q2"), reply("a2"), person("q1")), more = true))
        assertEquals(repeating.messages.map { it.id }.distinct(), repeating.messages.map { it.id })
    }

    @Test
    fun aReplyNobodyAskedForFollowsOnlyForAPersonAtTheEnd() {
        val before = listOf(person("q1"), reply("a1"))
        val after = before + note("n1", "harness") + reply("a2", complete = false)
        val change = transcriptChange(before, after, sent = "q1")
        assertEquals(TranscriptChange.Appended, change)
        assertTrue(movesToEnd(change, following = true))
        assertFalse(movesToEnd(change, following = false))
        // The stored reply in place of the one being written is the same row under the same key.
        val stored = before + note("n1", "harness") + reply("a2")
        assertEquals(after.map { it.id }, stored.map { it.id })
        assertEquals(TranscriptChange.Changed, transcriptChange(after, stored))
        assertFalse(movesToEnd(TranscriptChange.Changed, following = false))
    }

    @Test
    fun aReplyFrameIsNotToldAgainToAWatcherThatJoinsLater() = runTest {
        // The one connection, as a line whose frames wait for whoever reads it.
        val route = Channel<Frame>(Channel.UNLIMITED)
        val hub = EventsHub(backgroundScope)
        val open = { route.receiveAsFlow() }
        val drawer = Channel<Frame>(Channel.UNLIMITED)
        val screen = Channel<Frame>(Channel.UNLIMITED)
        val first = launch { hub.frames("instance", open).collect { drawer.send(it) } }
        val reports = Frame("reports", """{"unread":1}""")
        val started = Frame("reply", text("event_reply"))
        route.send(reports)
        route.send(started)
        assertEquals(reports, drawer.receive())
        assertEquals(started, drawer.receive())
        // A chat opened later hears the state it missed, and not the reply that started before it listened.
        val second = launch { hub.frames("instance", open).collect { screen.send(it) } }
        assertEquals(reports, screen.receive())
        val next = Frame("reply", """{"chat_id":"chat1","message_id":"msg3","live":true}""")
        route.send(next)
        assertEquals(next, screen.receive())
        assertEquals(next, drawer.receive())
        first.cancel()
        second.cancel()
    }

    @Test
    fun theListReadAgainMovesTheChatUpAndKeepsWhatWasLoadedBelow() {
        fun row(id: String, running: Boolean = false, unread: Boolean = false) = Chat(id = id, title = id, running = running, unread = unread)
        val shown = ChatsState(chats = listOf(row("c1"), row("c2"), row("c3"), row("c4"), row("c5")), more = true)
        // The first page is three rows; c4 and c5 were loaded with "more".
        val page = ChatPage(chats = listOf(row("c3", running = true), row("c1"), row("c2")), more = true)
        val read = shown.withNewest(page)
        assertEquals(listOf("c3", "c1", "c2", "c4", "c5"), read.chats.map { it.id })
        assertTrue(read.chats.first().running)
        assertTrue(read.more)
        // A new chat pushed the page's last row off it: that row is kept below, once.
        val pushed = shown.withNewest(ChatPage(chats = listOf(row("c0", unread = true), row("c1"), row("c2")), more = true))
        assertEquals(listOf("c0", "c1", "c2", "c3", "c4", "c5"), pushed.chats.map { it.id })
        // Only the page was shown, or nothing shown is on it: the page is the list.
        val short = ChatsState(chats = listOf(row("c1"), row("c2")), more = false)
        assertEquals(ChatsState(chats = page.chats, more = true), short.withNewest(page))
        val other = ChatPage(chats = listOf(row("x1")), more = false)
        assertEquals(ChatsState(chats = other.chats, more = false), shown.withNewest(other))
    }

    @Test
    fun aTurnTheComputersHarnessStartedIsNeverThePersonsBubble() {
        val harness = note("n1", "harness")
        assertEquals(Drawn.Note, drawn(harness, first = false, reportChat = false))
        // By its origin alone, should a server say it without `machine`.
        assertEquals(Drawn.Note, drawn(harness.copy(machine = false), first = false, reportChat = false))
        // Typed in the terminal: a person wrote it, and an origin this app does not know is nobody's mark.
        assertEquals(Drawn.Person, drawn(person("q1").copy(origin = "terminal"), first = false, reportChat = false))
        assertEquals(Drawn.Person, drawn(person("q1").copy(origin = "later"), first = false, reportChat = false))
    }

    @Test
    fun anEmptyFinishedReplyToATurnNobodyTypedSaysSoInOneLine() {
        val empty = Message(id = "a2", role = "assistant")
        assertTrue(replyUnrecorded(empty, note("n1", "harness")))
        assertTrue(replyUnrecorded(empty, note("n1")))
        // To something a person asked, an empty reply is just that.
        assertFalse(replyUnrecorded(empty, person("q1")))
        assertFalse(replyUnrecorded(empty, null))
        assertFalse(replyUnrecorded(empty, reply("a1")))
        // Still being written, stopped, failed, or holding anything at all: drawn as a reply.
        assertFalse(replyUnrecorded(empty.copy(complete = false), note("n1")))
        assertFalse(replyUnrecorded(empty.copy(stopped = true), note("n1")))
        assertFalse(replyUnrecorded(empty.copy(error = "The device did not answer."), note("n1")))
        assertFalse(replyUnrecorded(empty.copy(end = MessageEnd(phase = "stopped")), note("n1")))
        assertFalse(replyUnrecorded(empty.copy(text = "Seven."), note("n1")))
        assertFalse(replyUnrecorded(reply("a2"), note("n1")))
        // In a transcript: by the row before each reply.
        val rows = listOf(person("q1"), reply("a1"), note("n1", "harness"), empty, note("n2", "harness"), reply("a3"), person("q2"), empty.copy(id = "a4"))
        assertEquals(setOf("a2"), unrecordedReplies(rows))
        assertEquals(emptySet<String>(), unrecordedReplies(listOf(empty)))
    }

    @Test
    fun onlyThePersonsOwnScrollEndsTheFollowing() {
        // This app's scroll to the end, left short of it by a card that arrived meanwhile: still following.
        assertTrue(restFollows(following = true, byPerson = false, distance = 600, slack = 96))
        // The same rest after a finger: the person is reading above, and is left there.
        assertFalse(restFollows(following = true, byPerson = true, distance = 600, slack = 96))
        // Back at the end, however it got there: the following is taken up.
        assertTrue(restFollows(following = false, byPerson = true, distance = 40, slack = 96))
        assertTrue(restFollows(following = false, byPerson = false, distance = 0, slack = 96))
        // Not following and resting away from the end: nothing takes it up.
        assertFalse(restFollows(following = false, byPerson = false, distance = 600, slack = 96))
        // A list gone back towards its start was moved by a person (a wheel, a key, a reader's gesture).
        assertTrue(wentBack(fromIndex = 8, fromOffset = 0, toIndex = 6, toOffset = 120))
        assertTrue(wentBack(fromIndex = 8, fromOffset = 300, toIndex = 8, toOffset = 40))
        // Further down, or where it was: this app's own keeping to the end.
        assertFalse(wentBack(fromIndex = 8, fromOffset = 300, toIndex = 8, toOffset = 300))
        assertFalse(wentBack(fromIndex = 8, fromOffset = 300, toIndex = 9, toOffset = 0))
        assertFalse(wentBack(fromIndex = 8, fromOffset = 300, toIndex = 8, toOffset = 900))
    }

    @Test
    fun aPicturesPlaceHasItsShapeBeforeItsBytes() {
        assertEquals(1.6f, pictureRatio(1024, 640), 0.001f)
        assertEquals(0.75f, pictureRatio(768, 1024), 0.001f)
        // A size the server did not say: a square, not nothing.
        assertEquals(1f, pictureRatio(0, 0), 0f)
        assertEquals(1f, pictureRatio(1024, 0), 0f)
        // A strip is kept within what a phone can show.
        assertEquals(4f, pictureRatio(4000, 100), 0f)
        assertEquals(0.25f, pictureRatio(100, 4000), 0f)
    }
}
