package eu.llembas.android

import eu.llembas.android.api.ActivityEnvelope
import eu.llembas.android.api.ActivityFrame
import eu.llembas.android.api.ActivityLogic
import eu.llembas.android.api.ActivitySummary
import eu.llembas.android.api.AgentDetail
import eu.llembas.android.api.AgentsSetting
import eu.llembas.android.api.ApiClient
import eu.llembas.android.api.Chat
import eu.llembas.android.api.ContextUse
import eu.llembas.android.api.LembasJson
import eu.llembas.android.api.ModelInfo
import eu.llembas.android.api.ModelList
import eu.llembas.android.api.TeamEnvelope
import kotlinx.serialization.KSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The beta.5 `/api/v2` (server dev, pinned in `contract/`): the models' levels and pictures, a
 * chat's team size, and the activity panel -- parsed with the app's models, then the panel's
 * arithmetic on what they said.
 */
class ActivityTest {
    private fun <T> read(name: String, serializer: KSerializer<T>): T {
        val text = requireNotNull(javaClass.classLoader!!.getResource("$name.json")) { "contract/$name.json is missing" }.readText()
        return LembasJson.decodeFromString(serializer, text)
    }

    @Test
    fun theModelsSayTheirLevelsAndControl() {
        val (qwen, gptOss, vision) = read("models", ModelList.serializer()).models
        assertEquals(listOf("off", "medium"), qwen.offered)
        assertEquals("switch", qwen.levelControl)
        assertTrue(qwen.thinkingSwitch)
        assertEquals("medium", qwen.onLevel)
        assertEquals(2, qwen.parallel)
        assertEquals("/api/v2/models/images/0a1b2c3d4e5f.png", qwen.image)
        assertTrue(qwen.mayHaveTeam)

        assertEquals("slider", gptOss.levelControl)
        assertEquals("high", gptOss.level(null))
        assertEquals("low", gptOss.level("low"))
        // A level the model does not offer (an `off` from before) reads as its start level.
        assertEquals("high", gptOss.level("off"))
        assertFalse(gptOss.mayHaveTeam)
        assertNull(gptOss.image)

        assertEquals("none", vision.levelControl)
        assertEquals("default", vision.level(null))
        // No stated limit, but no tools: no team.
        assertNull(vision.parallel)
        assertFalse(vision.mayHaveTeam)
    }

    @Test
    fun anOlderServersModelStillGetsAControl() {
        // Before beta.5: `efforts` only. The app offered "off" before them, as it did then.
        val old = ModelInfo(id = "m", efforts = listOf("low", "high"))
        assertEquals(listOf("off", "low", "high"), old.offered)
        assertEquals("slider", old.levelControl)
        assertEquals("none", ModelInfo(id = "n").levelControl)
        assertEquals("switch", ModelInfo(id = "s", efforts = listOf("medium")).levelControl)
    }

    @Test
    fun aPictureIsOnlyTheInstancesOwn() {
        val base = "https://example.org"
        assertEquals("https://example.org/api/v2/models/images/0a1b.png", ApiClient.modelImageUrl(base, "/api/v2/models/images/0a1b.png"))
        assertNull(ApiClient.modelImageUrl(base, null))
        assertNull(ApiClient.modelImageUrl(base, "https://elsewhere.example/x.png"))
        assertNull(ApiClient.modelImageUrl(base, "/api/v2/models/images/../../me"))
        assertNull(ApiClient.modelImageUrl(base, "/api/v2/models/images/a/b.png"))
        assertNull(ApiClient.modelImageUrl(base, "/api/v2/models/images/.hidden"))
        assertNull(ApiClient.modelImageUrl(base, "/uploads/models/x.png"))
    }

    @Test
    fun aChatSaysItsTeamSizeAndDeviceSession() {
        val chat = read("chat", Chat.serializer())
        assertEquals(AgentsSetting(1, 4), chat.agents)
        assertNull(chat.deviceSessionId)
        assertEquals("ses_01waybread", read("chat_agent", Chat.serializer()).deviceSessionId)
    }

