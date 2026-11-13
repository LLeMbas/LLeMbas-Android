package eu.llembas.android.api

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// --- Reports (`/api/v2/reports`, harness spec 3.8.0 `reports.md`) -----------------------------
//
// A schedule's run does not answer into a chat: it files a report, or it does not. The server
// stores the reports and lists them; a report's body is Markdown, as a reply is.
//
// The shapes are read leniently where an id may come as a string or as an object carrying one
// ([chat], a run's `report`), so that a server that says a little more still parses; the
// contract fixtures (`contract/report*.json`) pin what it says.

/** The schedule a report came from: its id and its name. */
@Serializable
data class ReportSchedule(val id: String = "", val title: String = "")

/**
 * One report: [title] a headline, [summary] a sentence or two for a list or a notification,
 * [importance] `normal` or `high` (look now), the [schedule] that filed it, [created] when, and
 * whether it is still [unread]. Read alone it also carries its [body] (Markdown a model wrote:
 * untrusted) and the [chat] a reply continues in, once there is one.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Report(
    val id: String,
    val title: String = "",
    val summary: String = "",
    val importance: String = "normal",
    val schedule: ReportSchedule? = null,
    /** The schedule's name as it was when the report was filed: what is left once the schedule itself is gone. */
    @SerialName("schedule_title") val scheduleTitle: String = "",
    @JsonNames("created_at") val created: String? = null,
    val unread: Boolean = false,
    val body: String? = null,
    /** The body as text: every page in it replaced by its one line, `[page: Title]`; the body itself where it has none. */
    val text: String? = null,
    /** The pages the body has: only these are pages -- an `html-view` fence the server does not list is a code block. */
    val pages: List<StepPage> = emptyList(),
    val model: String? = null,
    val chat: JsonElement? = null,
) {
    val high: Boolean get() = importance == "high"

    /** The name of the task it came from: the schedule's as it is now, else as it was. */
    val from: String get() = schedule?.title?.ifBlank { null } ?: scheduleTitle

    /** The chat a reply to this report continues in; null before the first reply. */
    val chatId: String? get() = idOf(chat)
}

@Serializable
data class ReportList(val reports: List<Report> = emptyList(), val unread: Int? = null) {
    /** How many reports are unread: the server's count, else as many as it listed unread. */
    val unreadCount: Int get() = unread ?: reports.count { it.unread }
}

/** `GET /events`, frame `reports`: the count of the unread, and the newest report. */
@Serializable
data class ReportsFrame(val unread: Int = 0, val newest: Report? = null, @SerialName("latest_created") val latestCreated: String? = null)

/** `POST /reports/{id}/reply`: the chat the conversation about the report is in. */
@Serializable
data class ReportReply(val chat: JsonElement? = null) {
    val chatId: String? get() = idOf(chat)
}

/** An id that came as a string, or as an object with an `id`; null for anything else. */
internal fun idOf(element: JsonElement?): String? = when (element) {
    is JsonPrimitive -> element.contentOrNull?.takeIf { element.isString && it.isNotBlank() }
    is JsonObject -> idOf(element["id"])
    else -> null
}

/** Reports as the list shows them: the unread first, then the newest first within each half. */
fun sortedReports(reports: List<Report>): List<Report> = reports.sortedWith(compareByDescending<Report> { it.unread }.thenByDescending { it.created.orEmpty() })

/**
 * When a schedule's owner wants to hear from it (`loop.json` `scheduled.notify`): `always` (a
 * report every run), `when` (only when the person's own words hold) or `never` (history only).
 * A server before 3.8.0 said `matters` for what is now `when`; anything else is the default.
 */
fun notifyChoice(notify: String): String = when (notify) {
    "always", "never" -> notify
    else -> "when"
}

/**
 * An id as the server makes them -- letters, digits, `_` and `-`, at most 64 -- or null. Anything
 * that came from outside the app (a push payload, an intent's extra) is passed through this
 * before it becomes part of a route or an address: a `/` in it must not open another page.
 */
fun safeId(id: String?): String? = id?.takeIf { SAFE_ID.matches(it) }

private val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
