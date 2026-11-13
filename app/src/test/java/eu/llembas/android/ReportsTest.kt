package eu.llembas.android

import eu.llembas.android.api.LembasJson
import eu.llembas.android.api.PushPayload
import eu.llembas.android.api.Report
import eu.llembas.android.api.ReportList
import eu.llembas.android.api.ReportReply
import eu.llembas.android.api.Schedule
import eu.llembas.android.api.ScheduleRun
import eu.llembas.android.api.notifyChoice
import eu.llembas.android.api.sortedReports
import eu.llembas.android.push.ReportNotice
import eu.llembas.android.push.ScheduledNotice
import eu.llembas.android.ui.Segment
import eu.llembas.android.ui.pageProjection
import eu.llembas.android.ui.reports.shareText
import eu.llembas.android.ui.splitFences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reports (harness spec 3.8.0, `reports.md`) and pages in an answer (`pages.md`), as far as they
 * need no phone: the shapes, the list's order, "notify when", the push, and a page's place in
 * the text. The shapes here are written out in the test; `ReportsContractTest` reads the server's
 * own fixtures once they are pinned.
 */
class ReportsTest {
    private val list = """
        {"reports": [
          {"id": "rep1", "title": "Two tenders opened", "summary": "Storage bids are due in May.", "importance": "high",
           "schedule": {"id": "s1", "title": "Morning news"}, "created": "2026-03-10T07:31:00+00:00", "unread": true},
          {"id": "rep2", "title": "Nothing new on the bridge", "summary": "", "importance": "normal",
           "schedule": {"id": "s2", "title": "Bridge watch"}, "created": "2026-03-11T07:31:00+00:00", "unread": false},
          {"id": "rep3", "title": "Flour is back in stock", "created": "2026-03-09T07:31:00+00:00", "unread": true}
        ]}
    """.trimIndent()

    @Test
    fun theListAndItsOrder() {
        val reports = LembasJson.decodeFromString(ReportList.serializer(), list).reports
        assertEquals("Morning news", reports[0].schedule?.title)
        assertTrue(reports[0].high)
        assertFalse(reports[2].high)
        assertNull(reports[2].schedule)
        // The unread first, the newest first within each half.
        assertEquals(listOf("rep1", "rep3", "rep2"), sortedReports(reports).map { it.id })
        // A server that says nothing has none.
        assertTrue(LembasJson.decodeFromString(ReportList.serializer(), "{}").reports.isEmpty())
    }

    @Test
    fun oneReportItsBodyAndTheChatAReplyContinuesIn() {
        val alone = LembasJson.decodeFromString(
            Report.serializer(),
            """{"id":"rep1","title":"Two tenders opened","body":"**Two** tenders.","chat":null,"unread":true,"created_at":"2026-03-10T07:31:00+00:00"}""",
        )
        assertEquals("**Two** tenders.", alone.body)
        assertNull(alone.chatId)
        // `created` under either name.
        assertEquals("2026-03-10T07:31:00+00:00", alone.created)
        // The chat as an id, or as an object carrying one: both are the chat.
        val replied = LembasJson.decodeFromString(Report.serializer(), """{"id":"rep1","chat":"chat9"}""")
        assertEquals("chat9", replied.chatId)
        assertEquals("chat9", LembasJson.decodeFromString(Report.serializer(), """{"id":"rep1","chat":{"id":"chat9"}}""").chatId)
        assertEquals("chat9", LembasJson.decodeFromString(ReportReply.serializer(), """{"chat":"chat9"}""").chatId)
        assertNull(LembasJson.decodeFromString(ReportReply.serializer(), """{"chat":""}""").chatId)
    }

    @Test
    fun aRunLeavesANoteAndMaybeAReport() {
        val filed = LembasJson.decodeFromString(ScheduleRun.serializer(), """{"id":"r1","status":"ok","note":"Two tenders; report filed.","report":"rep1"}""")
        assertEquals("Two tenders; report filed.", filed.note)
        assertEquals("rep1", filed.reportId)
        val quiet = LembasJson.decodeFromString(ScheduleRun.serializer(), """{"id":"r2","status":"ok","note":"Nothing changed since Monday.","report":null}""")
        assertNull(quiet.reportId)
        // A server before 3.8.0 says neither.
        val old = LembasJson.decodeFromString(ScheduleRun.serializer(), """{"id":"r3","status":"silent"}""")
        assertEquals("", old.note)
        assertNull(old.reportId)
    }

    @Test
    fun notifyWhenIsOneOfThree() {
        assertEquals("always", notifyChoice("always"))
        assertEquals("never", notifyChoice("never"))
        assertEquals("when", notifyChoice("when"))
        // What a server before 3.8.0 called "when it matters", and a schedule that says nothing: the default.
        assertEquals("when", notifyChoice("matters"))
        assertEquals("when", notifyChoice(""))
        val schedule = LembasJson.decodeFromString(Schedule.serializer(), """{"id":"s1","notify":"when","notify_when":"the price drops below 100"}""")
        assertEquals("the price drops below 100", schedule.notifyWhen)
        assertEquals("", LembasJson.decodeFromString(Schedule.serializer(), """{"id":"s1","notify":"matters"}""").notifyWhen)
    }

