package eu.llembas.android

import eu.llembas.android.api.CardItem
import eu.llembas.android.api.LembasJson
import eu.llembas.android.api.LiveReply
import eu.llembas.android.api.Message
import eu.llembas.android.api.Progress
import eu.llembas.android.api.ProgressWords
import eu.llembas.android.api.ToolCall
import eu.llembas.android.api.callDuration
import eu.llembas.android.api.end
import eu.llembas.android.api.localeNumber
import eu.llembas.android.api.progressLine
import eu.llembas.android.api.progressText
import eu.llembas.android.api.shortTitle
import eu.llembas.android.api.state
import eu.llembas.android.ui.chat.planApproval
import kotlinx.serialization.json.JsonNull
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
 * One display in every client (harness spec 3.7.0, `display.md`), as far as the phone draws it:
 * a finished call's duration, the line that stays on a reply that was stopped or failed, a title
 * that was cut, and numbers in the phone's format. The values are the spec's own examples; once
 * the server's copy of the spec carries `conformance/display.json` it is pinned as
 * `contract/harness_display.json` and its `duration` and `end` cases run here too.
 */
class DisplayTest {
    private val english: ProgressWords by lazy {
        val xml = File("src/main/res/values/strings.xml").readText()
        val strings = Regex("""<string name="progress_([a-z_]+)"[^>]*>(.*?)</string>""").findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'").replace("&amp;", "&") }
        val keys = mapOf("stopped_reason_person" to "stopped_person", "stopped_reason_steps" to "stopped_steps", "stopped_reason_budget" to "stopped_budget")
        ProgressWords { key, args -> strings[keys[key] ?: key]?.let { String.format(Locale.ROOT, it, *args) } }
    }

