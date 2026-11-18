package eu.llembas.android

import eu.llembas.android.api.ApiClient
import eu.llembas.android.api.LembasJson
import eu.llembas.android.api.LinkedDevice
import eu.llembas.android.api.RunResult
import eu.llembas.android.api.ScheduleCompiled
import eu.llembas.android.api.ScheduleEnvelope
import eu.llembas.android.api.WhereToRun
import eu.llembas.android.api.agentic
import eu.llembas.android.ui.scheduled.EditState
import eu.llembas.android.ui.scheduled.agenticOffered
import eu.llembas.android.ui.scheduled.listed
import eu.llembas.android.ui.scheduled.read
import eu.llembas.android.ui.scheduled.runModelWhy
import eu.llembas.android.ui.scheduled.waitMinutes
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
        assertEquals(WhereToRun.Computer("tok1", "/home/user/project", "auto", 45, ""), whereToSend(agentic, editing = false))
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
        assertEquals(WhereToRun.Computer("tok1", "/home/frodo/waybread", "edit", 30, ""), whereToSend(read, editing = true))
        // Made a Chat schedule: the server is told so. A Chat schedule left alone: nothing.
        assertEquals(WhereToRun.Server, whereToSend(read.copy(agentic = false), editing = true))
        val chat = EditState().read(read("schedule", ScheduleEnvelope.serializer()).schedule).copy(loaded = true)
        assertFalse(chat.agentic)
        assertEquals(WhereToRun.Leave, whereToSend(chat, editing = true))
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

            api.saveSchedule(compiled, "every night", where = WhereToRun.Computer("tok1", "/home/user/project", "edit", null, ""))
            val agentic = sent()
            val on = agentic.getValue("runs_on").jsonObject
            assertEquals("tok1", on.getValue("device").jsonPrimitive.content)
            assertEquals("/home/user/project", on.getValue("cwd").jsonPrimitive.content)
            assertEquals("edit", on.getValue("mode").jsonPrimitive.content)
            assertFalse("approval_wait_minutes" in on)
            // The empty model is the folder's usual one: said, so a model set before is taken off.
            assertEquals("", agentic.getValue("model").jsonPrimitive.content)

            api.saveSchedule(compiled, "every night", id = "sched3", where = WhereToRun.Server)
            assertEquals(JsonNull, sent().getValue("runs_on"))

            api.saveSchedule(compiled, "every night", id = "sched3")
            val left = sent()
            assertFalse("runs_on" in left)
            assertFalse("model" in left)
        } finally {
            server.shutdown()
        }
    }
}
