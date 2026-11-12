package eu.llembas.android

import android.Manifest
import android.app.Application
import android.app.Notification
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
        // The app is opened again while it listens for the chosen phrase: the start says the same.
        WakeStatus.set(WakeState.Listening("OK Google"))
        service.startCommand(0, 2)
        assertEquals("Listening for “OK Google”", title(shadowOf(service.get()).lastForegroundNotification))
        // And while it is paused, a start does not turn the notification into "listening".
        WakeStatus.set(WakeState.Paused(WakePause.BatterySaver))
        service.startCommand(0, 3)
        assertEquals("Wake word paused: battery saver is on", title(shadowOf(service.get()).lastForegroundNotification))
        service.destroy()
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
