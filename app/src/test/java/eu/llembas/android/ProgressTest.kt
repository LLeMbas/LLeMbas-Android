package eu.llembas.android

import eu.llembas.android.api.LembasJson
import eu.llembas.android.api.LiveReply
import eu.llembas.android.api.Progress
import eu.llembas.android.api.ProgressWords
import eu.llembas.android.api.RunResult
import eu.llembas.android.api.StatusFrame
import eu.llembas.android.api.elapsed
import eu.llembas.android.api.progressLine
import eu.llembas.android.api.progressText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * The progress line (harness spec 3.4.0, `progress.md`): the spec's own conformance cases
 * (`conformance/progress.json`, the file every client and the server test against, pinned as
 * `contract/harness_progress.json` from the server's copy of the spec), drawn with
 * the app's English strings as `res/values/strings.xml` has them -- so a string that drifted
 * from `loop.json`'s `progress.texts` fails here too.
 */
class ProgressTest {
    /** The app's English, read from the generated resources, formatted as Android formats it. */
    private val english: ProgressWords by lazy {
        val xml = File("src/main/res/values/strings.xml").readText()
        val strings = Regex("""<string name="progress_([a-z_]+)">(.*?)</string>""").findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'").replace("&amp;", "&") }
        val keys = mapOf("stopped_reason_person" to "stopped_person", "stopped_reason_steps" to "stopped_steps", "stopped_reason_budget" to "stopped_budget")
        ProgressWords { key, args -> strings[keys[key] ?: key]?.let { String.format(Locale.ROOT, it, *args) } }
    }

    private val cases: List<JsonObject> by lazy {
        // The server's vendored copy of the spec's file, pinned with the contract.
        val text = requireNotNull(javaClass.classLoader!!.getResource("harness_progress.json")) { "contract/harness_progress.json is missing" }.readText()
        LembasJson.parseToJsonElement(text).jsonObject.getValue("cases").jsonArray.map { it.jsonObject }
    }

    @Test
    fun everyConformanceCase() {
        assertEquals(25, cases.size)
        for (case in cases) {
            val progress = LembasJson.decodeFromJsonElement(Progress.serializer(), case.getValue("progress"))
            val now = case.getValue("now").jsonPrimitive.long
            val expect = case.getValue("expect").jsonObject
            val about = case["about"]?.jsonPrimitive?.content
            assertEquals(about, expect.getValue("text").jsonPrimitive.content, progressText(progress, english, now, Locale.ROOT))
            assertEquals(about, expect.getValue("line").jsonPrimitive.content, progressLine(progress, english, now, Locale.ROOT))
        }
    }

    @Test
    fun aClockWritesDurations() {
        assertEquals("0s", elapsed(-1500))
        assertEquals("59s", elapsed(59_999))
        assertEquals("1m 00s", elapsed(60_000))
        assertEquals("59m 59s", elapsed(3_599_000))
        assertEquals("1h 00m", elapsed(3_600_000))
        assertEquals("26h 03m", elapsed(93_780_000))
    }

    @Test
    fun aRateInThePersonsNumberFormat() {
        val writing = Progress(phase = "writing", since = 0, rate = 12.46)
        assertEquals("Writing: 12,5 tok/s", progressText(writing, english, 0, Locale.forLanguageTag("sk")))
    }

    @Test
    fun aPhaseThisAppDoesNotKnowFallsBackToStatus() {
        assertNull(progressLine(Progress(phase = "dreaming", since = 0), english, 0))
        // A required field missing: no half sentence, the status text instead.
        assertNull(progressLine(Progress(phase = "running_tool", since = 0), english, 0))
        assertNull(progressLine(Progress(phase = "waiting_for_model", since = 0, model = "coder"), english, 0))
    }

    @Test
    fun theStatusFrameCarriesProgress() {
        val frame = """{"text": "Running bake --slow", "progress": {"phase": "running_tool", "since": 1762790400000, "tool": "bash", "what": "bake --slow"}}"""
        val live = LiveReply("m").apply("status", frame)
        assertEquals("Running bake --slow", live.status)
        assertEquals("bake --slow", live.progress?.what)
        // An older server's frame: the text alone, no progress.
        val old = live.apply("status", """{"text": "Thinking"}""")
        assertEquals("Thinking", old.status)
        assertNull(old.progress)
        // Idle: progress null.
        assertNull(live.apply("status", """{"text": "", "progress": null}""").progress)
        // The reply ends: neither is kept.
        val done = LembasJson.parseToJsonElement(File("../contract/event_done.json").readText()).toString()
        assertNull(live.apply("done", done).progress)
    }

    @Test
    fun theContractsStatusFrames() {
        fun frame(name: String) = LembasJson.decodeFromString(
            StatusFrame.serializer(),
            requireNotNull(javaClass.classLoader!!.getResource("$name.json")).readText(),
        )
        val running = frame("event_status")
        assertEquals("Running bake --slow", running.text)
        val since = running.progress!!.since
        assertEquals("Running bash: bake --slow · 5s", progressLine(running.progress!!, english, since + 5_000, Locale.ROOT))
        assertEquals("Waiting for you: a question · 0s", progressLine(frame("event_status_person").progress!!, english, since, Locale.ROOT))
        // Stopped is its text alone, though the free text is empty: the progress is what is drawn.
        val stopped = frame("event_status_stopped")
        assertEquals("", stopped.text)
        assertEquals("Stopped: by you", progressLine(stopped.progress!!, english, since + 60_000, Locale.ROOT))
        assertNull(frame("event_status_idle").progress)
    }

    @Test
    fun aScheduledRunSaysItsProgress() {
        val text = requireNotNull(javaClass.classLoader!!.getResource("schedule_run.json")).readText()
        // The contract's run has finished: `progress` is there, and null.
        assertTrue(text.contains("\"progress\": null"))
        assertNull(LembasJson.decodeFromString(RunResult.serializer(), text).run.progress)
        val waiting =
            """{"run": {"id": "r", "status": "waiting", "progress": {"phase": "waiting_for_model", "since": 0, "model": "coder", "reason": "slots"}}}"""
        val run = LembasJson.decodeFromString(RunResult.serializer(), waiting).run
        assertEquals("Waiting for coder: all its slots are in use", progressText(run.progress!!, english, 0, Locale.ROOT))
    }
}