    @Test
    fun aFinishedCallSaysHowLongItTook() {
        assertEquals("0.4s", callDuration("done", 412))
        assertEquals("8.4s", callDuration("done", 8_449))
        assertEquals("59.9s", callDuration("failed", 59_940))
        assertEquals("1m 05s", callDuration("done", 65_000))
        assertEquals("2h 03m", callDuration("done", 7_380_000))
        assertEquals("1.2s", callDuration("failed", 1_200))
        // Never a running call, never a denied one, and none where the time was not kept.
        assertNull(callDuration("denied", 3))
        assertNull(callDuration("running", 5_000))
        assertNull(callDuration("done", null))
        // A duration is not a locale number: a decimal point on every phone.
        val phone = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("sk-SK"))
            assertEquals("8.4s", callDuration("done", 8_449))
        } finally {
            Locale.setDefault(phone)
        }
    }

    @Test
    fun aCallIsInOneOfFourStates() {
        assertEquals("done", ToolCall(name = "bash", status = "ok").state())
        assertEquals("running", ToolCall(name = "bash", status = "running").state())
        assertEquals("failed", ToolCall(name = "bash", status = "error").state())
        assertEquals("failed", ToolCall(name = "bash", status = "ok", error = "exit 2").state())
        assertEquals("denied", ToolCall(name = "bash", status = "denied").state())
        // The call's time comes with the call (the link's `ms`), and an older server sends none.
        assertEquals(8_449L, LembasJson.decodeFromString(ToolCall.serializer(), """{"name":"bash","status":"ok","ms":8449}""").ms)
        assertNull(LembasJson.decodeFromString(ToolCall.serializer(), """{"name":"bash","status":"ok"}""").ms)
    }

    private fun reply(stopped: Boolean = false, error: String? = null, progress: Progress? = null) =
        Message(id = "m", role = "assistant", stopped = stopped, error = error, progress = progress)

    private fun line(message: Message): String? = message.end()?.let { progressText(it, english, 0, Locale.ROOT) }

    @Test
    fun aReplyThatDidNotFinishKeepsItsLine() {
        assertEquals("Stopped: by you", line(reply(stopped = true, progress = Progress(phase = "stopped", reason = "person"))))
        assertEquals("Stopped: step limit", line(reply(stopped = true, progress = Progress(phase = "stopped", reason = "steps"))))
        assertEquals("Stopped: budget", line(reply(stopped = true, progress = Progress(phase = "stopped", reason = "budget"))))
        // A stored row knows that it was stopped, not why: the line without a reason.
        assertEquals("Stopped", line(reply(stopped = true)))
        // An error: its first line is the reason, and the line is the spec's, not the raw text.
        assertEquals("Failed: 502 Bad Gateway", line(reply(error = "502 Bad Gateway\n  at upstream")))
        // A reply that finished shows nothing; nor does a user's message, or one that is only queued.
        assertNull(reply().end())
        assertNull(Message(id = "u", role = "user", stopped = true).end())
        assertNull(reply(progress = Progress(phase = "queued", ahead = 1)).end())
    }

    @Test
    fun theStreamsReasonStaysWithTheFinishedReply() {
        val stopped = """{"text": "", "progress": {"phase": "stopped", "since": 1767323045000, "reason": "person"}}"""
        val done = LembasJson.parseToJsonElement(File("../contract/event_done.json").readText()).jsonObject
        fun doneWith(change: (MutableMap<String, kotlinx.serialization.json.JsonElement>) -> Unit): String {
            val message = done.getValue("message").jsonObject.toMutableMap().also(change)
            return JsonObject(done + ("message" to JsonObject(message))).toString()
        }
        // Stopped by the person: the stream said why, the row only that it was stopped.
        val live = LiveReply("msg2").apply("status", stopped)
        val ended = live.apply("done", doneWith { it["stopped"] = kotlinx.serialization.json.JsonPrimitive(true) })
        assertNull(ended.progress)
        assertEquals("Stopped: by you", line(ended.finished!!))
        // A reply that finished after all keeps nothing of an earlier stop.
        assertNull(live.apply("done", doneWith { }).finished!!.end())
        // The phase moved on before the end (a retry that then worked): no end is remembered.
        val moved = live.apply("status", """{"text": "Writing", "progress": {"phase": "writing", "since": 1}}""")
        assertNull(moved.end)
        assertTrue(done.getValue("message").jsonObject["error"] is JsonNull)
    }

    @Test
    fun aTitleThatWasCutSaysSo() {
        assertEquals("bake --slow", shortTitle("  bake --slow\n--and more  "))
        assertNull(shortTitle("   "))
        assertNull(shortTitle(null))
        val long = "x".repeat(81)
        assertEquals("x".repeat(79) + "…", shortTitle(long))
        assertEquals("x".repeat(80), shortTitle("x".repeat(80)))
        // Counted in code points: a character outside the basic plane is one, and is never split.
        val leaves = "\uD83C\uDF43".repeat(81)
        assertEquals("\uD83C\uDF43".repeat(79) + "…", shortTitle(leaves))
        val running = Progress(phase = "running_tool", since = 0, tool = "bash", what = long)
        assertEquals("Running bash: ${"x".repeat(79)}… · 0s", progressLine(running, english, 0, Locale.ROOT))
    }

    @Test
    fun numbersAreInThePhonesFormat() {
        val reading = Progress(phase = "reading_prompt", since = 0, done = 12_480, total = 32_000)
        assertEquals("Reading the prompt: 12480 / 32000 tokens (39%)", progressText(reading, english, 0, Locale.ROOT))
        assertEquals("Reading the prompt: 12,480 / 32,000 tokens (39%)", progressText(reading, english, 0, Locale.US))
        assertEquals("Reading the prompt: 12.480 / 32.000 tokens (39%)", progressText(reading, english, 0, Locale.GERMANY))
        assertEquals("Writing: 1.234,5 tok/s", progressText(Progress(phase = "writing", since = 0, rate = 1234.46), english, 0, Locale.GERMANY))
        assertEquals("Queued: 1,200 ahead", progressText(Progress(phase = "queued", since = 0, ahead = 1200), english, 0, Locale.US))
        assertEquals("12480", localeNumber(12_480, Locale.ROOT))
        assertEquals("12.5", localeNumber(12.46, 1, Locale.ROOT))
    }

    @Test
    fun aPlanIsApprovedWithTheModeToContinueIn() {
        val choose = CardItem(key = "p0", kind = "plan", modeChoice = true)
        assertEquals(mapOf("p0" to listOf("edit")), planApproval(choose, "edit").choices)
        assertEquals(mapOf("p0" to listOf("manual")), planApproval(choose, "manual").choices)
        assertEquals("allow", planApproval(choose, "manual").verdict)
        // A session that may not choose a mode approves the plan alone.
        assertTrue(planApproval(CardItem(key = "p0", kind = "plan", modeChoice = false), null).choices.isEmpty())
    }

    @Test
    fun theSpecsOwnCasesOnceTheyArePinned() {
        val file = javaClass.classLoader!!.getResource("harness_display.json") ?: return
        val cases = LembasJson.parseToJsonElement(file.readText()).jsonObject.getValue("cases").jsonArray.map { it.jsonObject }
        for (case in cases) {
            val about = case["about"]?.jsonPrimitive?.content
            val expect = case.getValue("expect").jsonObject
            when (case.getValue("show").jsonPrimitive.content) {
                "duration" -> {
                    val want = expect.getValue("duration").let { if (it is JsonNull) null else it.jsonPrimitive.content }
                    assertEquals(about, want, callDuration(case.getValue("state").jsonPrimitive.content, case["ms"]?.jsonPrimitive?.long))
                }

                "end" -> {
                    val progress = case.getValue("progress").let { if (it is JsonNull) null else LembasJson.decodeFromJsonElement(Progress.serializer(), it) }
                    val want = expect.getValue("line").let { if (it is JsonNull) null else it.jsonPrimitive.content }
                    val message = Message(id = "m", role = "assistant", progress = progress)
                    assertEquals(about, want, message.end()?.let { progressLine(it, english, case.getValue("now").jsonPrimitive.long, Locale.ROOT) })
                }
            }
        }
    }
}
