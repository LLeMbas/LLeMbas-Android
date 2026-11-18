package eu.llembas.android

import eu.llembas.android.api.ApiClient
import eu.llembas.android.api.Chat
import eu.llembas.android.api.DeviceDirectory
import eu.llembas.android.api.DeviceList
import eu.llembas.android.api.LembasJson
import eu.llembas.android.api.ModelInfo
import eu.llembas.android.api.ModelList
import eu.llembas.android.api.StartModel
import eu.llembas.android.api.named
import eu.llembas.android.api.titleAmong
import eu.llembas.android.assist.AssistController
import eu.llembas.android.ui.PICKER_ENTRIES
import eu.llembas.android.ui.PICKER_WORDS
import eu.llembas.android.ui.PickerState
import eu.llembas.android.ui.asking
import eu.llembas.android.ui.back
import eu.llembas.android.ui.canChoose
import eu.llembas.android.ui.chat.ChatState
import eu.llembas.android.ui.chat.Draft
import eu.llembas.android.ui.chat.folderModel
import eu.llembas.android.ui.chat.modelId
import eu.llembas.android.ui.chat.modelInfo
import eu.llembas.android.ui.refusedHere
import eu.llembas.android.ui.took
import eu.llembas.android.ui.unanswered
import eu.llembas.android.ui.up
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A linked device's directories and the folder's usual model (harness spec 3.9.0), from the
 * server's own fixtures (`contract/device_directories*.json`, `models_agent_directory.json`):
 * the shapes, the picker's walk, and which model a new agent chat shows.
 */
class DirectoriesTest {
    private fun text(name: String): String = requireNotNull(javaClass.classLoader!!.getResource("$name.json")) { "contract/$name.json is missing" }.readText()

    private fun <T> read(name: String, serializer: KSerializer<T>): T = LembasJson.decodeFromString(serializer, text(name))

    private val project = read("device_directories", DeviceDirectory.serializer())
    private val places = read("device_directories_places", DeviceDirectory.serializer())

    @Test
    fun aDirectoryItsEntriesAndTheModelsLastUsedThere() {
        assertEquals("/home/user/project", project.path)
        assertEquals("/home/user", project.parent)
        assertTrue(project.accepted)
        assertTrue(project.listed)
        assertFalse(project.places)
        assertEquals("Qwen", project.model?.label)
        val (docs, server, vendor) = project.entries
        assertNull(docs.model)
        assertEquals("gpt-oss", server.model?.label)
        assertFalse(vendor.accepted)
        assertEquals("This directory is not trusted on the device.", vendor.reason)
        // Without a path: the device's places, each a directory to walk into.
        assertTrue(places.places)
        assertEquals(listOf("/home/user", "/home/user/project"), places.entries.map { it.path })
        assertEquals("Qwen", places.entries.last().model?.label)
    }

    @Test
    fun noListingIsACodeAndASentence() {
        val away = read("device_directories_not_linked", DeviceDirectory.serializer())
        assertFalse(away.listed)
        assertEquals("device_not_linked", away.code)
        assertEquals("This device is not linked right now.", away.error)
        val old = read("device_directories_cli_too_old", DeviceDirectory.serializer())
        assertEquals("cli_too_old", old.code)
        assertTrue(old.error.isNotBlank())
    }

    @Test
    fun thePickerWalksDownAndUpAndClosesAtTheTop() {
        val start = PickerState(loading = true).took(places)
        assertFalse(start.loading)
        // At the places there is no "up": back closes. And no path yet, so nothing to choose.
        assertNull(start.up)
        assertEquals("", start.typed)
        assertFalse(start.canChoose)
        val inside = start.took(project)
        assertEquals("/home/user/project", inside.typed)
        assertEquals("/home/user", inside.up)
        assertTrue(inside.canChoose)
        // A directory with no parent leads back to the places.
        assertEquals("", inside.took(project.copy(path = "/home/user", parent = "")).up)
        // Back at the places the field keeps the last path: it is still what would be chosen.
        assertEquals("/home/user/project", inside.took(places).typed)
    }

    @Test
    fun aDirectoryTheDeviceRefusesCannotBeChosenButATypedPathCan() {
        val refused = PickerState().took(
            project.copy(path = "/home/user/project/vendor", accepted = false, reason = "This directory is not trusted on the device."),
        )
        assertTrue(refused.refusedHere)
        assertFalse(refused.canChoose)
        // Another path written over it has not been refused by anybody: the instance checks it.
        val typed = refused.copy(typed = "/srv/garden")
        assertFalse(typed.refusedHere)
        assertTrue(typed.canChoose)
        assertFalse(typed.copy(typed = "  ").canChoose)
    }

