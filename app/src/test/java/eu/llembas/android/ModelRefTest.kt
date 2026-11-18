package eu.llembas.android

import eu.llembas.android.api.Chat
import eu.llembas.android.api.ChatPage
import eu.llembas.android.api.LembasJson
import eu.llembas.android.api.Message
import eu.llembas.android.api.Metrics
import eu.llembas.android.api.ModelInfo
import eu.llembas.android.api.RunResult
import eu.llembas.android.api.Schedule
import eu.llembas.android.api.ScheduleEnvelope
import eu.llembas.android.api.SchedulingModel
import eu.llembas.android.api.modelKey
import eu.llembas.android.api.named
import eu.llembas.android.api.replyModel
import eu.llembas.android.api.saidWithConnection
import eu.llembas.android.api.serving
import eu.llembas.android.ui.chat.ChatState
import eu.llembas.android.ui.chat.footLine
import eu.llembas.android.ui.chat.modelId
import eu.llembas.android.ui.chat.modelInfo
import eu.llembas.android.ui.chat.modelWord
import eu.llembas.android.ui.scheduled.EditState
import eu.llembas.android.ui.scheduled.modelToSend
import eu.llembas.android.ui.scheduled.read
import kotlinx.serialization.KSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Two connections serve one model id, and a chat, a reply and a schedule are on the second:
 * each says which connection (`model_ref`), and the app shows that one, not the first with the id.
 */
class ModelRefTest {
    private val home = ModelInfo(id = "qwen", name = "Qwen", ref = "local/qwen", provider = "local", connection = "Local", default = true)
    private val cloud = ModelInfo(id = "qwen", name = "Qwen", ref = "studio/qwen", provider = "studio", connection = "Model Studio")
    private val other = ModelInfo(id = "gpt-oss", name = "gpt-oss", ref = "local/gpt-oss", provider = "local", connection = "Local")
    private val models = listOf(home, cloud, other)

    private fun <T> read(name: String, serializer: KSerializer<T>): T = LembasJson.decodeFromString(
        serializer,
        requireNotNull(javaClass.classLoader!!.getResource("$name.json")) {
            "contract/$name.json is missing"
        }.readText(),
    )

    @Test
    fun aChatOnTheSecondConnectionShowsTheSecond() {
        val chat = Chat(id = "c", model = "qwen", modelRef = "studio/qwen")
        val open = ChatState(models = models, chat = chat)
        assertEquals("studio/qwen", chat.modelKey)
        // The header, the composer's facts and the picker's tick all read these two.
        assertEquals("studio/qwen", open.modelId)
        assertEquals(cloud, open.modelInfo)
        assertEquals(cloud.key, models.named(open.modelId)?.key)
        // What was picked here earlier does not outrank what the chat itself says.
        assertEquals(cloud, open.copy(picked = "local/qwen").modelInfo)
        // The bare id alone still names the first, as before and as on the server.
        assertEquals(home, ChatState(models = models, chat = Chat(id = "c", model = "qwen")).modelInfo)
        assertEquals(home, ChatState(models = models, chat = Chat(id = "c", model = "qwen", modelRef = "")).modelInfo)
    }

    @Test
    fun aRefTheListDoesNotHaveIsNoModelRatherThanTheFirstWithThatId() {
        val open = ChatState(models = models, chat = Chat(id = "c", model = "qwen", modelRef = "gone/qwen"))
        assertNull(open.modelInfo)
        // The title then says the chat's own id for it, not a ref nobody chose by.
        assertEquals("qwen", open.modelWord)
        assertNull(models.serving("gone/qwen", "qwen"))
        assertEquals(home, models.serving(null, "qwen"))
        assertEquals(cloud, models.serving("studio/qwen", "anything"))
    }

    @Test
    fun whatAThingIsKeptAndSentBy() {
        assertEquals("studio/qwen", modelKey("studio/qwen", "qwen"))
        assertEquals("qwen", modelKey(null, "qwen"))
        assertEquals("qwen", modelKey("", "qwen"))
        assertNull(modelKey(null, ""))
        assertNull(modelKey(null, null))
    }

    @Test
    fun theLineUnderAReplyNamesTheConnectionOfATwin() {
        assertEquals("qwen · Model Studio", models.replyModel("studio/qwen", "qwen"))
        assertEquals("qwen · Local", models.replyModel("local/qwen", "qwen"))
        // A model alone with its id says nothing more, and neither does a reply with no ref.
        assertEquals("gpt-oss", models.replyModel("local/gpt-oss", "gpt-oss"))
        assertEquals("qwen", models.replyModel(null, "qwen"))
        assertEquals("qwen", models.replyModel("gone/qwen", "qwen"))
        assertNull(models.replyModel("studio/qwen", null))
        val reply = Message(id = "m", role = "assistant", model = "qwen", modelRef = "studio/qwen", metrics = Metrics(completionTokens = 12))
        assertEquals("qwen · Model Studio · 12 tok", footLine(reply, models))
        assertEquals("qwen · 12 tok", footLine(reply))
        assertEquals("qwen · Model Studio", footLine(reply.copy(metrics = null), models))
    }

    @Test
    fun aScheduleAndItsRunNameTheConnection() {
        val offered = listOf(
            SchedulingModel(id = "qwen", name = "Qwen", ref = "local/qwen", connection = "Local"),
            SchedulingModel(id = "qwen", name = "Qwen", ref = "studio/qwen", connection = "Model Studio"),
            SchedulingModel(id = "gpt-oss", name = "gpt-oss", ref = "local/gpt-oss", connection = "Local"),
        )
        assertEquals("qwen · Model Studio", offered.saidWithConnection("studio/qwen", "qwen"))
        assertEquals("gpt-oss", offered.saidWithConnection("local/gpt-oss", "gpt-oss"))
        assertEquals("qwen", offered.saidWithConnection(null, "qwen"))
        assertEquals("qwen", emptyList<SchedulingModel>().saidWithConnection("studio/qwen", "qwen"))
        assertNull(offered.saidWithConnection("studio/qwen", ""))

        // The form starts from the schedule's own connection, ticks that one, and a save that
        // changed nothing says no model.
        val schedule = Schedule(id = "s", model = "qwen", modelRef = "studio/qwen")
        assertEquals("studio/qwen", schedule.modelKey)
        val form = EditState(models = offered, loaded = true).read(schedule).copy(agentic = true)
        assertEquals("studio/qwen", form.model)
        assertEquals(offered[1], offered.named(form.model))
        assertNull(modelToSend(form, editing = true))
        assertEquals("local/qwen", modelToSend(form.copy(model = "local/qwen"), editing = true))
    }

    @Test
    fun thePinnedContractSaysTheRefs() {
        assertEquals("local/qwen", read("chat", Chat.serializer()).modelRef)
        assertEquals("local/qwen", read("chats_listed", ChatPage.serializer()).chats.first().modelRef)
        assertEquals("local/qwen", read("message_assistant", Message.serializer()).modelRef)
        assertEquals("local/qwen", read("schedule", ScheduleEnvelope.serializer()).schedule.modelRef)
        assertEquals("local/gpt-oss", read("schedule_run", RunResult.serializer()).run.model.ref)
    }
}
