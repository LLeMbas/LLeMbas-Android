package eu.llembas.android

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import eu.llembas.android.wake.AssistPresence
import eu.llembas.android.wake.WakeBootReceiver
import eu.llembas.android.wake.WakePause
import eu.llembas.android.wake.WakeState
import eu.llembas.android.wake.WakeStatus
import eu.llembas.android.wake.WakeWordService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The wake-word service as Android starts it. What a phone showed before: every time the app was
 * opened the service was started again and posted "Listening for" the default phrase, whatever
 * was chosen and whether or not anything listened.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en")
class WakeServiceTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    private fun title(notification: Notification) = notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    @After
    fun off() = WakeStatus.set(WakeState.Off)

    @Test
    fun aStartNeverPostsAPhraseItIsNotListeningFor() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val service = Robolectric.buildService(WakeWordService::class.java).create()
        // The first start: nothing is known yet, so it says it is starting -- no phrase.
        service.startCommand(0, 1)
        assertEquals("Starting the wake word…", title(shadowOf(service.get()).lastForegroundNotification))
        service.destroy()
    }

    @Test
    fun aStartClearsWhatAnEarlierInstanceLeftBehind() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val manager = app.getSystemService(NotificationManager::class.java)
        WakeWordService.channel(app)
        // "Tap to listen again" from a restart, and a hold of the microphone nobody ended.
        manager.notify(WakeBootReceiver.RESUME_ID, NotificationCompat.Builder(app, WakeWordService.CHANNEL).setSmallIcon(R.drawable.ic_notification).build())
        AssistPresence.release()
        val before = AssistPresence.open.value
        AssistPresence.holdFor(600_000)
        assertEquals(before + 1, AssistPresence.open.value)
        val service = Robolectric.buildService(WakeWordService::class.java).create()
        service.startCommand(0, 1)
        assertNull(shadowOf(manager).getNotification(WakeBootReceiver.RESUME_ID))
        assertEquals(before, AssistPresence.open.value)
        service.destroy()
    }

    @Test
    fun stopIsTheSettingGoingOffNotTheServiceStoppedUnderAStart() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        WakeStatus.set(WakeState.Listening("Hey Assistant"))
        while (shadowOf(app).nextStoppedService != null) Unit
        WakeWordService.stop()
        // Nobody calls stopService: the service ends itself once it has shown its notification.
        assertNull(shadowOf(app).nextStoppedService)
        assertEquals(WakeState.Off, WakeStatus.state.value)
    }

    @Test
    fun withoutTheMicrophonePermissionItIsNotStartedAndSaysWhy() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        while (shadowOf(app).nextStartedService != null) Unit
        WakeWordService.start(app)
        assertEquals(WakeState.Paused(WakePause.NoMicrophone), WakeStatus.state.value)
        assertNull(shadowOf(app).nextStartedService)
        // Given back (in the system's settings), the next time the app comes to the screen starts it.
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        WakeWordService.start(app)
        assertEquals(WakeWordService::class.java.name, shadowOf(app).nextStartedService.component?.className)
    }
}
