package eu.llembas.android

import eu.llembas.android.api.ApiClient
import eu.llembas.android.api.ApiException
import eu.llembas.android.api.Chat
import eu.llembas.android.api.ChatDetail
import eu.llembas.android.api.Discarded
import eu.llembas.android.api.Drawn
import eu.llembas.android.api.LembasJson
import eu.llembas.android.api.Message
import eu.llembas.android.api.Step
import eu.llembas.android.api.Turn
import eu.llembas.android.api.Waiting
import eu.llembas.android.api.drawn
import eu.llembas.android.api.editable
import eu.llembas.android.api.fateUnknown
import eu.llembas.android.api.waiting
import eu.llembas.android.ui.chat.ChatState
import eu.llembas.android.ui.chat.Followed
import eu.llembas.android.ui.chat.WaitingActions
import eu.llembas.android.ui.chat.discarded
import eu.llembas.android.ui.chat.followedAfterSend
import eu.llembas.android.ui.chat.sentNow
import eu.llembas.android.ui.chat.staleWaiting
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ConnectException

/**
 * A message that waits (`queued`: behind a reply, of unknown arrival at a linked computer, or
 * held by it) and the two things its person does with it: read as the server writes it
 * (`contract/message_unsure.json`, `message_carried.json`, `message_sent_now.json`,
 * `message_discarded.json`), sent now and its reply followed, discarded and gone, and refused
 * in the instance's own words with the chat read again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WaitingMessageTest {
    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader!!.getResource("$name.json")) { "contract/$name.json is missing" }.readText()

    private fun message(name: String) = LembasJson.decodeFromString(Message.serializer(), fixture(name))

    private fun person(id: String, queued: Boolean = false, origin: String = "") =
        Message(id = id, role = "user", text = "And the bread?", queued = queued, origin = origin)

    private fun reply(id: String, complete: Boolean = true) = Message(
        id = id,
        role = "assistant",
        complete = complete,
        steps = if (complete) listOf(Step(index = 0, kind = "text", markdown = "Seven.")) else emptyList(),
    )

    private val chat = Chat(id = "chat1")

    // --- The fixtures -----------------------------------------------------------------------------

    @Test
    fun aWaitingMessageSaysHowItWaitsAsTheServerWritesIt() {
        val queued = message("message_queued")
        val unsure = message("message_unsure")
        val carried = message("message_carried")
        assertEquals(Waiting.Queued, queued.waiting)
        assertEquals(Waiting.Unsure, unsure.waiting)
        assertEquals(Waiting.Carried, carried.waiting)
        // Each is the person's own bubble, waiting, with its words.
        for (waits in listOf(queued, unsure, carried)) {
            assertTrue(waits.isUser && waits.queued)
            assertEquals("And the leaves?", waits.text)
            assertEquals(Drawn.Person, drawn(waits, first = false, reportChat = false))
        }
        // Only the one whose arrival nobody heard of is drawn as a warning.
        assertTrue(unsure.fateUnknown)
        assertFalse(carried.fateUnknown)
        assertFalse(queued.fateUnknown)
        // The queue's place is the plain queued one's alone.
        assertEquals(2, queued.progress?.ahead)
        assertNull(unsure.progress?.ahead)
        assertNull(carried.progress?.ahead)
    }

    @Test
    fun onlyAPersonsQueuedMessageWaits() {
        // Sent, it is a turn like any other; a reply never waits, whatever its row says.
        assertNull(person("q1").waiting)
        assertNull(person("q1", origin = "carried").waiting)
        assertNull(reply("a1").copy(queued = true).waiting)
        // An origin this app does not know waits as a plain queued message does.
        assertEquals(Waiting.Queued, person("q1", queued = true, origin = "somewhere").waiting)
    }

    @Test
    fun aWaitingMessageIsNeverOfferedEdit() {
        for (name in listOf("message_queued", "message_unsure", "message_carried")) {
            assertFalse(name, message(name).editable(writing = false))
        }
        // A delivered one is, while nothing is being written; a reply never.
        assertTrue(person("q1").editable(writing = false))
        assertFalse(person("q1").editable(writing = true))
        assertFalse(reply("a1").editable(writing = false))
        // The message a send-now answers with has stopped waiting.
        val sent = LembasJson.decodeFromString(Turn.serializer(), fixture("message_sent_now"))
        assertTrue(requireNotNull(sent.message).editable(writing = false))
    }

    @Test
    fun theAnswersToSendNowAndDiscardAreReadAsTheServerWritesThem() {
        val sent = LembasJson.decodeFromString(Turn.serializer(), fixture("message_sent_now"))
        val message = requireNotNull(sent.message)
        val started = requireNotNull(sent.reply)
        assertEquals("msg1", message.id)
        assertFalse(message.queued)
        assertEquals("", message.origin)
        assertNull(message.waiting)
        assertNull(message.progress)
        // The reply started for it: the model's, not finished, nothing written yet.
        assertEquals("msg4", started.id)
        assertFalse(started.isUser)
        assertFalse(started.complete)
        assertTrue(started.steps.isEmpty())

        assertEquals(Discarded(id = "msg3", discarded = true), LembasJson.decodeFromString(Discarded.serializer(), fixture("message_discarded")))
    }

    // --- The two requests -------------------------------------------------------------------------

    @Test
    fun sendNowAndDiscardAreTheMessagesOwnRoutes() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            val api = ApiClient(server.url("/").toString().trimEnd('/'), "lmb_test")
            server.enqueue(MockResponse().setBody(fixture("message_sent_now")))
            server.enqueue(MockResponse().setBody(fixture("message_discarded")))
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":"Wait for the current reply to finish, or stop it."}"""))
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"That message is not waiting to be sent."}"""))

            assertEquals("msg4", api.sendWaiting("chat1", "msg1").reply?.id)
            val send = server.takeRequest()
            assertEquals("POST", send.method)
            assertEquals("/api/v2/chats/chat1/messages/msg1/send", send.path)
            assertEquals("Bearer lmb_test", send.getHeader("Authorization"))
            // It starts a reply, so it says what this app can do, as a message does.
            assertTrue(send.body.readUtf8().contains("\"client\":[\"phone_actions\"]"))

            assertTrue(api.discardWaiting("chat1", "msg3").discarded)
            val discard = server.takeRequest()
            assertEquals("POST", discard.method)
            assertEquals("/api/v2/chats/chat1/messages/msg3/discard", discard.path)

            // A refusal is the instance's own sentence, with its status.
            try {
                api.sendWaiting("chat1", "msg1")
                fail("a 409 must throw")
            } catch (e: ApiException) {
                assertEquals(409, e.status)
                assertEquals("Wait for the current reply to finish, or stop it.", e.message)
            }
            try {
                api.discardWaiting("chat1", "msg3")
                fail("a 404 must throw")
            } catch (e: ApiException) {
                assertEquals(404, e.status)
                assertEquals("That message is not waiting to be sent.", e.message)
            }
        } finally {
            server.shutdown()
        }
    }

    // --- What the transcript becomes ---------------------------------------------------------------

    @Test
    fun aMessageSentNowStopsWaitingWhereItStoodAndItsReplyIsTheLastRow() {
        val shown = ChatState(
            chat = chat,
            messages = listOf(person("q1"), reply("a1"), person("w1", queued = true, origin = "unsure"), person("w2", queued = true)),
        )
        val turn = Turn(message = person("w1"), reply = reply("a2", complete = false))
        val after = shown.sentNow(turn)
        assertEquals(listOf("q1", "a1", "w1", "w2", "a2"), after.messages.map { it.id })
        assertNull(after.messages[2].waiting)
        // The other waiting message still waits.
        assertEquals(Waiting.Queued, after.messages[3].waiting)
        assertEquals("w1", after.lastSent)
        // Told twice (the answer, then the same rows from a read): no row is there twice.
        assertEquals(after.messages.map { it.id }, after.sentNow(turn).messages.map { it.id })
        // An answer that names no reply changes the message alone.
        assertEquals(listOf("q1", "a1", "w1", "w2"), shown.sentNow(Turn(message = person("w1"))).messages.map { it.id })
    }

    @Test
    fun aDiscardedMessageIsGoneAndNothingElseMoves() {
        val shown = ChatState(chat = chat, messages = listOf(person("q1"), reply("a1"), person("w1", queued = true), person("w2", queued = true)), more = true)
        val after = shown.discarded("w1")
        assertEquals(listOf("q1", "a1", "w2"), after.messages.map { it.id })
        assertTrue(after.more)
        // One that is not shown (any more): nothing to take away.
        assertEquals(after, after.discarded("w1"))
    }

    @Test
    fun theReplyASendStartedIsFollowedAsThePersonsOwnUnlessTheChatNoLongerHoldsIt() {
        val message = person("w1")
        val started = reply("a2", complete = false)
        // The read could not be made: the answer's word stands.
        assertEquals(Followed("a2", own = true), followedAfterSend("a2", null))
        // The read says it is being written, by `live` or by its row.
        assertEquals(Followed("a2", own = true), followedAfterSend("a2", ChatDetail(chat = chat, messages = listOf(message, started), live = "a2")))
        assertEquals(Followed("a2", own = true), followedAfterSend("a2", ChatDetail(chat = chat, messages = listOf(message, started))))
        // A linked computer that still knew the message did not run it again: the reply's row is
        // gone, and there is nothing to follow -- or only what else is being written, as nobody's.
        assertNull(followedAfterSend("a2", ChatDetail(chat = chat, messages = listOf(person("w1", queued = true, origin = "carried")))))
        assertEquals(
            Followed("a9", own = false),
            followedAfterSend("a2", ChatDetail(chat = chat, messages = listOf(message, reply("a9", complete = false)), live = "a9")),
        )
        // Already finished by the time it was read: drawn from the read, not followed.
        assertNull(followedAfterSend("a2", ChatDetail(chat = chat, messages = listOf(message, reply("a2")))))
        // An answer without a reply follows whatever the read says is live.
        assertNull(followedAfterSend(null, null))
        assertEquals(Followed("a9", own = false), followedAfterSend(null, ChatDetail(chat = chat, live = "a9")))
    }

    @Test
    fun onlyARefusalAboutTheMessageAsksForTheChatAgain() {
        assertTrue(staleWaiting(ApiException(409, "Wait for the current reply to finish, or stop it.")))
        assertTrue(staleWaiting(ApiException(409, "That message is not waiting to be sent.")))
        assertTrue(staleWaiting(ApiException(404, "That message is not waiting to be sent.")))
        // A server that failed, or one out of reach, says nothing about the message.
        assertFalse(staleWaiting(ApiException(500, "Something went wrong.")))
        assertFalse(staleWaiting(ConnectException("refused")))
    }

    // --- The actions, as the screen does them -------------------------------------------------------

    /** The screen's side of the two actions, recorded: what was read, followed and said. */
    private class Screen(start: ChatState, private val server: ChatDetail? = null) {
        val state = MutableStateFlow(start)
        val followed = mutableListOf<Followed>()
        val said = mutableListOf<String>()
        var reads = 0
        var catchUps = 0

        /** What the buttons were when the chat was read again after a refusal. */
        var actingAtCatchUp: String? = "never"

        val actions = WaitingActions(
            state = state,
            read = {
                reads++
                server
            },
            follow = { followed += it },
            say = { said += it.message.orEmpty() },
            catchUp = {
                catchUps++
                actingAtCatchUp = state.value.acting
            },
        )
    }

    private val waitingChat = ChatState(chat = chat, messages = listOf(person("q1"), reply("a1"), person("w1", queued = true, origin = "unsure")))

    @Test
    fun sendNowStartsAReplyThatIsFollowedAsOneThePersonAskedFor() = runTest {
        val turn = Turn(message = person("w1"), reply = reply("a2", complete = false))
        val screen =
            Screen(
                waitingChat,
                server = ChatDetail(chat = chat, messages = listOf(person("q1"), reply("a1"), person("w1"), reply("a2", complete = false)), live = "a2"),
            )
        screen.actions.sendNow(waitingChat.messages.last()) { turn }
        // The row no longer waits, the reply is there, and it is followed as the person's own.
        assertEquals(listOf("q1", "a1", "w1", "a2"), screen.state.value.messages.map { it.id })
        assertNull(screen.state.value.messages[2].waiting)
        assertEquals(listOf(Followed("a2", own = true)), screen.followed)
        // The chat was read once (a linked computer's chat sorts the message to where it was sent).
        assertEquals(1, screen.reads)
        assertTrue(screen.said.isEmpty())
        assertEquals(0, screen.catchUps)
        assertNull(screen.state.value.acting)
    }

    @Test
    fun theButtonsRestWhileTheInstanceIsAskedAndASecondTapIsNoSecondRequest() = runTest {
        val screen = Screen(waitingChat)
        val answer = CompletableDeferred<Turn>()
        var asked = 0
        val first = launch {
            screen.actions.sendNow(waitingChat.messages.last()) {
                asked++
                answer.await()
            }
        }
        runCurrent()
        assertEquals("w1", screen.state.value.acting)
        // A second tap, on either button, while the first is under way: nothing is asked.
        screen.actions.sendNow(waitingChat.messages.last()) {
            asked++
            Turn()
        }
        screen.actions.discard(waitingChat.messages.last()) { asked++ }
        assertEquals(1, asked)
        assertEquals(3, screen.state.value.messages.size)
        answer.complete(Turn(message = person("w1"), reply = reply("a2", complete = false)))
        first.join()
        assertNull(screen.state.value.acting)
        assertEquals(listOf(Followed("a2", own = true)), screen.followed)
    }

    @Test
    fun discardTakesTheRowAwayAndAsksNothingElse() = runTest {
        val screen = Screen(waitingChat)
        var asked = 0
        screen.actions.discard(waitingChat.messages.last()) { asked++ }
        assertEquals(1, asked)
        assertEquals(listOf("q1", "a1"), screen.state.value.messages.map { it.id })
        assertEquals(0, screen.reads)
        assertEquals(0, screen.catchUps)
        assertTrue(screen.followed.isEmpty())
        assertTrue(screen.said.isEmpty())
        assertNull(screen.state.value.acting)
    }

    @Test
    fun aRefusalIsSaidInTheInstancesWordsAndTheChatIsReadAgain() = runTest {
        // Send now while a reply is being written; send now and discard for a message that waits no more; discard for one that is gone.
        val refusals = listOf(
            ApiException(409, "Wait for the current reply to finish, or stop it."),
            ApiException(409, "That message is not waiting to be sent."),
            ApiException(404, "That message is not waiting to be sent."),
        )
        for (refusal in refusals) {
            for (send in listOf(true, false)) {
                val screen = Screen(waitingChat)
                val waits = waitingChat.messages.last()
                if (send) screen.actions.sendNow(waits) { throw refusal } else screen.actions.discard(waits) { throw refusal }
                assertEquals(listOf(refusal.message), screen.said)
                assertEquals(1, screen.catchUps)
                // The buttons were free again before the read: it may wait for a reply to end.
                assertNull(screen.actingAtCatchUp)
                // Nothing was changed on a guess, and nothing followed: the read says what is true.
                assertEquals(waitingChat.messages, screen.state.value.messages)
                assertTrue(screen.followed.isEmpty())
                assertNull(screen.state.value.acting)
            }
        }
    }

    @Test
    fun aFailureThatSaysNothingOfTheMessageLeavesItWaitingAndReadsNothing() = runTest {
        for (failure in listOf(ConnectException("refused"), ApiException(500, "Something went wrong."))) {
            val screen = Screen(waitingChat)
            screen.actions.sendNow(waitingChat.messages.last()) { throw failure }
            assertEquals(1, screen.said.size)
            assertEquals(0, screen.catchUps)
            assertEquals(Waiting.Unsure, screen.state.value.messages.last().waiting)
            assertNull(screen.state.value.acting)
            // The person can try again.
            screen.actions.discard(waitingChat.messages.last()) { }
            assertEquals(listOf("q1", "a1"), screen.state.value.messages.map { it.id })
        }
    }
}
