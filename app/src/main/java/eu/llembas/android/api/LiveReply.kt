package eu.llembas.android.api

import kotlinx.serialization.json.JsonObject

/**
 * A reply being written, as the stream has described it so far.
 *
 * Every frame carries a whole block, never a delta (the server's rule, `api/v2/stream.py`),
 * so applying one is replacing a field. That is what makes a dropped connection cheap: reopen
 * the stream, and its first pass rebuilds this from nothing.
 */
data class LiveReply(
    val messageId: String,
    val steps: List<Step> = emptyList(),
    val tail: Tail = Tail(),
    val plan: Plan? = null,
    val status: String = "",
    /** The structured progress beside [status] (spec 3.4.0); null from an older server, or idle. */
    val progress: Progress? = null,
    /** How the turn ended when it did not finish: the last `stopped` or `failed` progress, with its reason. */
    val end: Progress? = null,
    val cards: List<Card> = emptyList(),
    val jobs: List<Job> = emptyList(),
    /** The subagent tree (spec 3.4.0), from the `tree` frame; null until there is one. */
    val tree: SubagentTree? = null,
    val metrics: Metrics? = null,
    val steered: Set<String> = emptySet(),
    /**
     * Set by `done`: the finished row, which replaces this in the transcript. `done` is the end
     * of the reply -- the stream may stay open after it for one `title` frame, which nobody
     * waits for.
     */
    val finished: Message? = null,
    val title: String? = null,
    /** From `done`: a model is still naming the chat, so [title] is the one made from the prompt. */
    val titlePending: Boolean = false,
    /** A queued prompt the server started when this reply ended: the next stream to follow. */
    val next: String? = null,
    val closed: Boolean = false,
) {
    /** The steps as the transcript draws them: the closed ones, then the open tail. */
    val shown: List<Step>
        get() = buildList {
            addAll(steps)
            val index = (steps.maxOfOrNull { it.index } ?: -1) + 1
            if (tail.thinking.isNotEmpty()) add(Step(index = index, kind = "thinking", open = true, text = tail.thinking, label = tail.label))
            if (tail.text.isNotEmpty()) add(Step(index = index, kind = "text", open = true, markdown = tail.text))
        }

    /** Apply one frame. Unknown events are ignored: a newer server may send more. */
    fun apply(event: String, data: String): LiveReply = when (event) {
        "steps" -> decode(StepsFrame.serializer(), data)?.let { copy(steps = it.steps, tail = it.tail) } ?: this

        "tail" -> decode(Tail.serializer(), data)?.let { copy(tail = it) } ?: this

        "steered" -> decode(SteeredFrame.serializer(), data)?.let { copy(steered = steered + it.ids) } ?: this

        "plan" -> copy(plan = decodePlan(data))

        "status" -> decode(StatusFrame.serializer(), data)?.let { frame ->
            copy(status = frame.text, progress = frame.progress, end = frame.progress?.takeIf { it.phase in END_PHASES })
        } ?: this

        "ask" -> decode(AskFrame.serializer(), data)?.let { copy(cards = it.cards) } ?: this

        "jobs" -> decode(JobsFrame.serializer(), data)?.let { copy(jobs = it.jobs) } ?: this

        "tree" -> decode(TreeFrame.serializer(), data)?.let { copy(tree = it.tree) } ?: this

        "metrics" -> decode(Metrics.serializer(), data)?.let { copy(metrics = it) } ?: this

        "done" -> decode(DoneFrame.serializer(), data)?.let { done ->
            // The finished row says that it was stopped or failed; the stream said why (by you,
            // the step limit, the budget). The reason goes with the row, where its line stays
            // (display.md §4).
            val row = done.message
            val ended = row.stopped || !row.error.isNullOrBlank()
            copy(
                finished = if (ended && row.progress == null && end != null) row.copy(progress = end) else row,
                title = done.chat.title.ifEmpty { null },
                titlePending = done.chat.titlePending,
                next = done.next,
                cards = emptyList(),
                status = "",
                progress = null,
            )
        } ?: this

        // After `done`, when a model has named the chat: the name, and nothing else changes.
        "title" -> decode(TitleFrame.serializer(), data)?.let { named -> copy(title = named.chat.title.ifEmpty { title }, titlePending = false) } ?: this

        "close" -> copy(closed = true)

        else -> this
    }

    private fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, data: String): T? =
        runCatching { LembasJson.decodeFromString(serializer, data) }.getOrNull()

    private fun decodePlan(data: String): Plan? = runCatching {
        val element = LembasJson.parseToJsonElement(data)
        if (element is JsonObject && element.isNotEmpty()) LembasJson.decodeFromJsonElement(Plan.serializer(), element) else null
    }.getOrNull()
}
