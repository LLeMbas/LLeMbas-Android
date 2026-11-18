package eu.llembas.android

import eu.llembas.android.api.ApiClient
import eu.llembas.android.api.LembasJson
import eu.llembas.android.api.LinkedDevice
import eu.llembas.android.api.RunResult
import eu.llembas.android.api.ScheduleCompiled
import eu.llembas.android.api.ScheduleEnvelope
import eu.llembas.android.api.WatchCommand
import eu.llembas.android.api.WhereToRun
import eu.llembas.android.api.agentic
import eu.llembas.android.ui.scheduled.EditState
import eu.llembas.android.ui.scheduled.agenticOffered
import eu.llembas.android.ui.scheduled.dropsWatch
import eu.llembas.android.ui.scheduled.listed
import eu.llembas.android.ui.scheduled.modelToSend
import eu.llembas.android.ui.scheduled.modesOffered
import eu.llembas.android.ui.scheduled.read
import eu.llembas.android.ui.scheduled.runModelWhy
import eu.llembas.android.ui.scheduled.waitMinutes
import eu.llembas.android.ui.scheduled.watchToSend
import eu.llembas.android.ui.scheduled.watched
import eu.llembas.android.ui.scheduled.whereComplete
import eu.llembas.android.ui.scheduled.whereToSend
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two types of schedule (harness spec 3.9.0): Agentic is a schedule with a computer, Chat
 * one without. What the form offers, what a save says, and what a run tells of where it ran.
 */
class ScheduleTypesTest {
    private fun text(name: String): String = requireNotNull(javaClass.classLoader!!.getResource("$name.json")) { "contract/$name.json is missing" }.readText()

    private fun <T> read(name: String, serializer: KSerializer<T>): T = LembasJson.decodeFromString(serializer, text(name))

    private val workstation = LinkedDevice(id = "tok1", name = "workstation", scheduled = true)
    private val laptop = LinkedDevice(id = "tok2", name = "laptop", scheduled = false)

    @Test
    fun agenticIsOfferedOnlyWhileAComputerAcceptsScheduledWork() {
        assertFalse(EditState().agenticOffered)
        assertFalse(EditState().listed(listOf(laptop)).agenticOffered)
        val offered = EditState().listed(listOf(laptop, workstation))
        assertTrue(offered.agenticOffered)
        // A new Agentic schedule starts on the first computer that accepts it.
        assertEquals("tok1", offered.device)
        assertEquals("", EditState().listed(listOf(laptop)).device)
    }

    @Test
    fun aNewScheduleSaysAComputerOnlyWhenItIsAgentic() {
        assertEquals(WhereToRun.Leave, whereToSend(EditState(), editing = false))
        val agentic = EditState(agentic = true, device = "tok1", cwd = " /home/user/project ", agentMode = "auto", wait = "45", model = "")
        assertEquals(WhereToRun.Computer("tok1", "/home/user/project", "auto", 45), whereToSend(agentic, editing = false))
        // Its model is said only when one was chosen; the folder's usual model needs no word.
        assertNull(modelToSend(agentic, editing = false))
        assertEquals("qwen", modelToSend(agentic.copy(model = "qwen"), editing = false))
        assertNull(modelToSend(EditState(model = "qwen"), editing = false))
        // No wait typed, or nonsense: the instance's own.
        assertNull(agentic.copy(wait = "").waitMinutes)
        assertNull(agentic.copy(wait = "0").waitMinutes)
        // It cannot be saved without its computer and its folder.
        assertTrue(agentic.whereComplete)
        assertFalse(agentic.copy(cwd = " ").whereComplete)
        assertFalse(agentic.copy(device = "").whereComplete)
        assertTrue(EditState().whereComplete)
    }