    @Test
    fun theActivityPanel() {
        val activity = read("activity", ActivityEnvelope.serializer()).activity
        assertEquals("0123456789abcdef", activity.version)
        assertEquals(3, activity.summary.active)
        assertTrue(activity.teamOffered)

        val (baker, job) = activity.agents
        assertEquals("member", baker.kind)
        assertEquals("read", baker.step?.tool)
        assertTrue(baker.step!!.running)
        assertEquals(listOf("wrapper", "baker:job1"), baker.children.map { it.id })
        assertEquals("command", job.type)
        assertFalse(job.transcript)

        // The tree, flattened in order for drawing; the split is by the top level's state.
        assertEquals(listOf("baker", "wrapper", "baker:job1", "job1"), ActivityLogic.flatten(activity.agents).map { it.id })
        val (live, ended) = ActivityLogic.split(activity.agents)
        assertEquals(2, live.size)
        assertTrue(ended.isEmpty())

        val team = activity.team!!
        assertEquals(3, team.size)
        assertEquals(2, team.cursor)
        assertTrue(team.room[1].report)
        assertEquals(listOf("baker"), team.room[1].to)

        val context = activity.context!!
        assertEquals(128000, context.limit)
        assertEquals(listOf("messages", "system"), ActivityLogic.barParts(context).map { it.key })
        assertEquals("/home/frodo/recipe.md", activity.outputs.single().path)
        assertEquals(42, activity.outputs.single().added)
        assertNull(ActivityLogic.plan(activity.plan))
    }

    @Test
    fun anAgentsConversationAndTheTeamAndTheEvent() {
        val detail = read("agent", AgentDetail.serializer())
        assertEquals("baker", detail.agent.name)
        assertEquals(listOf("user", "assistant"), detail.transcript.map { it.role })
        assertEquals("recipe.md", detail.transcript[1].tools.single().title)
        assertTrue(detail.transcript[1].running)

        val team = read("team", TeamEnvelope.serializer()).team
        assertTrue(team.offered)
        assertEquals(listOf(0, 1, 2), team.agents.map { it.depth })
        assertNull(team.agents[0].manager)

        val frame = read("event_activity", ActivityFrame.serializer())
        assertEquals("0123456789abcdef", frame.version)
        assertEquals(2, frame.summary.working)
    }

    @Test
    fun theStripCountsWhatIsGoing() {
        val summary = ActivitySummary(queued = 1, working = 3, waiting = 0, done = 4, finished = 4, active = 4, total = 8)
        assertEquals(listOf("working" to 3, "queued" to 1, "finished" to 4), ActivityLogic.stripCounts(summary))
        assertTrue(ActivityLogic.stripCounts(ActivitySummary(done = 2, finished = 2)).isEmpty())
    }

    @Test
    fun autoCompactIsCountedOnlyAgainstAKnownWindow() {
        val context = read("activity", ActivityEnvelope.serializer()).activity.context!!
        // 95 % of 128k is 121.6k; 41k used.
        assertEquals(80_600, ActivityLogic.untilAutoCompact(context))
        assertEquals(0, ActivityLogic.untilAutoCompact(context.copy(used = 127_000)))
        assertNull(ActivityLogic.untilAutoCompact(context.copy(threshold = 0)))
        assertNull(ActivityLogic.untilAutoCompact(context.copy(limit = 0)))
    }

    @Test
    fun aTaskIsCalledByWhatItWasAskedToDo() {
        val activity = read("activity", ActivityEnvelope.serializer()).activity
        val (baker, command) = activity.agents
        assertEquals("baker", ActivityLogic.title(baker))
        assertEquals("find mallorn leaves", ActivityLogic.title(baker.children[1]))
        assertEquals("pytest", ActivityLogic.title(command))
        assertEquals(2, ActivityLogic.descendants(baker))
    }

    @Test
    fun numbersAsTheServerWritesThem() {
        assertEquals("950", ActivityLogic.compactTokens(950))
        assertEquals("14.2k", ActivityLogic.compactTokens(14_200))
        assertEquals("41k", ActivityLogic.compactTokens(41_000))
        assertEquals("180k", ActivityLogic.compactTokens(180_400))
        assertEquals("1.54M", ActivityLogic.compactTokens(1_540_000))
        assertEquals("1M", ActivityLogic.compactTokens(1_000_000))
        assertEquals("5s", ActivityLogic.duration(5))
        assertEquals("1m 05s", ActivityLogic.duration(65))
        assertEquals("2h 03m", ActivityLogic.duration(7_380))
        assertEquals("32", ActivityLogic.percent(32.0))
        assertEquals("23.4", ActivityLogic.percent(23.4))
    }

    @Test
    fun elapsedTimeRunsOnOnlyForOneThatIsGoing() {
        val activity = read("activity", ActivityEnvelope.serializer()).activity
        val (baker, _) = activity.agents
        val wrapper = baker.children[0]
        val subagent = baker.children[1]
        assertEquals(105L, ActivityLogic.elapsed(baker, readAtMillis = 1_000, nowMillis = 11_000))
        assertEquals(0L, ActivityLogic.elapsed(wrapper, readAtMillis = 1_000, nowMillis = 11_000))
        assertEquals(12L, ActivityLogic.elapsed(subagent, readAtMillis = 1_000, nowMillis = 11_000))
    }

    @Test
    fun noWindowNoBar() {
        assertTrue(ActivityLogic.barParts(ContextUse(used = 10, limit = 0)).isEmpty())
    }
}
