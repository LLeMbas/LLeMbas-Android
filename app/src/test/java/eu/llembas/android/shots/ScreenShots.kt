package eu.llembas.android.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import eu.llembas.android.assist.AssistActions
import eu.llembas.android.assist.AssistSheetContent
import eu.llembas.android.assist.AssistSheetFrame
import eu.llembas.android.assist.AssistUiState
import eu.llembas.android.ui.chat.ActivityActions
import eu.llembas.android.ui.chat.ActivityContent
import eu.llembas.android.ui.chat.CardSheet
import eu.llembas.android.ui.chat.ChatActions
import eu.llembas.android.ui.chat.ChatContent
import eu.llembas.android.ui.chat.ModelPickerContent
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
import eu.llembas.android.ui.scheduled.RunContent
import eu.llembas.android.ui.scheduled.ScheduleContent
import eu.llembas.android.ui.scheduled.ScheduleEditContent
import eu.llembas.android.ui.scheduled.ScheduledContent
import eu.llembas.android.ui.scheduled.SchedulingModelsContent
import eu.llembas.android.ui.settings.LanguageChoices
import eu.llembas.android.ui.settings.SettingsActions
import eu.llembas.android.ui.settings.SettingsContent
import eu.llembas.android.ui.theme.LocalPalette
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

private object NoAssistActions : AssistActions

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
                        Fakes.chats,
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
    fun scheduleEdit() = shoot("11-schedule-edit") { ScheduleEditContent(Fakes.editing, editing = false) }

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
