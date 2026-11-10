package eu.llembas.android.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * What a session is doing (harness spec 3.4.0, `progress.md`): one of a fixed set of [phase]s,
 * since [since] (Unix milliseconds), with the phase's data. A field that is not known is left
 * out by the sender, so every one is optional here, and the text says less without it.
 */
@Serializable
data class Progress(
    val phase: String,
    val since: Long = 0,
    /** queued: turns before it. */
    val ahead: Int? = null,
    /** waiting_for_model, loading_model: the model's display name. */
    val model: String? = null,
    /** waiting_for_model: busy | slots | group. stopped: person | steps | budget. retrying, failed: the error. */
    val reason: String? = null,
    /** reading_prompt: prompt tokens processed, of [total]. */
    val done: Int? = null,
    val total: Int? = null,
    /** writing: tokens a second. */
    val rate: Double? = null,
    /** running_tool: the tool's name; waiting_for_person (approval): the tool asked about. */
    val tool: String? = null,
    /** running_tool: the call's short title. waiting_for_person: approval | question | plan. */
    val what: String? = null,
    /** waiting_for_subagents: subagents working or waiting. */
    val running: Int? = null,
    /** retrying: seconds until the retry, counted down from [since]. */
    @SerialName("in") val inSeconds: Int? = null,
    val attempt: Int? = null,
) {
    companion object {
        /** `loop.json` `progress.no_elapsed`: drawn as their text alone. */
        val NO_ELAPSED = setOf("stopped", "failed")
    }
}

/**
 * The shared texts (`loop.json` `progress.texts`, the i18n keys `progress.<phase>` …), as this
 * app holds them: [text] returns the template for a key with its arguments filled in, or null for
 * a key it does not have. The keys are the spec's with `.` as `_`: `queued_ahead`,
 * `reason_busy`, `what_approval_bare`, `stopped_reason_person`, `line`.
 */
fun interface ProgressWords {
    fun text(key: String, vararg args: String): String?
}

/**
 * The phase's text, its fields filled (`progress.md` §2): numbers as integers, a rate with one
 * decimal in [locale]'s format, `retrying`'s `in` counted down to [now]. Null for a phase this
 * app does not know -- a newer server's -- so the caller falls back to the `status` text.
 */
fun progressText(progress: Progress, words: ProgressWords, now: Long, locale: Locale = Locale.getDefault()): String? {
    val p = progress
    return when (p.phase) {
        "queued" -> if (p.ahead != null) words.text("queued_ahead", "${p.ahead}") else words.text("queued")

        "waiting_for_model" -> {
            val model = p.model ?: return null
            val why = p.reason?.let { words.text("reason_$it") ?: it } ?: return null
            words.text("waiting_for_model", model, why)
        }

        "loading_model" -> p.model?.let { words.text("loading_model", it) }

        "reading_prompt" -> {
            val done = p.done
            val total = p.total
            if (done != null && total != null && total > 0) {
                words.text("reading_prompt", "$done", "$total", "${done.toLong() * 100 / total}")
            } else {
                words.text("reading_prompt_bare")
            }
        }

        "thinking" -> words.text("thinking")

        "writing" -> p.rate?.let { words.text("writing", String.format(locale, "%.1f", it)) } ?: words.text("writing_bare")

        "running_tool" -> {
            val tool = p.tool ?: return null
            if (!p.what.isNullOrBlank()) words.text("running_tool", tool, p.what.take(80)) else words.text("running_tool_bare", tool)
        }

        "waiting_for_person" -> {
            val what = when (p.what) {
                "approval" -> if (!p.tool.isNullOrBlank()) words.text("what_approval", p.tool) else words.text("what_approval_bare")
                null -> null
                else -> words.text("what_${p.what}")
            } ?: return null
            words.text("waiting_for_person", what)
        }

        "waiting_for_subagents" -> words.text("waiting_for_subagents", "${p.running ?: 0}")

        "compacting" -> words.text("compacting")

        "retrying" -> {
            val left = ((p.inSeconds ?: 0) - ((now - p.since).coerceAtLeast(0) / 1000)).coerceAtLeast(0)
            words.text("retrying", "$left", p.reason.orEmpty())
        }

        "stopped" -> {
            val why = p.reason?.let { words.text("stopped_reason_$it") }
            if (why != null) words.text("stopped_reason", why) else words.text("stopped")
        }

        "failed" -> words.text("failed", p.reason.orEmpty())

        else -> null
    }
}

/**
 * The line a client draws (`progress.md` §3): `{{text}} · {{elapsed}}`, or the text alone for
 * `stopped` and `failed`. Null when [progressText] is.
 */
fun progressLine(progress: Progress, words: ProgressWords, now: Long, locale: Locale = Locale.getDefault()): String? {
    val text = progressText(progress, words, now, locale) ?: return null
    if (progress.phase in Progress.NO_ELAPSED) return text
    return words.text("line", text, elapsed(now - progress.since)) ?: text
}

/** Whole seconds as a ticking clock writes a duration: `5s`, `1m 05s`, `1h 02m`. Never negative. */
fun elapsed(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${"%02d".format(Locale.ROOT, seconds % 60)}s"
        else -> "${seconds / 3600}h ${"%02d".format(Locale.ROOT, (seconds % 3600) / 60)}m"
    }
}