    @Test
    fun aReportsPushOpensTheReport() {
        val payload = LembasJson.decodeFromString(
            PushPayload.serializer(),
            """{"kind":"report","title":"Two tenders opened","body":"Storage bids are due in May.","url":"/reports/rep1"}""",
        )
        val notice = ReportNotice.from(payload)!!
        assertEquals("rep1", notice.reportId)
        assertEquals("Two tenders opened", notice.title)
        assertEquals("Storage bids are due in May.", notice.summary)
        assertFalse(notice.high)
        // The id named outright wins over the address, and importance may make it urgent.
        val named = payload.copy(reportId = "rep7", importance = "high")
        assertEquals("rep7", ReportNotice.from(named)!!.reportId)
        assertTrue(ReportNotice.from(named)!!.high)
        // One notification per report.
        assertEquals(notice.id, ReportNotice.from(payload)!!.id)
        // Not a report: nothing; and a report is not a scheduled result.
        assertNull(ReportNotice.from(payload.copy(kind = "reply")))
        assertNull(ReportNotice.from(payload.copy(url = "/chat/c1")))
        assertNull(ScheduledNotice.from(payload))
    }

    @Test
    fun theReportsWordsComeFromThePayloadsNotification() {
        // The payload's top is for a client that knows nothing of reports; the words are inside.
        val payload = LembasJson.decodeFromString(
            PushPayload.serializer(),
            """{"kind":"report","report_id":"rep1","title":"LLeMbas","body":"New report","url":"/reports/rep1",
               "notification":{"title":"Two tenders opened","body":"Storage bids are due in May.","public":{"title":"New report","body":""},"importance":"high"}}""",
        )
        val notice = ReportNotice.from(payload)!!
        assertEquals("Two tenders opened", notice.title)
        assertEquals("Storage bids are due in May.", notice.summary)
        assertTrue(notice.high)
    }

    @Test
    fun anOlderServersScheduledPushStillWorks() {
        // 2.0.0-beta.11 and before: a run's result is its own push, which opens the run.
        val result = LembasJson.decodeFromString(
            PushPayload.serializer(),
            """{"kind":"schedule","title":"Morning news","body":"Little happened.","url":"/scheduled/s1?run=r1","schedule_id":"s1","run_id":"r1"}""",
        )
        assertNull(ReportNotice.from(result))
        val notice = ScheduledNotice.from(result)!!
        assertEquals("s1", notice.scheduleId)
        assertEquals("r1", notice.runId)
        assertFalse(notice.failed)
        // A failed run keeps its failure notification under 3.8.0 too.
        assertTrue(ScheduledNotice.from(result.copy(kind = "schedule_failed"))!!.failed)
    }

    @Test
    fun aPageInAnAnswerIsNeverItsSource() {
        val reply = "Sales by month:\n\n```html-view Monthly   sales\n<!doctype html>\n<html><script>alert(1)</script></html>\n```\n\nThat is all."
        val split = splitFences(reply)
        assertEquals(listOf(Segment.Prose("Sales by month:"), Segment.Page("Monthly sales"), Segment.Prose("That is all.")), split)
        // The word is exact: these are ordinary code blocks.
        assertEquals(listOf(Segment.Code("html", "<p>")), splitFences("```html\n<p>\n```"))
        assertEquals(listOf(Segment.Code("html-views", "<p>")), splitFences("```html-views\n<p>\n```"))
        // Tildes, no title, and one still being written.
        assertEquals(listOf(Segment.Page("")), splitFences("~~~html-view\n<html></html>\n~~~"))
        assertEquals(listOf(Segment.Page("Chart", closed = false)), splitFences("```html-view Chart\n<html>"))
        // Backticks inside a page do not end it unless a line is the closing fence.
        assertEquals(listOf(Segment.Page("T")), splitFences("````html-view T\nconst a = `x`\n```\nstill the page\n````"))
        // The server's own line in a page's place.
        assertEquals(
            listOf(Segment.Prose("Before."), Segment.Page("Monthly sales"), Segment.Prose("After.")),
            splitFences("Before.\n\n[page: Monthly sales]\n\nAfter."),
        )
        assertEquals(listOf(Segment.Prose("See [page: x] inline.")), splitFences("See [page: x] inline."))
    }

    @Test
    fun whatIsCopiedOrSharedSaysThereIsAPage() {
        val reply = "Sales:\n\n```html-view Monthly sales\n<html>secret markup</html>\n```\n\n```sh\nmake\n```"
        val text = pageProjection(reply, untitled = "Page")
        assertEquals("Sales:\n\n[page: Monthly sales]\n\n```sh\nmake\n```", text)
        assertFalse(text.contains("secret markup"))
        assertEquals("[page: Page]", pageProjection("```html-view\n<html></html>\n```", untitled = "Page"))
        val report = Report(id = "rep1", title = "Two tenders opened", body = "Bids are due.\n\n```html-view Chart\n<svg></svg>\n```")
        assertEquals("Two tenders opened\n\nBids are due.\n\n[page: Chart]", shareText(report))
    }
}
