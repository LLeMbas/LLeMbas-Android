package eu.llembas.android

import eu.llembas.android.api.AgentsSetting
import eu.llembas.android.api.Chat
import eu.llembas.android.api.LembasJson
import eu.llembas.android.api.LinkedDevice
import eu.llembas.android.api.ModelList
import eu.llembas.android.ui.chat.ChatState
import eu.llembas.android.ui.chat.Draft
import eu.llembas.android.ui.chat.agents
import eu.llembas.android.ui.chat.compactContext
import eu.llembas.android.ui.chat.device
import eu.llembas.android.ui.chat.level
import eu.llembas.android.ui.chat.modelId
import eu.llembas.android.ui.chat.offersTeam
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
        assertEquals("qwen", ChatState(models = models).modelId)
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
}
