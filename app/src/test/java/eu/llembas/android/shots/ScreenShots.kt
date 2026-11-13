@file:OptIn(ExperimentalRoborazziApi::class)

package eu.llembas.android.shots

import android.view.LayoutInflater
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.inspectionMode
import com.github.takahirom.roborazzi.size
import eu.llembas.android.R
import eu.llembas.android.actions.NavApp
import eu.llembas.android.assist.AssistActions
import eu.llembas.android.assist.AssistSheetContent
import eu.llembas.android.assist.AssistSheetFrame
import eu.llembas.android.assist.AssistUiState
import eu.llembas.android.share.ShareActions
import eu.llembas.android.share.ShareSheetContent
import eu.llembas.android.share.ShareState
import eu.llembas.android.ui.chat.ActivityActions
import eu.llembas.android.ui.chat.ActivityContent
import eu.llembas.android.ui.chat.CardSheet
import eu.llembas.android.ui.chat.ChatActions
import eu.llembas.android.ui.chat.ChatContent
import eu.llembas.android.ui.chat.CodeBlock
import eu.llembas.android.ui.chat.ModelPickerContent
import eu.llembas.android.ui.chat.PhoneCardContent
import eu.llembas.android.ui.chat.PhoneCardState
import eu.llembas.android.ui.chat.TuneContent
import eu.llembas.android.ui.chat.modelId
import eu.llembas.android.ui.home.ArchivedActions
import eu.llembas.android.ui.home.ArchivedContent
import eu.llembas.android.ui.home.ChatsState
import eu.llembas.android.ui.home.DrawerActions
import eu.llembas.android.ui.home.DrawerContent
import eu.llembas.android.ui.memory.MemoryActions
import eu.llembas.android.ui.memory.MemoryContent
import eu.llembas.android.ui.memory.MemoryState
import eu.llembas.android.ui.reports.ReportContent
import eu.llembas.android.ui.reports.ReportsActions
import eu.llembas.android.ui.reports.ReportsContent
import eu.llembas.android.ui.reports.ReportsState
import eu.llembas.android.ui.scheduled.RunContent
import eu.llembas.android.ui.scheduled.ScheduleContent
import eu.llembas.android.ui.scheduled.ScheduleEditContent
import eu.llembas.android.ui.scheduled.ScheduledContent
import eu.llembas.android.ui.scheduled.SchedulingModelsContent
import eu.llembas.android.ui.settings.LanguageChoices
import eu.llembas.android.ui.settings.NotificationSummaryGroup
import eu.llembas.android.ui.settings.SettingsActions
import eu.llembas.android.ui.settings.SettingsContent
import eu.llembas.android.ui.settings.SettingsState
import eu.llembas.android.ui.settings.WakeChoice
import eu.llembas.android.ui.settings.WakeChoices
import eu.llembas.android.ui.settings.WakeWordGroup
import eu.llembas.android.ui.theme.LocalPalette
import eu.llembas.android.ui.viewer.FileBody
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneOffset

private object NoChatActions : ChatActions

private object NoDrawerActions : DrawerActions

private object NoArchivedActions : ArchivedActions

private object NoMemoryActions : MemoryActions

private object NoActivityActions : ActivityActions

private object NoSettingsActions : SettingsActions

private object NoReportsActions : ReportsActions

private object NoAssistActions : AssistActions

private object NoShareActions : ShareActions

