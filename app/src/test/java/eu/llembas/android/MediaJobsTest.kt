package eu.llembas.android

import eu.llembas.android.api.LembasJson
import eu.llembas.android.api.LiveReply
import eu.llembas.android.api.MediaJob
import eu.llembas.android.api.MediaJobList
import eu.llembas.android.api.Message
import eu.llembas.android.api.Raw
import eu.llembas.android.api.Step
import eu.llembas.android.api.ToolCall
import eu.llembas.android.ui.chat.ChatMedia
import eu.llembas.android.ui.chat.ChatState
import eu.llembas.android.ui.chat.JobSeen
import eu.llembas.android.ui.chat.MediaActions
import eu.llembas.android.ui.chat.awaitsTelling
import eu.llembas.android.ui.chat.job
import eu.llembas.android.ui.chat.jobRefs
import eu.llembas.android.ui.chat.jobRefsOf
import eu.llembas.android.ui.chat.jobsToRead
import eu.llembas.android.ui.chat.withJobs
import eu.llembas.android.ui.chat.woke
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a chat follows its long generations: which jobs it reads again, and when a job has woken it. */
class MediaJobsTest {
    private fun contract(name: String): String = requireNotNull(javaClass.classLoader!!.getResource("$name.json")) {
        "contract/$name.json is missing"
    }.readText()

    private fun job(name: String) = LembasJson.decodeFromString(MediaJob.serializer(), contract(name))

    /** A reply whose one round of tools is the pinned picture call and the pinned video call. */
    private val reply: Message
        get() {
            val calls = LembasJson.parseToJsonElement(contract("tool_media")).jsonObject.getValue("calls")
            val read = LembasJson.decodeFromJsonElement(ListSerializer(ToolCall.serializer()), calls)
            return Message(id = "msg2", role = "assistant", steps = listOf(Step(index = 0, kind = "tools", calls = read)))
        }

    @Test
    fun theJobsACallStartedAreFoundOnceWhereverTheCallIs() {
        assertEquals(listOf("mjob1"), jobRefsOf(listOf(reply)).map { it.id })
        // In the reply being written too, a call under another call, and never the same job twice.
        val nested = ToolCall(name = "agent", children = reply.steps[0].calls)
        val live = listOf(Step(index = 0, kind = "tools", calls = listOf(nested)))
        assertEquals(listOf("mjob1"), jobRefsOf(listOf(reply), live).map { it.id })
        assertEquals(listOf("mjob1"), jobRefsOf(emptyList(), live).map { it.id })
        assertEquals(listOf("mjob1"), ChatState(live = LiveReply("msg3", steps = live)).jobRefs.map { it.id })
        assertTrue(jobRefsOf(listOf(Message(id = "m", role = "user", text = "hello"))).isEmpty())
    }

    @Test
    fun aJobIsReadAgainUntilItHasEnded() {
        val state = ChatState(messages = listOf(reply))
        // Named by a call and never read: the call's own word is from the moment it returned.
        assertEquals(listOf("mjob1"), state.jobsToRead())
        val running = state.withJobs(listOf(job("media_job")), at = 1000)
        assertEquals(listOf("mjob1"), running.jobsToRead())
        // Ended: left alone, whatever the call still says.
        val done = running.withJobs(listOf(job("media_job_done").copy(id = "mjob1")), at = 4000)
        assertTrue(done.jobsToRead().isEmpty())
        assertEquals("done", done.mediaJobs.getValue("mjob1").job.state)
        assertEquals(4000L, done.mediaJobs.getValue("mjob1").at)
        // A job the chat's list has that no call on this page names is followed while it is under way.
        val listed = ChatState().withJobs(LembasJson.decodeFromString(MediaJobList.serializer(), contract("media_jobs")).jobs, at = 1000)
        assertEquals(listOf("mjob1"), listed.jobsToRead())
        assertEquals(setOf("mjob1", "mjob2", "mjob3"), listed.mediaJobs.keys)
        assertEquals(ChatState(), ChatState().withJobs(emptyList(), at = 1))
    }

    @Test
    fun aJobThatToldTheChatHasWokenIt() {
        val running = job("media_job")
        val ended = job("media_job_done").copy(told = false)
        val told = job("media_job_done")
        // The end and the telling may be two reads apart: only the telling says there is a reply to read.
        assertFalse(woke(running, ended))
        assertTrue(awaitsTelling(ended))
        assertTrue(woke(ended, told))
        assertTrue(woke(running, told))
        assertFalse(awaitsTelling(told))
        // Told already at the last read, or first heard of as told: the transcript in hand has it.
        assertFalse(woke(told, told))
        assertFalse(woke(null, told))
        // A failure wakes the chat too; a job the person stopped wakes nobody and is not waited for.
        assertTrue(woke(running, job("media_job_failed")))
        assertFalse(awaitsTelling(running))
        assertFalse(awaitsTelling(MediaJob(id = "j", state = "cancelled")))
    }

    @Test
    fun theClockOnTheCardRunsOnFromTheRead() {
        val seen = JobSeen(job("media_job"), at = 10_000)
        assertEquals(42L, seen.elapsed(10_000))
        assertEquals(47L, seen.elapsed(15_999))
        // A clock that went back, or a job never read, says what the server said.
        assertEquals(42L, seen.elapsed(5_000))
        assertEquals(42L, JobSeen(job("media_job")).elapsed(99_000))
        // An ended job took what it took.
        assertEquals(95L, JobSeen(job("media_job_done"), at = 10_000).elapsed(500_000))
    }

    @Test
    fun aCardIsDrawnFromWhatWasReadElseFromTheCall() {
        val ref = jobRefsOf(listOf(reply)).single()
        val unread = ChatMedia(emptyMap(), object : MediaActions {})
        assertEquals("running", unread.job(ref).job.state)
        assertTrue(unread.job(ref).job.underWay)
        assertEquals(0L, unread.job(ref).at)
        val read = ChatMedia(mapOf("mjob1" to JobSeen(job("media_job_failed").copy(id = "mjob1"), at = 5)), object : MediaActions {})
        assertEquals("failed", read.job(ref).job.state)
        assertEquals("ComfyUI ran out of video memory in KSampler.", read.job(ref).job.error)
    }

    @Test
    fun aGeneratedFileIsAskedForByItsId() {
        val done = job("media_job_done").result!!
        assertEquals(Raw.Node("node7"), done.raw)
        assertEquals("/home/frodo/Generated", done.folder)
        assertEquals("video-20260102-030405.mp4", done.title)
        // A result without an id is asked for by where it was written.
        assertEquals(Raw.Path("/home/frodo/Generated/a.glb"), done.copy(nodeId = "", path = "/home/frodo/Generated/a.glb").raw)
    }
}
