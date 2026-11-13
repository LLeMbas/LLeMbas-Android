package eu.llembas.android

import eu.llembas.android.api.Chat
import eu.llembas.android.api.LembasJson
import eu.llembas.android.api.PushPayload
import eu.llembas.android.api.Report
import eu.llembas.android.api.ReportList
import eu.llembas.android.api.ReportReply
import eu.llembas.android.api.ReportsFrame
import eu.llembas.android.api.RunResult
import eu.llembas.android.api.Step
import eu.llembas.android.api.shownMarkdown
import eu.llembas.android.push.ReportNotice
import eu.llembas.android.push.ScheduledNotice
import eu.llembas.android.ui.Segment
import eu.llembas.android.ui.reports.shareText
import eu.llembas.android.ui.splitFences
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server's reports contract (`contract/report*.json`, `push_report.json`,
 * `event_reports.json`, written by its own serialisers): every file read with the app's models,
 * and what the app makes of each.
 */
class ReportsContractTest {
    private fun text(name: String): String = requireNotNull(javaClass.classLoader!!.getResource("$name.json")) { "contract/$name.json is missing" }.readText()

    private fun <T> read(name: String, serializer: KSerializer<T>): T = LembasJson.decodeFromString(serializer, text(name))

    @Test
    fun theListAndTheCountOfTheUnread() {
        val list = read("reports", ReportList.serializer())
        val report = list.reports.single()
        assertEquals("rep1", report.id)
        assertEquals("Two cakes are unwrapped", report.title)
        assertEquals("Two of the twelve cakes have lost their leaves.", report.summary)
        assertTrue(report.high)
        assertTrue(report.unread)
        assertEquals("Waybread check", report.schedule?.title)
        assertEquals("2026-01-02T03:04:05+00:00", report.created)
        assertEquals(1, list.unreadCount)
        // The same for the events frame, which carries the newest one too.
        val frame = read("event_reports", ReportsFrame.serializer())
        assertEquals(1, frame.unread)
        assertEquals("rep1", frame.newest?.id)
    }

    @Test
    fun oneReportIsWrappedAndItsPageIsACard() {
        // `GET /reports/{id}` answers `{report: …}`: read as ApiClient.report reads it.
        val report = LembasJson.decodeFromJsonElement(Report.serializer(), LembasJson.parseToJsonElement(text("report")).jsonObject.getValue("report"))
        assertEquals("chat9", report.chatId)
        assertEquals("gpt-oss", report.model)
        // The body carries the page's source; the phone draws a card in its place, never the source.
        val drawn = splitFences(report.body!!)
        assertEquals(Segment.Page("Cakes"), drawn.last())
        assertFalse(drawn.any { it is Segment.Code })
        // Share takes the server's own text, where the page is its one line.
        assertEquals("Two cakes are unwrapped\n\n## What\n\nTwo of the twelve cakes have lost their leaves.\n\n[page: Cakes]", shareText(report))
        assertFalse(shareText(report).contains("10 wrapped"))
        assertEquals("chat9", read("report_reply", ReportReply.serializer()).chatId)
    }

    @Test
    fun theReportsPush() {
        val payload = read("push_report", PushPayload.serializer())
        val notice = ReportNotice.from(payload)!!
        assertEquals("rep1", notice.reportId)
        // The words are the notification's; the payload's top is the app's name and "New report".
        assertEquals("Two cakes are unwrapped", notice.title)
        assertEquals("Two of the twelve cakes have lost their leaves.", notice.summary)
        assertTrue(notice.high)
        assertEquals("LLeMbas", payload.title)
        // It is not a scheduled result, though it names the schedule and the run.
        assertNull(ScheduledNotice.from(payload))
        // And the run that filed it says which report.
        assertEquals("rep1", read("schedule_run", RunResult.serializer()).run.reportId)
    }

    @Test
    fun aReportsConversationSaysWhichReportAndWhatMayBeDoneToIt() {
        val chat = read("chat_report", Chat.serializer())
        assertEquals("report", chat.kind)
        assertEquals("rep1", chat.report?.id)
        assertEquals("Two cakes are unwrapped", chat.report?.title)
        // Rename and delete; not archive, pin or a folder.
        assertTrue(chat.allows("rename") && chat.allows("delete"))
        assertFalse(chat.allows("archive") || chat.allows("pin") || chat.allows("folder"))
        // An ordinary chat allows all of them, and so does a server that does not say.
        assertTrue(read("chat", Chat.serializer()).allows("archive"))
        assertNull(read("chat", Chat.serializer()).report)
        assertTrue(LembasJson.decodeFromString(Chat.serializer(), """{"id":"c1"}""").allows("archive"))
    }

    @Test
    fun aReportWhoseScheduleIsGoneKeepsItsName() {
        val orphan = LembasJson.decodeFromJsonElement(Report.serializer(), LembasJson.parseToJsonElement(text("report_orphan")).jsonObject.getValue("report"))
        assertNull(orphan.schedule)
        assertEquals("Waybread check", orphan.from)
        assertEquals("Waybread check", read("reports", ReportList.serializer()).reports.single().from)
    }

    @Test
    fun aStepWithAPageIsShownByItsText() {
        val step = read("step_text_page", Step.serializer())
        assertEquals("Cakes", step.pages.single().title)
        // The server's own text, where the page is its one line -- which the phone draws as a card.
        assertEquals("Two are unwrapped.\n\n[page: Cakes]\n", step.shownMarkdown)
        assertEquals(listOf(Segment.Prose("Two are unwrapped."), Segment.Page("Cakes")), splitFences(step.shownMarkdown))
        // A step without pages is its markdown.
        assertEquals("plain", Step(kind = "text", markdown = "plain", text = "other").shownMarkdown)
    }
}
