package eu.llembas.android

import eu.llembas.android.api.AgentSubagents
import eu.llembas.android.api.AgentsSetting
import eu.llembas.android.api.Chat
import eu.llembas.android.api.ChatDetail
import eu.llembas.android.api.LembasJson
import eu.llembas.android.api.LinkedDevice
import eu.llembas.android.api.LiveReply
import eu.llembas.android.api.Message
import eu.llembas.android.api.MessagePage
import eu.llembas.android.api.ModelList
import eu.llembas.android.api.Step
import eu.llembas.android.api.SubagentsOffer
import eu.llembas.android.ui.chat.ChatState
import eu.llembas.android.ui.chat.Draft
import eu.llembas.android.ui.chat.agents
import eu.llembas.android.ui.chat.closedUnfinished
import eu.llembas.android.ui.chat.compactContext
import eu.llembas.android.ui.chat.device
import eu.llembas.android.ui.chat.level
import eu.llembas.android.ui.chat.modelId
import eu.llembas.android.ui.chat.offersTeam
import eu.llembas.android.ui.chat.withOlder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the top bar, the composer and the tune sheet read from a chat's state. */
class ChatStateTest {
    private val models = LembasJson.decodeFromString(
        ModelList.serializer(),
        requireNotNull(javaClass.classLoader!!.getResource("models.json")).readText(),
    ).models

    @Test
    fun theModelIsTheChatsThenTheDraftsThenTheDefault() {
        // A model is named by its ref; a chat and an older choice still name it by its id.
        assertEquals("local/qwen", ChatState(models = models).modelId)
        assertEquals("gpt-oss", ChatState(models = models, draft = Draft(model = "gpt-oss")).modelId)
        assertEquals("vision-small", ChatState(models = models, chat = Chat(id = "c", model = "vision-small"), draft = Draft(model = "gpt-oss")).modelId)
    }

    @Test
    fun theLevelFollowsTheModelsControl() {
        // A switch model starts at its effort; one level is no choice at all.
        assertEquals("medium", ChatState(models = models).level)
        assertEquals("off", ChatState(models = models, draft = Draft(effort = "off")).level)
        assertNull(ChatState(models = models, draft = Draft(model = "vision-small")).level)
        // A level the model does not offer falls back to its own.
        assertEquals("high", ChatState(models = models, chat = Chat(id = "c", model = "gpt-oss", effort = "extreme")).level)
    }

    @Test
    fun aTeamIsOfferedByTheServerOrGuessedForANewChat() {
        assertTrue(ChatState(models = models).offersTeam(agent = false))
        assertFalse(ChatState(models = models).offersTeam(agent = true))
        // gpt-oss serves one at a time: offered, for a model named in the chat; vision-small not at all.
        assertTrue(ChatState(models = models, draft = Draft(model = "gpt-oss")).offersTeam(agent = false))
        assertFalse(ChatState(models = models, draft = Draft(model = "vision-small")).offersTeam(agent = false))
        assertTrue(ChatState(models = models, chat = Chat(id = "c", model = "gpt-oss"), teamOffered = true).offersTeam(agent = false))
        assertEquals(AgentsSetting(2, 6), ChatState(draft = Draft(agents = AgentsSetting(2, 6))).agents)
    }

    @Test
    fun anAgentChatHasTheSliderWhenTheServerOffersIt() {
        // Its subagents run on its device (spec 3.4.0 §14): the chat's own word decides, never a guess.
        val agentChat = Chat(id = "a", kind = "agent", model = "qwen")
        assertTrue(ChatState(models = models, chat = agentChat.copy(subagents = SubagentsOffer(offered = true, cap = 8))).offersTeam(agent = true))
        assertFalse(ChatState(models = models, chat = agentChat.copy(subagents = SubagentsOffer(offered = false, cap = 32))).offersTeam(agent = true))
        // A new agent chat: the server's fact for agent chats, never the model's guess.
        assertTrue(ChatState(models = models, agentSubagents = AgentSubagents(offered = true)).offersTeam(agent = true))
        assertFalse(ChatState(models = models, agentSubagents = AgentSubagents(offered = false)).offersTeam(agent = true))
        assertFalse(ChatState(models = models).offersTeam(agent = true))
        // A listed chat (`subagents: null`) asks the chat's activity.
        assertTrue(ChatState(models = models, chat = agentChat, teamOffered = true).offersTeam(agent = true))
    }

