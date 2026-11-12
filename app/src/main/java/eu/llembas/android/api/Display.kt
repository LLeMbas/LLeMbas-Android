package eu.llembas.android.api

import java.text.NumberFormat
import java.util.Locale

/*
 * One display in every client (harness spec 3.7.0, `display.md`): what this app says about a tool
 * call, the end of a reply and a number -- the same things, from the same data, as the web UI,
 * Desktop and the CLI.
 */

/** The states that end a turn without finishing it; their line stays on the reply (`display.end`). */
val END_PHASES = setOf("stopped", "failed")

/**
 * A call's short title as a line may show it (`display.md` §1, step 4): trimmed, its first line
 * only, and cut to [max] code points -- one fewer and `…` -- so a cut title says it was cut.
 * Null when nothing is left.
 */
fun shortTitle(text: String?, max: Int = TITLE_MAX): String? {
    val line = text?.trim()?.lineSequence()?.firstOrNull()?.trim().orEmpty()
    if (line.isEmpty()) return null
    if (line.codePointCount(0, line.length) <= max) return line
    return line.substring(0, line.offsetByCodePoints(0, max - 1)) + "…"
}

/** `loop.json` `progress.what_max`. */
const val TITLE_MAX = 80

/**
 * The state a drawn call is in (`display.md` §2): `running`, `done`, `failed` or `denied`, from
 * what `/api/v2` says of it.
 */
fun ToolCall.state(): String = when {
    status == "denied" -> "denied"
    status == "error" || !error.isNullOrEmpty() -> "failed"
    status == "running" || status == "pending" -> "running"
    else -> "done"
}

/**
 * How long a finished call took (`display.md` §2): tenths under a minute (`0.4s`), then
 * `1m 05s`, then `2h 03m`. None for a call that is running or was denied, or whose time was not
 * kept. Not a locale number: written with `.` everywhere.
 */
fun callDuration(state: String, ms: Long?): String? {
    if (ms == null || state == "running" || state == "denied") return null
    val seconds = ms.coerceAtLeast(0) / 1000.0
    if (seconds < 60) return String.format(Locale.ROOT, "%.1fs", seconds)
    val minutes = (seconds / 60).toLong()
    return if (minutes < 60) {
        String.format(Locale.ROOT, "%dm %02ds", minutes, (seconds % 60).toLong())
    } else {
        String.format(Locale.ROOT, "%dh %02dm", minutes / 60, minutes % 60)
    }
}

/**
 * How a reply ended, when it did not finish (`display.md` §4): the `stopped` or `failed` progress
 * whose line stays on it. From the end the stream said (kept on the message as its `progress`,
 * with the reason: by you, step limit, budget), else from what the stored row knows -- that it
 * was stopped, or its error, of which the first line is the reason. Null for a reply that
 * finished, a user's message, or one still queued.
 */
fun Message.end(): Progress? = when {
    role != "assistant" -> null
    progress?.phase in END_PHASES -> progress
    !error.isNullOrBlank() -> Progress(phase = "failed", reason = error.lineSequence().firstOrNull { it.isNotBlank() }?.trim())
    stopped -> Progress(phase = "stopped")
    else -> null
}

/**
 * A count a person reads, in [locale]'s own format -- its grouping and its decimal mark
 * (`display.md` §6): `12,480`, `12 480`, `12.480`. [Locale.ROOT] is the spec's plain format, no
 * grouping and `.`, which is what its conformance texts are written in.
 */
fun localeNumber(n: Long, locale: Locale): String = if (locale == Locale.ROOT) n.toString() else NumberFormat.getIntegerInstance(locale).format(n)

/** A measure with [decimals] places, in [locale]'s format; plain for [Locale.ROOT]. */
fun localeNumber(n: Double, decimals: Int, locale: Locale): String {
    if (locale == Locale.ROOT) return String.format(Locale.ROOT, "%.${decimals}f", n)
    val format = NumberFormat.getNumberInstance(locale)
    format.minimumFractionDigits = decimals
    format.maximumFractionDigits = decimals
    format.roundingMode = java.math.RoundingMode.HALF_UP
    return format.format(n)
}