    @Test
    fun aDeviceThatCannotListKeepsTheTypedPathAndSaysWhy() {
        val typed = PickerState(typed = "/home/user/garden", loading = true)
        for (name in listOf("device_directories_not_linked", "device_directories_cli_too_old")) {
            val answer = read(name, DeviceDirectory.serializer())
            val after = typed.took(answer, fallback = "It cannot be listed.")
            assertEquals("/home/user/garden", after.typed)
            assertEquals(answer.error, after.error)
            assertFalse(after.loading)
            assertTrue(after.canChoose)
        }
        // The listing shown before stays where it was; a code without a sentence gets the app's own.
        val walked = PickerState().took(project)
        val silent = walked.took(DeviceDirectory(code = "no_answer"), fallback = "It cannot be listed.")
        assertEquals(project, silent.listing)
        assertEquals("It cannot be listed.", silent.error)
        // The next listing clears the sentence.
        assertNull(silent.took(places).error)
    }

    @Test
    fun whatWasTypedWhileTheDeviceAnsweredStays() {
        // Asked, then typed in before the answer came: the answer's path does not write over it.
        val typing = PickerState(typed = "/home/user").asking().copy(typed = "/srv/garden", edited = true)
        val answered = typing.took(project)
        assertEquals("/srv/garden", answered.typed)
        assertEquals(project.path, answered.listing?.path)
        // The next question starts afresh: its answer is followed again.
        assertEquals("/home/user/project", answered.asking().took(project).typed)
    }

    @Test
    fun backClosesWhenTheDeviceWentAwayMidWalk() {
        val inside = PickerState().took(project)
        assertEquals("/home/user", inside.back)
        // The parent could not be listed: "up" would ask it again and again, so Back closes.
        val away = inside.asking().took(read("device_directories_not_linked", DeviceDirectory.serializer()))
        assertTrue(away.failed)
        assertNull(away.back)
        assertNull(inside.asking().unanswered("Home cannot be reached.").back)
        // A listing after it: the walk goes on.
        assertEquals("/home/user", away.asking().took(project).back)
        assertNull(PickerState().took(places).back)
    }

    @Test
    fun aListingIsBoundedBeforeItIsDrawn() {
        val entry = project.entries.first()
        val long = "x".repeat(5_000)
        val wild = project.copy(
            reason = long,
            entries = listOf(entry, entry, entry.copy(path = "")) + (1..1_000).map { entry.copy(path = "/home/user/project/d$it", name = long, reason = long) },
        )
        val shown = PickerState().took(wild).listing!!
        // Each path once (the rows are keyed by it), none without one, and no more than the cap.
        assertEquals(PICKER_ENTRIES, shown.entries.size)
        assertEquals(shown.entries.size, shown.entries.map { it.path }.toSet().size)
        assertTrue(shown.entries.none { it.path.isEmpty() })
        // The device's words are cut to a length that leaves the controls on the screen.
        assertTrue(shown.reason.length <= PICKER_WORDS + 1)
        assertTrue(shown.entries.all { it.name.length <= PICKER_WORDS + 1 && it.reason.length <= PICKER_WORDS + 1 })
        assertTrue(PickerState().took(DeviceDirectory(code = "refused", error = long)).error!!.length <= PICKER_WORDS + 1)
    }

    @Test
    fun aListingThatCameWithASentenceShowsBoth() {
        val partial = PickerState().took(project.copy(error = "Some directories could not be read."))
        assertEquals("Some directories could not be read.", partial.error)
        assertEquals(3, partial.listing?.entries?.size)
        assertFalse(partial.failed)
    }

    @Test
    fun aNewAgentChatShowsTheFoldersUsualModelUnlessOneWasPicked() {
        val list = read("models_agent_directory", ModelList.serializer())
        assertEquals(StartModel(model = "gpt-oss", from = "directory", ref = "local/gpt-oss"), list.start)
        assertTrue(list.models.none { it.interactiveOnly })
        val draft = ChatState(models = list.models, start = list.start)
        // The default is Qwen; the folder's usual model is shown instead.
        assertEquals("local/qwen", ChatState(models = list.models).modelId)
        assertEquals("local/gpt-oss", draft.modelId)
        assertEquals("gpt-oss", draft.modelInfo?.id)
        // A model the person picked in this draft is never set aside.
        assertEquals("local/vision-small", draft.copy(draft = Draft(model = "local/vision-small")).modelId)
        // A model the person no longer has is not shown, and a chat that exists has its own.
        assertNull(draft.copy(start = StartModel(model = "gone", from = "directory")).folderModel)
        assertEquals("local/qwen", draft.copy(start = StartModel(model = "gone", from = "directory")).modelId)
        assertEquals("qwen", draft.copy(chat = Chat(id = "c", model = "qwen")).modelInfo?.id)
        // A server before refs says the id alone: it still finds the row.
        assertEquals("local/gpt-oss", draft.copy(start = StartModel(model = "gpt-oss", from = "directory")).folderModel)
        // Without a directory the list says nothing of it.
        assertNull(read("models_agent", ModelList.serializer()).start)
    }

