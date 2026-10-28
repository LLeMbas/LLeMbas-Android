package eu.llembas.android

import eu.llembas.android.api.AskFrame
import eu.llembas.android.api.Chat
import eu.llembas.android.api.DoneFrame
import eu.llembas.android.api.JobsFrame
import eu.llembas.android.api.LembasJson
import eu.llembas.android.api.LiveReply
import eu.llembas.android.api.Message
import eu.llembas.android.api.Metrics
import eu.llembas.android.api.Plan
import eu.llembas.android.api.StatusFrame
import eu.llembas.android.api.SteeredFrame
import eu.llembas.android.api.StepsFrame
import eu.llembas.android.api.Tail
import kotlinx.serialization.KSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server's contract, as pinned in `contract/` by `scripts/fetch-contract.py`: every shape
 * the server's serialisers wrote must parse into this app's models, and say what it said.
 */
class ContractTest {
    private fun text(name: String): String = requireNotNull(javaClass.classLoader!!.getResource("$name.json")) { "contract/$name.json is missing" }.readText()

    private fun <T> read(name: String, serializer: KSerializer<T>): T = LembasJson.decodeFromString(serializer, text(name))

    @Test
    fun theVersionIsOneThisAppReads() {
        val version = LembasJson.parseToJsonElement(text("version")).toString()
        assertEquals("""{"app_api":1}""", version)
    }

    @Test
    fun theTwoPointOhContractHasNoCanvas() {
        // LLeMbas 2.0 removed the canvas: no `canvas` frame, so no fixture for one. A stream
        // that still sends it (a 1.x server) is ignored frame by frame, as any unknown event is.
        assertNull(javaClass.classLoader!!.getResource("event_canvas.json"))
        assertEquals(LiveReply("m"), LiveReply("m").apply("canvas", """{"tabs": [], "active": ""}"""))
    }

    @Test
    fun aChatAndAnAgentChat() {
        val chat = read("chat", Chat.serializer())
        assertEquals("Waybread", chat.title)
        assertEquals("low", chat.effort)
        assertTrue(chat.unread)
        val agent = read("chat_agent", Chat.serializer())
        assertTrue(agent.isAgent)
        assertEquals("edit", agent.mode)
        assertEquals("/home/dev/project", agent.device?.directory)
    }

    @Test
    fun theMessages() {
        val user = read("message_user", Message.serializer())
        assertTrue(user.isUser)
        assertEquals("How is lembas made?", user.text)

        val reply = read("message_assistant", Message.serializer())
        assertEquals(listOf("thinking", "text", "tools", "steer", "text"), reply.steps.map { it.kind })
        assertTrue(reply.steps[1].markdown!!.startsWith("Here is how it is made"))
        val call = reply.steps[2].calls.single()
        assertEquals("shell", call.block.kind)
        assertEquals("bake --slow", call.block.title)
        assertEquals(listOf("exit 0"), call.block.chips)
        assertEquals("and add honey", reply.steps[3].messages.single().text)
        assertEquals(40, reply.metrics!!.completionTokens)
    }

    @Test
    fun everyFrameParses() {
        assertTrue(read("event_steps", StepsFrame.serializer()).steps.isNotEmpty())
        assertNotNull(read("event_tail", Tail.serializer()).label)
        assertEquals(listOf("msg9"), read("event_steered", SteeredFrame.serializer()).ids)
        assertEquals("Bake lembas", read("event_plan", Plan.serializer()).title)
        assertTrue(read("event_status", StatusFrame.serializer()).text.isNotEmpty())
        val cards = read("event_ask", AskFrame.serializer()).cards
        assertEquals(listOf("question", "approval", "plan"), cards.map { it.kind })
        assertEquals(listOf("Honey", "Plain"), cards[0].items[0].options.map { it.label })
        assertTrue(cards[1].items[0].editable)
        assertNotNull(cards[2].items[0].plan)
        assertTrue(read("event_ask_empty", AskFrame.serializer()).cards.isEmpty())
        assertEquals("explore", read("event_jobs", JobsFrame.serializer()).jobs.single().agent)
        assertEquals(2, read("event_metrics", Metrics.serializer()).percent)
        assertEquals("Waybread", read("event_done", DoneFrame.serializer()).chat.title)
    }

    @Test
    fun aWholeStreamBuildsAReply() {
        var live = LiveReply("msg2")
        for (event in listOf("steps", "tail", "steered", "plan", "status", "ask", "jobs", "metrics", "done", "close")) {
            live = live.apply(event, text("event_$event"))
        }
        assertTrue(live.closed)
        assertNotNull(live.finished)
        assertEquals("Waybread", live.title)
        assertNull(live.next)
        // A finished reply asks nothing: `done` clears the cards.
        assertTrue(live.cards.isEmpty())
        assertEquals(setOf("msg9"), live.steered)
        assertEquals("Bake lembas", live.plan?.title)
    }
}
