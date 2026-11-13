package eu.llembas.android

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import eu.llembas.android.wake.WakeAttempt
import eu.llembas.android.wake.WakeFailure
import eu.llembas.android.wake.WakePause
import eu.llembas.android.wake.WakeState
import eu.llembas.android.wake.WakeWordService
import eu.llembas.android.wake.wakeAttemptWords
import eu.llembas.android.wake.wakeHeardNotification
import eu.llembas.android.wake.wakeNotification
import eu.llembas.android.wake.wakeTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZoneOffset
import java.util.Locale

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

    @Test
    fun heardButUnableToOpenIsANotificationThatComesUp() {
        val heard = wakeHeardNotification(app)
        assertEquals("Heard you. Tap to ask.", heard.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        // Its own channel, which may come up over what is on screen; the listening one is quiet.
        assertEquals(WakeWordService.CHANNEL_HEARD, heard.channelId)
        WakeWordService.channel(app)
        val manager = app.getSystemService(NotificationManager::class.java)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(WakeWordService.CHANNEL_HEARD).importance)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(WakeWordService.CHANNEL).importance)
        // Fixed words, nothing from the account: it may show on the lock screen. The tap asks.
        assertEquals(Notification.VISIBILITY_PUBLIC, heard.visibility)
        assertEquals(Notification.FLAG_AUTO_CANCEL, heard.flags and Notification.FLAG_AUTO_CANCEL)
        assertNotNull(heard.contentIntent)
    }

    @Test
    fun theFallbackSaysWhyInFixedWords() {
        fun line(assistant: Boolean) = wakeHeardNotification(app, assistant).extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        // The assistant, and still it could not open by itself this time.
        assertEquals("Could not open by itself — tap to ask", line(assistant = true))
        // Not the assistant: that is the reason.
        assertEquals("LLeMbas can open by itself over other apps only as the phone's digital assistant app.", line(assistant = false))
    }

    @Test
    fun theLastWakeInPlainWords() {
        val zone = ZoneOffset.UTC
        val at = 1_773_129_600_000 // Tue 10 Mar 2026, 08:00:00 UTC
        fun words(attempt: WakeAttempt) = wakeAttemptWords(app, attempt, zone, Locale.ENGLISH)
        assertEquals(
            listOf("Tue 10 Mar, 08:00:00 · “Hey Assistant”", "Opened the assistant."),
            words(WakeAttempt(at = at, phrase = "Hey Assistant", way = "session", outcome = WakeAttempt.OUT_OPENED, assistant = true, readyAt = at - 60_000)),
        )
        // The assistant in name, never connected in this run of the app: said step by step.
        assertEquals(
            listOf(
                "Tue 10 Mar, 08:00:00 · “Hey Assistant”",
                "Android had not connected LLeMbas as the assistant, so it could not open the sheet.",
                "Posted a notification to tap instead.",
                "LLeMbas was the digital assistant app.",
                "Android had not connected the assistant since the app started.",
                "Android connected it again at Tue 10 Mar, 08:00:30.",
            ),
            words(
                WakeAttempt(
                    at = at,
                    phrase = "Hey Assistant",
                    way = "notification",
                    outcome = WakeAttempt.OUT_NOT_CONNECTED,
                    fallback = "notification",
                    assistant = true,
                    connectedLater =
                    at + 30_000,
                ),
            ),
        )
        // Android's own words where it refused, and when it had connected and dropped the service.
        val refused = words(
            WakeAttempt(
                at = at,
                phrase = "OK Computer",
                way = "session",
                outcome = WakeAttempt.OUT_REFUSED,
                detail = "SecurityException: not the current voice interaction service",
                fallback = "activity",
                readyAt = at - 3_600_000,
                shutdownAt = at - 60_000,
            ),
        )
        assertEquals("Android refused to open the assistant: SecurityException: not the current voice interaction service", refused[1])
        assertEquals("Opened it as a screen of the app instead.", refused[2])
        assertEquals("LLeMbas was not the digital assistant app.", refused[3])
        assertEquals("Android had connected the assistant at Tue 10 Mar, 07:00:00.", refused[4])
        assertEquals("Android had disconnected it at Tue 10 Mar, 07:59:00.", refused[5])
        assertEquals("Heard in a test: nothing was to open.", words(WakeAttempt(at = at, phrase = "Hey Assistant", outcome = WakeAttempt.OUT_TESTED))[1])
        assertEquals(
            "Asked Android to open the assistant; it did not refuse, but no sheet came.",
            words(WakeAttempt(at = at, phrase = "Hey Assistant", way = "session", outcome = WakeAttempt.OUT_NO_SHEET, fallback = "notification"))[1],
        )
    }
}
