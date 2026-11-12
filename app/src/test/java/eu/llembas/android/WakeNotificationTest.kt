package eu.llembas.android

import android.app.Application
import android.app.Notification
import eu.llembas.android.wake.WakeFailure
import eu.llembas.android.wake.WakePause
import eu.llembas.android.wake.WakeState
import eu.llembas.android.wake.wakeNotification
import eu.llembas.android.wake.wakeTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What the wake word's notification says, from its state alone: the phrase only while it really
 * listens for it, a pause with its reason, a failure with what failed -- and nothing else, ever.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en")
class WakeNotificationTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    private fun title(state: WakeState) = wakeNotification(app, state).extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    private fun line(state: WakeState) = wakeNotification(app, state).extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    @Test
    fun itNamesThePhraseOnlyWhileItListensForIt() {
        assertEquals("Listening for “Hey Assistant”", title(WakeState.Listening("Hey Assistant")))
        assertEquals("Nothing leaves the phone until you say it.", line(WakeState.Listening("Hey Assistant")))
        // A service that has just started knows no phrase yet, and names none.
        assertEquals("Starting the wake word…", title(WakeState.Starting))
        assertNull(line(WakeState.Starting))
    }

    @Test
    fun aPauseSaysWhy() {
        assertEquals("Wake word paused: the screen is off", title(WakeState.Paused(WakePause.ScreenOff)))
        assertEquals("Wake word paused: battery saver is on", title(WakeState.Paused(WakePause.BatterySaver)))
        assertEquals("Wake word paused while the assistant is open", title(WakeState.Paused(WakePause.Assistant)))
        assertEquals("Wake word paused: LLeMbas is not allowed to use the microphone", title(WakeState.Paused(WakePause.NoMicrophone)))
        assertEquals(
            "Wake word hears nothing: another app has the microphone, or the phone's microphone is switched off",
            title(WakeState.Paused(WakePause.Silent)),
        )
    }

    @Test
    fun aFailureSaysWhatFailedAndNeverThatItListens() {
        val failures = mapOf(
            WakeFailure.Engine to "Wake word is not working: the engine did not start",
            WakeFailure.Model to "Wake word is not working: its model could not be unpacked on this phone",
            WakeFailure.Microphone to "Wake word is not working: the microphone did not start",
        )
        for ((why, words) in failures) {
            val state = WakeState.Failed(why, "IOException: no space left")
            assertEquals(words, title(state))
            assertEquals("It is not listening. It tries again by itself.", line(state))
            assertFalse(title(state).contains("Listening"))
        }
        assertEquals("Wake word is not working: this build has no engine", title(WakeState.Failed(WakeFailure.NoEngine)))
        assertNull(line(WakeState.Failed(WakeFailure.NoEngine)))
        assertEquals("The wake word is not listening", wakeTitle(app, WakeState.Off))
    }

    @Test
    fun itShowsNothingOfTheAccountAndCannotBeSwipedAway() {
        val notification = wakeNotification(app, WakeState.Listening("Hey Assistant"))
        assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
        assertEquals(Notification.FLAG_ONGOING_EVENT, notification.flags and Notification.FLAG_ONGOING_EVENT)
        assertEquals(1, notification.actions.size)
    }
}
