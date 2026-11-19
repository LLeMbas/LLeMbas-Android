package eu.llembas.android.ui.chat

import eu.llembas.android.api.MediaJob
import eu.llembas.android.api.MediaJobRef
import eu.llembas.android.api.Message
import eu.llembas.android.api.Step
import eu.llembas.android.api.ToolCall

// A chat's long generations as the chat keeps them. There is no stream frame for a job -- a chat
// has no stream while no reply is being written -- so the jobs under way are read again every few
// seconds while the chat is in front, and the chat is read again when one of them has woken it.

/** A job as it was last read, and when ([at], the phone's uptime clock; 0: known only from the call). */
data class JobSeen(val job: MediaJob, val at: Long = 0) {
    /** How long it has taken, in seconds, at [now]: counted on from the read while it is under way. */
    fun elapsed(now: Long): Long {
        val said = job.seconds.toLong().coerceAtLeast(0)
        return if (job.underWay && at > 0 && now > at) said + (now - at) / 1000 else said
    }
}

/** How often jobs under way are read again. */
const val JOB_POLL_MS = 3000L

/** How many more reads a job that has ended is given to say that the chat was told. */
const val TELL_ROUNDS = 20

private fun calls(steps: List<Step>): Sequence<ToolCall> {
    fun all(call: ToolCall): Sequence<ToolCall> = sequenceOf(call) + call.children.orEmpty().asSequence().flatMap { all(it) }
    return steps.asSequence().flatMap { it.calls.asSequence() }.flatMap { all(it) }
}

/** Every job the calls of these messages (and of the steps being written, [live]) started, each once, in the order the calls come. */
fun jobRefsOf(messages: List<Message>, live: List<Step> = emptyList()): List<MediaJobRef> {
    val all = messages.asSequence().flatMap { calls(it.steps) } + calls(live)
    return all.mapNotNull { it.mediaJob?.takeIf { ref -> ref.id.isNotBlank() } }.distinctBy { it.id }.toList()
}

/** The jobs this chat's calls started (the reply being written included). */
val ChatState.jobRefs: List<MediaJobRef> get() = jobRefsOf(messages, live?.shown.orEmpty())

/**
 * A job that has ended and has not said yet that the chat was told: the reply about it is still
 * to come, so it is read a few more times. A cancelled job wakes nobody.
 */
fun awaitsTelling(job: MediaJob): Boolean = !job.underWay && !job.told && job.state != MediaJob.CANCELLED

/**
 * The jobs to read again: the ones a call names that were never read (the call's own word is
 * from the moment it returned), and the ones under way. A job that has ended is left alone.
 */
fun ChatState.jobsToRead(): List<String> {
    val named = jobRefs.map { it.id }.filter { mediaJobs[it]?.let { seen -> seen.at == 0L || seen.job.underWay } ?: true }
    val known = mediaJobs.values.filter { it.job.underWay }.map { it.job.id }
    return (named + known).distinct()
}

/** [jobs], read at [at], among what the chat knows of its jobs. */
fun ChatState.withJobs(jobs: List<MediaJob>, at: Long): ChatState =
    if (jobs.isEmpty()) this else copy(mediaJobs = mediaJobs + jobs.filter { it.id.isNotBlank() }.associate { it.id to JobSeen(it, at) })

/**
 * Whether a read says the chat was woken since the last one: the job was known and had not told
 * the chat, and now has. Then there is a reply this app did not start, and the transcript is
 * read again. A job first heard of already told is part of the transcript that was just read.
 */
fun woke(before: MediaJob?, after: MediaJob): Boolean = before != null && !before.told && after.told

/** What a call's job is drawn from: what was read of it, else the call's own word. */
fun ChatMedia.job(ref: MediaJobRef): JobSeen = jobs[ref.id] ?: JobSeen(MediaJob.of(ref))
