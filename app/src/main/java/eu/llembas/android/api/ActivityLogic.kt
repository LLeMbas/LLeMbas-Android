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
     * What the strip above the composer says, as counts in order: running, queued, waiting,
     * then finished. Empty when nothing is going -- then there is no strip, whatever finished.
     */
    fun stripCounts(summary: ActivitySummary): List<Pair<String, Int>> {
        if (summary.working + summary.queued + summary.waiting == 0) return emptyList()
        return listOf(
            "working" to summary.working,
            "queued" to summary.queued,
            "waiting" to summary.waiting,
            "finished" to summary.finished,
        ).filter { it.second > 0 }
    }

    /**
     * How many tokens are left before the chat compacts itself: the window's [ContextUse.threshold]
     * per cent, less what is used. Null when it never will -- the threshold is 0 (off) or 100,
     * or the window is unknown -- since a count against an unknown window is a guess.
     */
    fun untilAutoCompact(context: ContextUse): Int? {
        if (context.limit <= 0 || context.threshold !in 1..99) return null
        return (context.limit.toLong() * context.threshold / 100 - context.used).toInt().coerceAtLeast(0)
    }

    /**
     * What a task is called in its row: a team's member by its name, a subagent or a command
     * by what it was asked to do (its role, else its brief), its id only when it has neither.
     */
    fun title(agent: ActivityAgent): String = if (agent.kind == "member") {
        agent.name
    } else {
        agent.role.ifBlank { agent.brief }.ifBlank { agent.name }
    }

    /** Every task under [agent], however deep. */
    fun descendants(agent: ActivityAgent): Int = agent.children.sumOf { 1 + descendants(it) }

    /** The top level split as the panel shows it: those still going, then the ended ones. */
    fun split(agents: List<ActivityAgent>): Pair<List<ActivityAgent>, List<ActivityAgent>> = agents.partition { it.isActive }

    /** Every agent of a forest with its subagents under it, in tree order, for drawing as rows. */
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