    @Test
    fun aNewAgentChatRunsOnTheChosenDeviceOrTheFirst() {
        val devices = listOf(LinkedDevice("d1"), LinkedDevice("d2"))
        assertEquals("d1", ChatState(devices = devices).device?.id)
        assertEquals("d2", ChatState(devices = devices, draft = Draft(device = "d2")).device?.id)
        assertNull(ChatState().device)
    }

    @Test
    fun contextWindowsAreSaidShort() {
        assertEquals("128K", compactContext(131072))
        assertEquals("8K", compactContext(8192))
        assertEquals("1M", compactContext(1_000_000))
    }

    private fun said(id: String, role: String = "user") = Message(id = id, role = role)

    @Test
    fun anOlderPageIsPutAboveOnceHoweverOftenItArrives() {
        val shown = ChatState(messages = listOf(said("m3"), said("m4")), more = true)
        val page = MessagePage(messages = listOf(said("m1"), said("m2")), more = false)
        val once = shown.withOlder("m3", page)
        assertEquals(listOf("m1", "m2", "m3", "m4"), once.messages.map { it.id })
        assertFalse(once.more)
        // The same page a second time (two taps, two answers): m3 is no longer the first row.
        assertEquals(once, once.withOlder("m3", page))
        // A page that overlaps what is shown, or names a row twice, adds each row once.
        val overlapping = MessagePage(messages = listOf(said("m2"), said("m2"), said("m3")), more = true)
        assertEquals(listOf("m2", "m3", "m4"), shown.withOlder("m3", overlapping).messages.map { it.id })
        // The transcript was read again meanwhile: the page belongs above another first row.
        val moved = ChatState(messages = listOf(said("m7"), said("m8")), more = true)
        assertEquals(moved, moved.withOlder("m3", page))
    }

    @Test
    fun aStreamThatClosesWithoutDoneLeavesWhatWasWritten() {
        val chat = Chat(id = "c")
        val live = LiveReply("m2")
            .apply("steps", """{"steps":[{"index":0,"kind":"thinking","text":"Flour first."}],"tail":{"thinking":"","text":"Mix the","label":""}}""")
            .apply("status", """{"text":"Stopped","progress":{"phase":"stopped","reason":"person"}}""")
        val writing = ChatState(chat = chat, messages = listOf(said("m1"), said("m2", "assistant")), live = live)

        // Read again: the transcript is the server's, with the reply as it stored it.
        val stored = said("m2", "assistant").copy(stopped = true, steps = listOf(Step(index = 0, kind = "text", markdown = "Mix the")))
        val reread = writing.closedUnfinished("m2", ChatDetail(chat = chat.copy(title = "Waybread"), messages = listOf(said("m1"), stored), more = true))
        assertNull(reread.live)
        assertEquals(stored, reread.messages.last())
        assertEquals("Waybread", reread.chat?.title)
        assertTrue(reread.more)

        // It could not be read: the steps the stream had shown stay on the row, closed, with
        // the end the stream said.
        val kept = writing.closedUnfinished("m2", null)
        assertNull(kept.live)
        assertEquals(listOf("thinking", "text"), kept.messages.last().steps.map { it.kind })
        assertEquals("Mix the", kept.messages.last().steps.last().markdown)
        assertTrue(kept.messages.last().steps.none { it.open })
        assertEquals("stopped", kept.messages.last().progress?.phase)
        assertEquals(said("m1"), kept.messages.first())

        // Nothing had been written: nothing is invented, and nothing is live.
        val empty = writing.copy(live = LiveReply("m2")).closedUnfinished("m2", null)
        assertNull(empty.live)
        assertEquals(writing.messages, empty.messages)

        // Another reply is followed by now: this stream's end is not its business.
        val next = writing.copy(live = LiveReply("m9"))
        assertEquals(next, next.closedUnfinished("m2", null))
        assertEquals(next, next.closedUnfinished("m2", ChatDetail(chat = chat)))
    }
}