    @Test
    fun anEditSaysOnlyWhatTheFormKnows() {
        val on = read("schedule_on_device", ScheduleEnvelope.serializer()).schedule
        assertTrue(on.agentic)
        // Not read: nothing is said, whatever the form shows.
        assertEquals(WhereToRun.Leave, whereToSend(EditState(agentic = true, device = "tok1", cwd = "/x"), editing = true))
        // Read: the form holds the schedule's own computer, folder, mode, wait and model.
        val read = EditState().listed(listOf(workstation)).read(on).copy(loaded = true)
        assertTrue(read.agentic)
        assertEquals("tok1", read.device)
        assertEquals("/home/frodo/waybread", read.cwd)
        assertEquals("edit", read.agentMode)
        assertEquals("30", read.wait)
        assertEquals("", read.model)
        // Left as it was read: nothing is said of where it runs, its model or its command -- so
        // its words can be put right while its computer is not linked or its model is gone.
        assertEquals(WhereToRun.Leave, whereToSend(read, editing = true))
        assertNull(modelToSend(read, editing = true))
        assertNull(watchToSend(read, editing = true))
        // What was changed is said, and only that.
        assertEquals(WhereToRun.Computer("tok1", "/home/frodo/lembas", "edit", 30), whereToSend(read.copy(cwd = "/home/frodo/lembas"), editing = true))
        assertEquals(WhereToRun.Computer("tok1", "/home/frodo/waybread", "auto", 30), whereToSend(read.copy(agentMode = "auto"), editing = true))
        assertEquals("qwen", modelToSend(read.copy(model = "qwen"), editing = true))
        assertEquals(WhereToRun.Leave, whereToSend(read.copy(model = "qwen"), editing = true))
        // Back to the folder's usual model: the empty one is said.
        val pinned = EditState().read(on.copy(model = "qwen")).copy(loaded = true)
        assertEquals("qwen", pinned.model)
        assertNull(modelToSend(pinned, editing = true))
        assertEquals("", modelToSend(pinned.copy(model = ""), editing = true))
        // Made a Chat schedule: the server is told so. A Chat schedule left alone: nothing.
        assertEquals(WhereToRun.Server, whereToSend(read.copy(agentic = false), editing = true))
        val chat = EditState().read(read("schedule", ScheduleEnvelope.serializer()).schedule).copy(loaded = true)
        assertFalse(chat.agentic)
        assertEquals(WhereToRun.Leave, whereToSend(chat, editing = true))
        assertNull(modelToSend(chat, editing = true))
    }

    @Test
    fun aChatSchedulesModelIsShownWhenItIsMadeAgentic() {
        val chat = read("schedule", ScheduleEnvelope.serializer()).schedule.copy(model = "gpt-oss", modelRef = null)
        val form = EditState().read(chat).copy(loaded = true, agentic = true, device = "tok1", cwd = "/home/user/project")
        // Its own model is the one the form shows, and it is kept: nothing is said of it.
        assertEquals("gpt-oss", form.model)
        assertNull(modelToSend(form, editing = true))
        assertEquals(WhereToRun.Computer("tok1", "/home/user/project", "edit", null), whereToSend(form, editing = true))
    }

    @Test
    fun theWatchedCommandIsSaidWhenItChangesAndGoesWhenTheScheduleBecomesChat() {
        val on = read("schedule_on_device", ScheduleEnvelope.serializer()).schedule
        val read = EditState().read(on).copy(loaded = true)
        assertEquals("git log -1 --format=%H", read.watchCommand)
        assertEquals("30", read.watchTimeout)
        assertEquals(WatchCommand("git log -1 --format=%H", 30), read.watched)
        assertNull(watchToSend(read, editing = true))
        assertEquals(WatchCommand("git status --short", 30), watchToSend(read.copy(watchCommand = " git status --short "), editing = true))
        // Emptied: watch nothing. Seconds outside 1..600, or without a command, are not said.
        assertEquals(WatchCommand("", null), watchToSend(read.copy(watchCommand = ""), editing = true))
        assertNull(read.copy(watchTimeout = "900").watched.timeoutS)
        // A new one: only when a command was written.
        assertNull(watchToSend(EditState(agentic = true), editing = false))
        assertEquals(WatchCommand("make check", null), watchToSend(EditState(agentic = true, watchCommand = "make check"), editing = false))
        // Made Chat: the command cannot go along, the form says so, and the save takes it off.
        val chat = read.copy(agentic = false)
        assertTrue(chat.dropsWatch)
        assertEquals(WatchCommand("", null), watchToSend(chat, editing = true))
        assertFalse(read.dropsWatch)
        // A page a Chat schedule watches is not this form's: nothing is said of it.
        val watching = EditState().read(read("schedule_watching", ScheduleEnvelope.serializer()).schedule).copy(loaded = true)
        assertEquals("", watching.watchCommand)
        assertNull(watchToSend(watching, editing = true))
        // Not read: nothing.
        assertNull(watchToSend(EditState(agentic = true, watchCommand = "make check"), editing = true))
    }

