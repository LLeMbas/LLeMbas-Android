package eu.llembas.android

import android.app.Application
import android.app.Notification
import eu.llembas.android.push.Notifications
import eu.llembas.android.push.ReportNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A report's notification, both of its faces: unlocked, the report's title and summary; on the
 * lock screen the app's fixed words and nothing from the account (rule 5).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en")
class ReportNotificationTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    private val notice = ReportNotice("rep1", "Two tenders opened", "Storage bids are due in May.", high = false)

    private fun Notification.words() = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT).map {
        extras.getCharSequence(it)?.toString().orEmpty()
    }

    @Test
    fun unlockedItSaysTheReportLockedOnlyThatThereIsOne() {
        val shown = Notifications.reportNotification(app, "home", notice)
        assertEquals(LembasApp.CHANNEL_REPORTS, shown.channelId)
        assertEquals("Two tenders opened", shown.words()[0])
        assertEquals("Storage bids are due in May.", shown.words()[1])
        // Private: Android shows the public version on a locked screen.
        assertEquals(Notification.VISIBILITY_PRIVATE, shown.visibility)
        val locked = shown.publicVersion
        assertNotNull(locked)
        assertEquals(listOf("LLeMbas", "New report", ""), locked.words())
        // Nothing of the report anywhere in what the lock screen gets.
        val all = locked.words().joinToString(" ") + locked.extras.keySet().joinToString(" ") { locked.extras.get(it)?.toString().orEmpty() }
        assertFalse(all.contains("tenders"))
        assertFalse(all.contains("Storage"))
    }

    @Test
    fun itOffersOpenAndMarkReadAndTheSecondNeedsThePhoneUnlocked() {
        val shown = Notifications.reportNotification(app, "home", notice)
        assertEquals(listOf("Open", "Mark read"), shown.actions.map { it.title.toString() })
        assertFalse(shown.actions[0].isAuthenticationRequired)
        assertTrue(shown.actions[1].isAuthenticationRequired)
        assertNotNull(shown.contentIntent)
        assertEquals(Notification.FLAG_AUTO_CANCEL, shown.flags and Notification.FLAG_AUTO_CANCEL)
        // A report without a title still says something that is the app's own.
        assertEquals("New report", Notifications.reportNotification(app, "home", notice.copy(title = "")).words()[0])
    }
}
