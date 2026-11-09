package eu.llembas.android

import eu.llembas.android.api.LembasJson
import eu.llembas.android.api.PushPayload
import eu.llembas.android.api.RulePreview
import eu.llembas.android.api.RunEnvelope
import eu.llembas.android.api.RunResult
import eu.llembas.android.api.Schedule
import eu.llembas.android.api.ScheduleCompiled
import eu.llembas.android.api.ScheduleEnvelope
import eu.llembas.android.api.ScheduleList
import eu.llembas.android.api.ScheduleRuns
import eu.llembas.android.api.SchedulesFrame
import eu.llembas.android.api.SchedulingModels
import eu.llembas.android.push.ScheduledNotice
import eu.llembas.android.ui.scheduled.SchedulingLogic
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server's scheduling contract (`contract/schedule*.json`, `event_schedules.json`, written
 * by its serialisers): every shape parses into this app's models and says what it said. And the
 * push a scheduled run sends (services/schedule/notify.py `payload`), read into a notification.
 */
class ScheduleContractTest {
    private fun text(name: String): String = requireNotNull(javaClass.classLoader!!.getResource("$name.json")) { "contract/$name.json is missing" }.readText()

    private fun <T> read(name: String, serializer: KSerializer<T>): T = LembasJson.decodeFromString(serializer, text(name))

    /** The schedule every fixture carries: sched1, "Waybread check", with run1 as its last run. */
    private fun assertWaybread(s: Schedule) {
        assertEquals("sched1", s.id)
        assertEquals("Waybread check", s.title)
        assertEquals("Check the waybread is wrapped.", s.instruction)
        assertEquals("every second Monday at 7:30", s.request)
        assertEquals("On the second Monday of each month at 07:30", s.description)
        assertEquals("UTC", s.timezone)
        assertTrue(s.enabled)
        assertFalse(s.paused)
        assertEquals("2026-01-12T07:30:00+00:00", s.nextRun)
        assertEquals(5, s.nextRuns.size)
        assertEquals("fresh", s.mode)
        assertEquals("matters", s.notify)
        assertEquals("one", s.catchUp)
        assertNull(s.graceMinutes)
        assertEquals("qwen", s.model)
        assertEquals("high", s.effort)
        assertNull(s.agents)
        assertTrue("plan" in s.tools)
        assertEquals("chat1", s.chatId)
        assertEquals(3, s.firedCount)
        assertEquals(0, s.failureStreak)
        assertFalse(s.running)
        // The rule is the server's own form, kept whole to be handed back.
        val nth = s.rule.getValue("at").jsonObject.getValue("nth").jsonArray.single().jsonObject
        assertEquals(2, nth.getValue("n").jsonPrimitive.int)

        val run = s.lastRun!!
        assertEquals("run1", run.id)
        assertEquals("sched1", run.scheduleId)
        assertEquals("ok", run.status)
        assertEquals("late", run.timing)
        assertEquals("GPT-OSS", run.model.label)
        assertEquals("switched", run.model.reason)
        assertEquals("Qwen", run.model.switchedFrom)
        assertEquals(1200L, run.tokens.prompt)
        assertNull(run.cost)
        assertNull(run.error)
        assertEquals("msg2", run.messageId)
        assertEquals("All the waybread is wrapped.", run.preview)
        assertTrue(run.notified)
    }

    @Test
    fun theListOneScheduleAndTheEventFrame() {
        assertWaybread(read("schedules", ScheduleList.serializer()).schedules.single())
        assertWaybread(read("schedule", ScheduleEnvelope.serializer()).schedule)
        val frame = read("event_schedules", SchedulesFrame.serializer())
        assertEquals("597aab1ff7b62194", frame.version)
        assertWaybread(frame.schedules.single())
    }

    @Test
    fun theRunsAWaitingOneAndOneInFull() {
        val page = read("schedule_runs", ScheduleRuns.serializer())
        assertFalse(page.more)
        val (waiting, done) = page.runs
        assertEquals("waiting", waiting.status)
        assertEquals("model", waiting.waitingFor)
        assertEquals("2026-01-02T03:09:05+00:00", waiting.retryAt)
        assertEquals(2, waiting.missed)
        assertEquals(2, waiting.attempts)
        assertNull(waiting.model.label)
        assertNull(waiting.chatId)
        assertEquals("no_model", waiting.error?.kind)
        assertEquals("Waiting for a free model: Qwen: it is answering someone.", waiting.error?.text)
        assertEquals("run1", done.id)

        val one = read("schedule_run", RunResult.serializer())
        assertEquals("run1", one.run.id)
        assertEquals("chat1", one.run.chatId)
        assertEquals("All the waybread is *wrapped*.", one.text)

        // `POST /schedules/{id}/run` answers 202 with the run alone.
        val started = LembasJson.decodeFromString(RunEnvelope.serializer(), """{"run":{"id":"run3","status":"running","timing":"manual"}}""")
        assertEquals("manual", started.run.timing)
    }