    @Test
    fun onlyTheModesTheComputerAllowsAreOffered() {
        assertEquals(listOf("manual", "edit", "auto"), modesOffered("", "edit"))
        assertEquals(listOf("manual", "edit", "auto"), modesOffered("auto", "edit"))
        assertEquals(listOf("manual", "edit"), modesOffered("edit", "edit"))
        assertEquals(listOf("manual"), modesOffered("manual", "manual"))
        // The mode the schedule has stays shown, whatever the computer says now.
        assertEquals(listOf("manual", "auto"), modesOffered("manual", "auto"))
    }

    @Test
    fun aSchedulesOwnComputerStaysListedWhenItIsNotLinked() {
        val on = read("schedule_on_device", ScheduleEnvelope.serializer()).schedule
        // Read first, the computers after -- and the other way round.
        val readFirst = EditState().read(on).listed(listOf(laptop))
        assertEquals(listOf("tok2", "tok1"), readFirst.devices.map { it.id })
        assertEquals("bag-end", readFirst.devices.last().label)
        assertEquals("tok1", readFirst.device)
        val listedFirst = EditState().listed(listOf(laptop)).read(on)
        assertEquals(listOf("tok2", "tok1"), listedFirst.devices.map { it.id })
        // Linked: it is in the list once.
        assertEquals(listOf("tok1"), EditState().read(on).listed(listOf(workstation)).devices.map { it.id })
    }

    @Test
    fun whatThePersonChangedIsNotUndoneByTheRead() {
        val on = read("schedule_on_device", ScheduleEnvelope.serializer()).schedule
        val touched = EditState(agentic = false, whereTouched = true).read(on)
        assertFalse(touched.agentic)
        assertTrue(touched.wasAgentic)
    }

    @Test
    fun aRunSaysWhereItRanAndHowItsModelWasChosen() {
        val run = read("schedule_run_agentic", RunResult.serializer()).run
        val where = run.ranOn!!
        assertEquals("workstation", where.deviceName)
        assertEquals("/home/user/project", where.cwd)
        assertEquals("edit", where.mode)
        assertEquals("directory", run.model.reason)
        assertNotNull(runModelWhy(run.model.reason))
        assertNotNull(runModelWhy("default"))
        assertNotNull(runModelWhy("session"))
        // A pinned model needs no word, and a switched one has its own sentence.
        assertNull(runModelWhy("pinned"))
        assertNull(runModelWhy("switched"))
        assertNull(runModelWhy(null))
        // A Chat schedule's run ran nowhere but here.
        assertNull(read("schedule_run", RunResult.serializer()).run.ranOn)
    }

    @Test
    fun aSaveCarriesTheTypeAsRunsOn() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            val api = ApiClient(server.url("/").toString().trimEnd('/'), "lmb_test")
            val compiled =
                ScheduleCompiled(
                    ok = true,
                    title = "Nightly tests",
                    instruction = "Run the tests.",
                    rule = JsonObject(mapOf("every" to JsonObject(emptyMap()))),
                )
            repeat(3) { server.enqueue(MockResponse().setBody(text("schedule_on_device"))) }
            fun sent(): JsonObject = LembasJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

            api.saveSchedule(
                compiled,
                "every night",
                where = WhereToRun.Computer("tok1", "/home/user/project", "edit", null),
                model = "",
                watch = WatchCommand("make check", 60),
            )
            val agentic = sent()
            val watch = agentic.getValue("watch").jsonObject
            assertEquals("command", watch.getValue("kind").jsonPrimitive.content)
            assertEquals("make check", watch.getValue("command").jsonPrimitive.content)
            assertEquals("60", watch.getValue("timeout_s").jsonPrimitive.content)
            val on = agentic.getValue("runs_on").jsonObject
            assertEquals("tok1", on.getValue("device").jsonPrimitive.content)
            assertEquals("/home/user/project", on.getValue("cwd").jsonPrimitive.content)
            assertEquals("edit", on.getValue("mode").jsonPrimitive.content)
            assertFalse("approval_wait_minutes" in on)
            // The empty model is the folder's usual one: said, so a model set before is taken off.
            assertEquals("", agentic.getValue("model").jsonPrimitive.content)

            api.saveSchedule(compiled, "every night", id = "sched3", where = WhereToRun.Server, watch = WatchCommand("", null))
            val chat = sent()
            assertEquals(JsonNull, chat.getValue("runs_on"))
            assertEquals(JsonNull, chat.getValue("watch"))
            assertFalse("model" in chat)

            api.saveSchedule(compiled, "every night", id = "sched3")
            val left = sent()
            assertFalse("runs_on" in left)
            assertFalse("model" in left)
            assertFalse("watch" in left)
        } finally {
            server.shutdown()
        }
    }
}
