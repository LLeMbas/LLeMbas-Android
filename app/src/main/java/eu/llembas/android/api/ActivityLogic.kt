package eu.llembas.android.api

import kotlinx.serialization.json.JsonObject
import java.util.Locale

/**
 * The activity panel's arithmetic, apart from the screen so the tests can hold it: the numbers
 * are written as the server writes them (`compact_tokens`, `jobs.duration`), and the strip's
 * line counts what the web's strip counts.
 */
object ActivityLogic {
    /** A token count at a glance: 950, 14.2k, 180k, 1.54M (the server's `compact_tokens`). */
    fun compactTokens(value: Int): String {
        val n = value.coerceAtLeast(0)
        return when {
            n < 1000 -> n.toString()
            n < 100_000 -> trim(String.format(Locale.ROOT, "%.1f", n / 1000.0)) + "k"
            n < 1_000_000 -> "${Math.round(n / 1000.0)}k"
            else -> trim(String.format(Locale.ROOT, "%.2f", n / 1_000_000.0)) + "M"
        }
    }

    private fun trim(number: String): String = if ('.' in number) number.trimEnd('0').trimEnd('.') else number

    /** A duration as both the server and the CLI write it: `5s`, `1m 05s`, `2h 03m`. */
    fun duration(seconds: Long): String {
        val total = seconds.coerceAtLeast(0)
        if (total < 60) return "${total}s"
        val minutes = total / 60
        val rest = total % 60
        if (minutes < 60) return String.format(Locale.ROOT, "%dm %02ds", minutes, rest)
        return String.format(Locale.ROOT, "%dh %02dm", minutes / 60, minutes % 60)
    }

    /**
     * How long an agent has run now: the server's count when it was read, plus the time since
     * for one that is still going. A queued one has not started, and an ended one has stopped
     * counting.
     */
    fun elapsed(agent: ActivityAgent, readAtMillis: Long, nowMillis: Long): Long {
        val going = agent.state == "working" || agent.state == "waiting"
        val since = if (going) ((nowMillis - readAtMillis) / 1000).coerceAtLeast(0) else 0
        return agent.seconds + since
    }

    /**
     * What the strip above the composer says, as counts in order: agents working, queued,
     * waiting. Empty when nothing is going -- then there is no strip.
     */
    fun stripCounts(summary: ActivitySummary): List<Pair<String, Int>> =
        listOf("working" to summary.working, "queued" to summary.queued, "waiting" to summary.waiting).filter { it.second > 0 }

    /** The top level split as the panel shows it: those still going, then the ended ones. */
    fun split(agents: List<ActivityAgent>): Pair<List<ActivityAgent>, List<ActivityAgent>> = agents.partition { it.isActive }

    /** Every agent of a forest with its helpers under it, in tree order, for drawing as rows. */
    fun flatten(agents: List<ActivityAgent>): List<ActivityAgent> = agents.flatMap { listOf(it) + flatten(it.children) }

    /**
     * The context's bar: one segment per category with tokens, `free` left out (it is the
     * empty end of the bar). Nothing at all when the window is unknown.
     */
    fun barParts(context: ContextUse): List<ContextPart> {
        if (context.limit <= 0) return emptyList()
        return context.categories.filter { it.key != "free" && it.tokens > 0 }
    }

    /** The plan the panel shows, read leniently: a plan that does not parse is no plan. */
    fun plan(json: JsonObject?): Plan? = json?.let { runCatching { LembasJson.decodeFromJsonElement(Plan.serializer(), it) }.getOrNull() }

    /** A percentage as the server writes it: one decimal, none when it is whole. */
    fun percent(value: Double): String = trim(String.format(Locale.ROOT, "%.1f", value))
}