/**
 * The app's screens with made-up data ([Fakes]), as a phone shows them: each in Shire and
 * Moria, at a large phone's size and, where things crowd, a small one's and with large type.
 * See [shoot]. These are looked at after every design change; they assert nothing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "xhdpi")
class ScreenShots {
    @Test
    fun newChat() {
        shoot("01-new-chat") { ChatContent(Fakes.newChat, NoChatActions, agent = false, onKind = {}) }
        shoot("01-new-chat", Phone.Small) { ChatContent(Fakes.newChat, NoChatActions, agent = false, onKind = {}) }
        shoot("01-new-chat", Phone.Small, fontScale = 1.5f) { ChatContent(Fakes.newChat, NoChatActions, agent = false, onKind = {}) }
    }

    @Test
    fun newAgentChat() {
        shoot("02-new-agent-chat") { ChatContent(Fakes.newAgentChat, NoChatActions, agent = true, onKind = {}) }
        // No computer linked: Agent stays, and says how to link one.
        shoot("02-new-agent-chat-unlinked") { ChatContent(Fakes.newAgentChatUnlinked, NoChatActions, agent = true, onKind = {}) }
        shoot("02-new-agent-chat-unlinked", Phone.Small) { ChatContent(Fakes.newAgentChatUnlinked, NoChatActions, agent = true, onKind = {}) }
    }

    @Test
    fun transcript() {
        shoot("03-transcript") { ChatContent(Fakes.transcript, NoChatActions, agent = false) }
        shoot("03-transcript", Phone.Small) { ChatContent(Fakes.transcript, NoChatActions, agent = false) }
        shoot("03-transcript", Phone.Small, fontScale = 1.5f) { ChatContent(Fakes.transcript, NoChatActions, agent = false) }
    }

    @Test
    fun writing() {
        // The 3.4.0 progress line under the reply: `{{text}} · {{elapsed}}`, the clock still.
        shoot("04-writing") { ChatContent(Fakes.writing, NoChatActions, agent = false) }
        shoot("04-waiting-for-model", Phone.Small, fontScale = 1.5f) { ChatContent(Fakes.waitingForModel, NoChatActions, agent = false) }
    }

    @Test
    fun drawer() {
        val drawn: @Composable () -> Unit = {
            ModalNavigationDrawer(
                drawerState = rememberDrawerState(DrawerValue.Open),
                drawerContent = {
                    DrawerContent(
                        Fakes.chats.copy(unreadReports = 2),
                        current = "c1",
                        account = "Home · frodo@example.org",
                        actions = NoDrawerActions,
                        today = Fakes.today,
                        zone = ZoneOffset.UTC,
                    )
                },
            ) { ChatContent(Fakes.transcript, NoChatActions, agent = false) }
        }
        shoot("05-drawer", content = drawn)
        shoot("05-drawer", Phone.Small, content = drawn)
        // Searching: the titles that match, then what the instance found inside the messages.
        shoot("05-drawer-search") {
            ModalNavigationDrawer(
                drawerState = rememberDrawerState(DrawerValue.Open),
                drawerContent = {
                    DrawerContent(
                        Fakes.chatsFound,
                        current = null,
                        account = "Home · frodo@example.org",
                        actions = NoDrawerActions,
                        today = Fakes.today,
                        zone = ZoneOffset.UTC,
                        initialQuery = "bread",
                    )
                },
            ) { ChatContent(Fakes.transcript, NoChatActions, agent = false) }
        }
    }

    @Test
    fun modelPicker() = shoot("06-model-picker") {
        SheetOver { ModelPickerContent(Fakes.models, Fakes.transcript.modelId, onPick = {}) }
    }

    @Test
    fun tune() {
        shoot("07-tune") { SheetOver { TuneContent(Fakes.tuned, NoChatActions, agent = false) } }
        shoot("07-tune-one-at-a-time") { SheetOver { TuneContent(Fakes.tunedOneAtATime, NoChatActions, agent = false) } }
        // The Subagents range whole, at a cap of 32 and of 6, and the agent alone (Min 1 · Max 1)
        // at the narrow phone and large type.
        shoot("07-tune", Phone.Scroll) { SheetOver { TuneContent(Fakes.tuned, NoChatActions, agent = false) } }
        shoot("07-tune-one-at-a-time", Phone.Scroll, fontScale = 1.5f) { SheetOver { TuneContent(Fakes.tunedOneAtATime, NoChatActions, agent = false) } }
        // A new agent chat whose computer's CLI cannot run subagents yet: the setting says so.
        val agentTune = Fakes.newAgentChat.copy(agentSubagents = eu.llembas.android.api.AgentSubagents(offered = true, deviceRuns = false))
        shoot("07-tune-new-agent", Phone.Scroll) { SheetOver { TuneContent(agentTune, NoChatActions, agent = true) } }
        shoot("07-tune-alone", Phone.SmallScroll, fontScale = 1.5f) { SheetOver { TuneContent(Fakes.tunedAlone, NoChatActions, agent = false) } }
    }

    @Test
    fun settings() {
        shoot("08-settings") { SettingsContent(Fakes.settings, NoSettingsActions) }
        shoot("08-settings", Phone.Small) { SettingsContent(Fakes.settings, NoSettingsActions) }
        // The whole page: Language, the assistant's switches and Voice input.
        shoot("08-settings", Phone.Scroll) { SettingsContent(Fakes.settings, NoSettingsActions) }
    }

    /**
     * The wake word's group and notification summaries on their own, since the whole page is
     * longer than a shot: listening; failed with its reason and a test that heard nothing; a test
     * that heard the phrase twice, in a standard build (which explains where summaries went).
     */
    @Test
    fun wakeWord() {
        val groups: @Composable (SettingsState) -> Unit = { state ->
            Column(Modifier.padding(horizontal = 16.dp)) {
                WakeWordGroup(state, NoSettingsActions, onChoose = {})
                NotificationSummaryGroup(state, NoSettingsActions)
            }
        }
        shoot("08-wake-listening", Phone.Scroll) { groups(Fakes.settings) }
        shoot("08-wake-failed", Phone.Scroll) { groups(Fakes.settingsWakeFailed) }
        shoot("08-wake-failed", Phone.SmallScroll, fontScale = 1.5f) { groups(Fakes.settingsWakeFailed) }
        // The assistant not connected right now, and the last wake that found it so.
        shoot("08-wake-unbound", Phone.Scroll) { groups(Fakes.settingsWakeUnbound) }
        shoot("08-wake-unbound", Phone.SmallScroll, fontScale = 1.5f) { groups(Fakes.settingsWakeUnbound) }
        shoot("08-wake-tested", Phone.Scroll) { groups(Fakes.settingsWakeTested) }
        shoot("08-wake-tested", Phone.SmallScroll, fontScale = 1.5f) { groups(Fakes.settingsWakeTested) }
    }

    @Test
    fun wakeChoices() {
        shoot("08-wake-name", Phone.Scroll) { SheetOver { WakeChoices(WakeChoice.Name, Fakes.settings.prefs, onPick = {}) } }
        shoot("08-wake-name", Phone.Small, fontScale = 1.5f) { SheetOver { WakeChoices(WakeChoice.Name, Fakes.settings.prefs, onPick = {}) } }
        shoot("08-wake-greeting") { SheetOver { WakeChoices(WakeChoice.Greeting, Fakes.settings.prefs, onPick = {}) } }
        shoot("08-wake-sensitivity") { SheetOver { WakeChoices(WakeChoice.Sensitivity, Fakes.settings.prefs, onPick = {}) } }
        shoot("08-wake-sensitivity", Phone.Small, fontScale = 1.5f) { SheetOver { WakeChoices(WakeChoice.Sensitivity, Fakes.settings.prefs, onPick = {}) } }
    }

    @Test
    fun language() {
        shoot("17-language", Phone.Scroll) { SheetOver { LanguageChoices(Fakes.settings.prefs, onPick = {}) } }
        shoot("17-language", Phone.Small, fontScale = 1.5f) { SheetOver { LanguageChoices(Fakes.settings.prefs, onPick = {}) } }
    }

    @Test
    fun scheduled() {
        shoot("09-scheduled") { ScheduledContent(Fakes.scheduled) }
        shoot("09-scheduled", Phone.Small) { ScheduledContent(Fakes.scheduled) }
    }

    @Test
    fun schedule() = shoot("10-schedule") { ScheduleContent(Fakes.schedule) }

    @Test
    fun scheduleOnAComputer() {
        // The server's own fixture: on bag-end, in a folder, Edit, and the computer away.
        shoot("10-schedule-on-computer") { ScheduleContent(Fakes.scheduleOnComputer) }
        shoot("10-schedule-on-computer", Phone.Small, fontScale = 1.5f) { ScheduleContent(Fakes.scheduleOnComputer) }
    }

    @Test
    fun scheduleEdit() {
        shoot("11-schedule-edit", Phone.Scroll) { ScheduleEditContent(Fakes.editing, editing = false) }
        // "Notify when": the three choices, and "When…" with the person's words.
        shoot("11-schedule-edit-notify", Phone.Scroll) { ScheduleEditContent(Fakes.editingNotify, editing = true) }
        shoot("11-schedule-edit-notify", Phone.SmallScroll, fontScale = 1.5f) { ScheduleEditContent(Fakes.editingNotify, editing = true) }
    }

    @Test
    fun reports() {
        shoot("40-reports") { ReportsContent(Fakes.reports, NoReportsActions) }
        shoot("40-reports", Phone.Small, fontScale = 1.5f) { ReportsContent(Fakes.reports, NoReportsActions) }
        shoot("40-reports-none") { ReportsContent(ReportsState(), NoReportsActions) }
        shoot("40-reports-older-instance") { ReportsContent(ReportsState(unsupported = true), NoReportsActions) }
        // One report as a page; the page in it is a card, not a page and not its source.
        shoot("41-report", Phone.Scroll) { ReportContent(Fakes.report) }
        shoot("41-report", Phone.Small, fontScale = 1.5f) { ReportContent(Fakes.report) }
        // A task's runs: the note, the way to a report, an unchanged look.
        shoot("42-schedule-runs-reports", Phone.Scroll) { ScheduleContent(Fakes.scheduleWithReports) }
        shoot("42-schedule-runs-reports", Phone.SmallScroll, fontScale = 1.5f) { ScheduleContent(Fakes.scheduleWithReports) }
    }

    @Test
    fun scheduleRun() {
        shoot("12-schedule-run") { RunContent(Fakes.run) }
        shoot("12-schedule-run", Phone.Small) { RunContent(Fakes.run) }
    }

    @Test
    fun activity() {
        val sheet: @Composable () -> Unit = { SheetOver { ActivityContent(Fakes.activity, readAt = 0, now = 0, actions = NoActivityActions) } }
        val open: @Composable () -> Unit = {
            SheetOver { ActivityContent(Fakes.activity, readAt = 0, now = 0, actions = NoActivityActions, unfolded = true) }
        }
        shoot("14-activity", content = sheet)
        shoot("14-activity", Phone.Small, content = sheet)
        shoot("14-activity", Phone.Small, fontScale = 1.5f, content = sheet)
        shoot("14-activity", Phone.Scroll, content = sheet)
        shoot("15-activity-open", Phone.Scroll, content = open)
        // Spec 3.4.0's subagents: the reduced list above the background tasks.
        val tree: @Composable () -> Unit = {
            SheetOver { ActivityContent(Fakes.activity, readAt = 0, now = 0, actions = NoActivityActions, tree = Fakes.tree) }
        }
        shoot("26-subagents", Phone.Scroll, content = tree)
        shoot("26-subagents", Phone.Small, fontScale = 1.5f, content = tree)
        shoot("16-activity-new-chat") {
            SheetOver { ActivityContent(eu.llembas.android.api.Activity(), readAt = 0, now = 0, actions = NoActivityActions, canCompact = false) }
        }
    }

    /**
     * The assistant's sheet over an app (a chat stands in for whatever was on screen): at half
     * height as it opens, at a small phone's size and with large type too.
     */
    @Test
    fun assistant() {
        val over: (AssistUiState, Boolean) -> @Composable () -> Unit = { state, locked ->
            {
                Box(Modifier.fillMaxSize()) {
                    ChatContent(Fakes.transcript, NoChatActions, agent = false)
                    AssistSheetFrame(onDismiss = {}) { handle, _ -> AssistSheetContent(state, NoAssistActions, locked, handle) }
                }
            }
        }
        shoot("18-assistant-listening", content = over(Fakes.assistListening, false))
        shoot("18-assistant-listening", Phone.Small, fontScale = 1.5f, content = over(Fakes.assistListening, false))
        shoot("19-assistant-opened", content = over(Fakes.assistOpened, false))
        shoot("19-assistant-opened", Phone.Small, content = over(Fakes.assistOpened, false))
        shoot("20-assistant-answered", content = over(Fakes.assistAnswered, false))
        shoot("21-assistant-writing", content = over(Fakes.assistWriting, false))
        shoot("22-assistant-locked", content = over(Fakes.assistOpened, true))
        shoot("23-assistant-offline", Phone.Small, content = over(Fakes.assistOffline, false))
        shoot("24-assistant-conversation", content = over(Fakes.assistConversation, false))
        shoot("24-assistant-conversation", Phone.Small, fontScale = 1.5f, content = over(Fakes.assistConversation, false))
    }

    /**
     * The cards a reply waits on, as the server's contract (`event_ask.json`) has them: a device's
     * approval (the subagent asking, the heading, the command in its box, the server's reason),
     * and a question.
     */
    @Test
    fun cards() {
        val frame = requireNotNull(javaClass.classLoader!!.getResource("event_ask.json")).readText()
        val cards = eu.llembas.android.api.LembasJson.decodeFromString(eu.llembas.android.api.AskFrame.serializer(), frame).cards
        val approval = cards.first { it.kind == "approval" }
        val fromSubagent = cards.first { it.kind == "approval" && it.items.any { item -> item.subagent != null } }
        val question = cards.first { it.kind == "question" }
        shoot("24-approval") { SheetOver { CardSheet(approval, onAnswer = {}) } }
        shoot("24-approval-subagent") { SheetOver { CardSheet(fromSubagent, onAnswer = {}) } }
        shoot("24-approval", Phone.Small, fontScale = 1.5f) { SheetOver { CardSheet(approval, onAnswer = {}) } }
        shoot("25-question") { SheetOver { CardSheet(question, onAnswer = {}) } }
    }

    @Test
    fun schedulingModels() = shoot("13-scheduling-models") { SchedulingModelsContent(Fakes.schedulingModels) }

    @Test
    fun archived() {
        shoot("27-archived") { ArchivedContent(Fakes.chats, NoArchivedActions) }
        shoot("27-archived", Phone.Small, fontScale = 1.5f) { ArchivedContent(Fakes.chats, NoArchivedActions) }
        shoot("27-archived-none") { ArchivedContent(ChatsState(), NoArchivedActions) }
    }

    @Test
    fun picture() {
        // The picture's place is its size before it loads (screenshots fetch nothing).
        shoot("29-generated-image") { ChatContent(Fakes.picture, NoChatActions, agent = false) }
        shoot("29-generated-image", Phone.Small, fontScale = 1.5f) { ChatContent(Fakes.picture, NoChatActions, agent = false) }
    }

    @Test
    fun memory() {
        shoot("28-memory") { MemoryContent(Fakes.memory, NoMemoryActions) }
        shoot("28-memory", Phone.Small, fontScale = 1.5f) { MemoryContent(Fakes.memory, NoMemoryActions) }
        shoot("28-memory-none") { MemoryContent(MemoryState(), NoMemoryActions) }
    }

    /** The file viewer's code in the theme's colours, a few languages, and a reply's code block. */
    @Test
    fun highlighting() {
        for ((name, file) in Fakes.codeFiles) {
            shoot("33-viewer-$name") { FileBody("code", file.first, line = 3, text = file.second) }
        }
        shoot("33-viewer-kotlin", Phone.Small, fontScale = 1.5f) { FileBody("code", "kotlin", line = 0, text = Fakes.codeFiles.getValue("kotlin").second) }
        shoot("34-code-block") {
            Column(Modifier.fillMaxSize().padding(16.dp)) { CodeBlock("ts", Fakes.codeFiles.getValue("typescript").second) }
        }
    }

    /**
     * The phone_action cards as the server's contract has them (`event_ask_phone.json`): an
     * alarm, a route through a stop with both map apps to choose from, a stop added in Google
     * Maps (its limit said), and the phone still locked.
     */
    @Test
    fun phoneActions() {
        val frame = requireNotNull(javaClass.classLoader!!.getResource("event_ask_phone.json")).readText()
        val phones = eu.llembas.android.api.LembasJson.decodeFromString(eu.llembas.android.api.AskFrame.serializer(), frame).cards
            .map { requireNotNull(it.items.single().phone) }
        val both = listOf(NavApp.GoogleMaps, NavApp.OsmAnd)
        val card: (Int, NavApp?, PhoneCardState) -> @Composable () -> Unit = { i, chosen, state ->
            { Column(Modifier.fillMaxSize().padding(16.dp)) { PhoneCardContent(phones[i], both, chosen, state) } }
        }
        shoot("35-phone-alarm", content = card(0, null, PhoneCardState()))
        shoot("35-phone-alarm", Phone.Small, fontScale = 1.5f, content = card(0, null, PhoneCardState()))
        shoot("36-phone-navigate-pick", content = card(1, null, PhoneCardState()))
        shoot("37-phone-add-stop-google", content = card(2, NavApp.GoogleMaps, PhoneCardState()))
        shoot("38-phone-stop-locked", content = card(3, NavApp.OsmAnd, PhoneCardState(locked = true)))
    }

    /** "Ask LLeMbas" from another app's share menu: a link, and a picture with a file. */
    @Test
    fun share() {
        val over: (ShareState) -> @Composable () -> Unit = { state ->
            {
                Box(Modifier.fillMaxSize()) {
                    ChatContent(Fakes.transcript, NoChatActions, agent = false)
                    AssistSheetFrame(onDismiss = {}) { handle, _ -> ShareSheetContent(state, NoShareActions, handle) }
                }
            }
        }
        shoot("30-share-link", content = over(Fakes.shareLink))
        shoot("30-share-link", Phone.Small, fontScale = 1.5f, content = over(Fakes.shareLink))
        shoot("31-share-files", content = over(Fakes.shareFiles))
    }

    /** The home-screen widget, as the launcher inflates it (light; [widgetNight] is dark). */
    @Test
    fun widget() = shootWidget("light")

    @Test
    @Config(qualifiers = "+night")
    fun widgetNight() = shootWidget("dark")

    private fun shootWidget(theme: String) {
        for (phone in listOf(Phone.Large, Phone.Small)) {
            captureRoboImage(
                filePath = "build/screenshots/${phone.tag}-32-widget-$theme.png",
                roborazziComposeOptions = RoborazziComposeOptions {
                    size(phone.widthDp, 120)
                    inspectionMode(true)
                },
            ) {
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    AndroidView(
                        factory = { context -> LayoutInflater.from(context).inflate(R.layout.widget_ask, null) },
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                    )
                }
            }
        }
    }
}

/**
 * A bottom sheet's content as the sheet shows it, over a dimmed page: the sheet itself is a
 * window of its own, which a screenshot of the page does not hold.
 */
@Composable
private fun SheetOver(content: @Composable () -> Unit) {
    val palette = LocalPalette.current
    Box(Modifier.fillMaxSize().background(palette.bg)) {
        Box(Modifier.fillMaxSize().background(palette.inkInverse.copy(alpha = 0.5f)))
        Surface(
            color = palette.surface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.padding(vertical = 16.dp).size(width = 32.dp, height = 4.dp).background(palette.inkFaint, RoundedCornerShape(2.dp)))
                content()
            }
        }
    }
}
