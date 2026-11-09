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
import eu.llembas.android.ui.chat.ChatActions
import eu.llembas.android.ui.chat.ChatContent
import eu.llembas.android.ui.chat.ModelPickerContent
import eu.llembas.android.ui.chat.TuneContent
import eu.llembas.android.ui.chat.modelId
import eu.llembas.android.ui.home.DrawerActions
import eu.llembas.android.ui.home.DrawerContent
import eu.llembas.android.ui.scheduled.ScheduleContent
import eu.llembas.android.ui.scheduled.ScheduleEditContent
import eu.llembas.android.ui.scheduled.ScheduledContent
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

private object NoSettingsActions : SettingsActions

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
        shoot("01-new-chat") { ChatContent(Fakes.newChat, NoChatActions, agent = false) }
        shoot("01-new-chat", Phone.Small) { ChatContent(Fakes.newChat, NoChatActions, agent = false) }
    }

    @Test
    fun newAgentChat() = shoot("02-new-agent-chat") { ChatContent(Fakes.newAgentChat, NoChatActions, agent = true) }

    @Test
    fun transcript() {
        shoot("03-transcript") { ChatContent(Fakes.transcript, NoChatActions, agent = false) }
        shoot("03-transcript", Phone.Small) { ChatContent(Fakes.transcript, NoChatActions, agent = false) }
        shoot("03-transcript", Phone.Small, fontScale = 1.5f) { ChatContent(Fakes.transcript, NoChatActions, agent = false) }
    }

    @Test
    fun writing() = shoot("04-writing") { ChatContent(Fakes.writing, NoChatActions, agent = false) }

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
    }

    @Test
    fun modelPicker() = shoot("06-model-picker") {
        SheetOver { ModelPickerContent(Fakes.models, Fakes.transcript.modelId, onPick = {}) }
    }

    @Test
    fun tune() = shoot("07-tune") {
        SheetOver { TuneContent(Fakes.tuned, NoChatActions, agent = false) }
    }

    @Test
    fun settings() {
        shoot("08-settings") { SettingsContent(Fakes.settings, NoSettingsActions) }
        shoot("08-settings", Phone.Small) { SettingsContent(Fakes.settings, NoSettingsActions) }
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