    @Test
    fun twoModelsWithOneIdAreToldApartByTheirRef() {
        val home = ModelInfo(id = "qwen", name = "Qwen", ref = "local/qwen", provider = "local", connection = "Local", default = true)
        val cloud = ModelInfo(id = "qwen", name = "Qwen", ref = "studio/qwen", provider = "studio", connection = "Model Studio")
        val other = ModelInfo(id = "gpt-oss", name = "gpt-oss", ref = "local/gpt-oss", connection = "Local")
        val models = listOf(home, cloud, other)
        // A ref names its own row; a bare id the first of them, as on the server.
        assertEquals(cloud, models.named("studio/qwen"))
        assertEquals(home, models.named("qwen"))
        assertNull(models.named("gone"))
        assertNull(models.named(""))
        // The twins say their connection; a model alone with its id does not.
        assertEquals("Qwen · Model Studio", cloud.titleAmong(models))
        assertEquals("Qwen · Local", home.titleAmong(models))
        assertEquals("gpt-oss", other.titleAmong(models))
        // The folder's usual model by its ref: the second twin, not the first.
        val draft = ChatState(models = models, start = StartModel(model = "qwen", from = "directory", ref = "studio/qwen"))
        assertEquals("studio/qwen", draft.modelId)
        assertEquals(cloud, draft.modelInfo)
        // A pick is kept and sent by its ref.
        assertEquals(cloud, draft.copy(start = null, draft = Draft(model = "studio/qwen")).modelInfo)
        // An open chat says the id; the ref picked here says which twin, while the id still fits.
        val open = ChatState(models = models, chat = Chat(id = "c", model = "qwen"))
        assertEquals(home, open.modelInfo)
        assertEquals(cloud, open.copy(picked = "studio/qwen").modelInfo)
        assertEquals(other, open.copy(chat = Chat(id = "c", model = "gpt-oss"), picked = "studio/qwen").modelInfo)
        // A model from a server before refs is its id.
        assertEquals("qwen", ModelInfo(id = "qwen").key)
    }

    @Test
    fun theAssistantsSavedModelStillFindsItsRowAfterRefs() {
        val models = read("models", ModelList.serializer()).models
        // Saved as an id before refs, or as a ref since: the same model, named by its ref.
        assertEquals("local/gpt-oss", AssistController.assistantModelFor("gpt-oss", "qwen", models))
        assertEquals("local/gpt-oss", AssistController.assistantModelFor("local/gpt-oss", "qwen", models))
        // Gone, or "Instance default": the account's default, an id.
        assertEquals("local/qwen", AssistController.assistantModelFor("retired", "qwen", models))
        assertEquals("local/qwen", AssistController.assistantModelFor("", null, models))
    }

    @Test
    fun aDeviceSaysWhetherItTakesScheduledWorkAndRemembers() {
        val (workstation, laptop) = read("devices", DeviceList.serializer()).devices
        assertTrue(workstation.scheduled)
        assertEquals("edit", workstation.maxMode)
        assertTrue(workstation.directoryModel)
        assertFalse(laptop.scheduled)
        assertFalse(laptop.directoryModel)
        assertEquals("LLeMbas CLI on laptop", laptop.label)
    }

    @Test
    fun theRoutesNameTheDeviceThePathAndTheDirectory() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            val api = ApiClient(server.url("/").toString().trimEnd('/'), "lmb_test")
            repeat(2) { server.enqueue(MockResponse().setBody(text("device_directories_places"))) }
            repeat(2) { server.enqueue(MockResponse().setBody(text("models_agent_directory"))) }
            assertTrue(api.deviceDirectories("token1").places)
            assertEquals("/api/v2/devices/token1/directories", server.takeRequest().path)
            api.deviceDirectories("token1", "/home/user/my project")
            assertEquals("/api/v2/devices/token1/directories?path=%2Fhome%2Fuser%2Fmy%20project", server.takeRequest().path)
            assertEquals("gpt-oss", api.agentModelList("token1", " /home/user/project ").start?.model)
            assertEquals("/api/v2/models?kind=agent&device=token1&directory=%2Fhome%2Fuser%2Fproject", server.takeRequest().path)
            // No folder chosen: the question is the old one.
            api.agentModelList("token1", "")
            assertEquals("/api/v2/models?kind=agent&device=token1", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }
}
