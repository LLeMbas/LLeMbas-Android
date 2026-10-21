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
    val cards: List<Card> = emptyList(),
    val jobs: List<Job> = emptyList(),
    val metrics: Metrics? = null,
    val steered: Set<String> = emptySet(),
    /** Set by `done`: the finished row, which replaces this in the transcript. */
    val finished: Message? = null,
    val title: String? = null,
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

        "status" -> decode(StatusFrame.serializer(), data)?.let { copy(status = it.text) } ?: this

        "ask" -> decode(AskFrame.serializer(), data)?.let { copy(cards = it.cards) } ?: this

        "jobs" -> decode(JobsFrame.serializer(), data)?.let { copy(jobs = it.jobs) } ?: this

        "metrics" -> decode(Metrics.serializer(), data)?.let { copy(metrics = it) } ?: this

        "done" -> decode(DoneFrame.serializer(), data)?.let {
            copy(finished = it.message, title = it.chat.title.ifEmpty { null }, next = it.next, cards = emptyList(), status = "")
        } ?: this

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