    @Test
    fun theCompileAndThePreview() {
        val compiled = read("schedule_compile", ScheduleCompiled.serializer())
        assertTrue(compiled.ok)
        assertNull(compiled.reason)
        assertEquals("Waybread check", compiled.title)
        assertEquals("chat", compiled.target)
        assertEquals("On the second Monday of each month at 07:30", compiled.description)
        assertEquals(5, compiled.nextRuns.size)
        assertTrue(compiled.savable)

        // A rule that cannot run comes back empty, in no words, with no runs: not savable.
        val preview = read("schedule_preview", RulePreview.serializer())
        assertTrue(preview.rule.isEmpty())
        assertNull(preview.description)
        assertTrue(preview.nextRuns.isEmpty())
        assertFalse(ScheduleCompiled(ok = false, reason = "Say what you want to happen.", instruction = "x").savable)
    }

    @Test
    fun theSchedulingModelsAndANarrowing() {
        val models = read("schedule_models", SchedulingModels.serializer())
        val (gpt, qwen) = models.models
        assertEquals("gpt-oss", gpt.id)
        assertFalse(gpt.free)
        assertEquals("it is answering someone", gpt.busy)
        assertTrue(gpt.chosen)
        assertTrue(qwen.free)
        assertNull(qwen.busy)
        assertFalse(qwen.chosen)
        assertEquals("/api/v2/models/images/0a1b2c3d4e5f.png", qwen.image)
        assertEquals(listOf("gpt-oss", "qwen"), models.instance)
        assertEquals(listOf("gpt-oss"), models.mine)

        // Ticking Qwen ticks every one: no narrowing at all. Unticking the last one: refused.
        assertEquals(emptyList<String>(), SchedulingLogic.toggled(models.models, "qwen"))
        assertNull(SchedulingLogic.toggled(models.models, "gpt-oss"))
        val all = models.models.map { it.copy(chosen = true) }
        assertEquals(listOf("gpt-oss"), SchedulingLogic.toggled(all, "qwen"))
    }

    @Test
    fun aResultsPushOpensTheRun() {
        // What notify.payload and push.announce send for a run that finished: ids, a fixed
        // sentence, never the result.
        val payload = LembasJson.decodeFromString(
            PushPayload.serializer(),
            """{"schedule_id":"sched1","run_id":"run1","chat_id":"chat1","status":"ok","visibility":"private",
               "actions":["open"],"ask":"","pause_label":"Pause","title":"LLeMbas",
               "body":"A scheduled task has finished.","url":"/scheduled/sched1?run=run1","kind":"schedule"}""",
        )
        val notice = ScheduledNotice.from(payload)!!
        assertEquals("sched1", notice.scheduleId)
        assertEquals("run1", notice.runId)
        assertFalse(notice.failed)
        assertTrue(notice.open)
        assertFalse(notice.pause)
        assertEquals("private", payload.visibility)
    }

    @Test
    fun aFailurePushOffersPauseAndAsksAfterThree() {
        val failed = LembasJson.decodeFromString(
            PushPayload.serializer(),
            """{"schedule_id":"sched1","run_id":"run2","status":"failed","visibility":"private","actions":["open","pause"],
               "ask":"pause","body":"A scheduled task failed 3 times in a row. Pause it?","url":"/scheduled/sched1?run=run2",
               "kind":"schedule_failed"}""",
        )
        val notice = ScheduledNotice.from(failed)!!
        assertTrue(notice.failed)
        assertTrue(notice.askPause)
        assertTrue(notice.pause)
        // A push about the same run replaces its notification; another run gets its own.
        assertEquals(notice.id, notice.copy(failed = false).id)
        assertTrue(notice.id != notice.copy(runId = "run3").id)
    }

    @Test
    fun aPushWithOnlyItsUrlAndOneThatIsNotScheduled() {
        val bare = PushPayload(kind = "schedule", url = "/scheduled/sched1?run=run1")
        val notice = ScheduledNotice.from(bare)!!
        assertEquals("sched1", notice.scheduleId)
        assertEquals("run1", notice.runId)
        assertTrue(notice.open)
        assertNull(ScheduledNotice.from(PushPayload(kind = "reply", chatId = "chat1")))
        assertNull(ScheduledNotice.from(PushPayload(kind = "schedule", url = "/chat/chat1")))
        assertEquals(null to null, ScheduledNotice.fromUrl("/elsewhere"))
    }
}
