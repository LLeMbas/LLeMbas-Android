package eu.llembas.android

import eu.llembas.android.wake.WakeAttempt
import eu.llembas.android.wake.WakeFacts
import eu.llembas.android.wake.WakeOpener
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What is tried when the phrase is heard, in which order, and what is written down about it --
 * without a phone: Android's part is a script here (connected or not, refuses or not, a sheet
 * comes on the n-th wait or never).
 */
class WakeOpenerTest {
    /** One wake against a scripted Android. */
    private class Phone(
        var facts: WakeFacts,
        /** The waits for a sheet, in order: how long each took, or null for "none came". */
        val sheets: MutableList<Long?> = mutableListOf(),
        val connects: Boolean = false,
        val refuses: Throwable? = null,
        val activityRefused: Throwable? = null,
    ) {
        val did = mutableListOf<String>()

        val opener = WakeOpener(
            facts = { facts },
            awaitConnected = {
                did += "wait-connect"
                connects
            },
            showSession = {
                did += "session"
                refuses?.let { throw it }
            },
            startActivity = {
                did += "activity"
                activityRefused?.let { throw it }
            },
            sheetCame = {
                did += "wait-sheet"
                if (sheets.isEmpty()) null else sheets.removeAt(0)
            },
            notify = { did += "notify" },
            now = { 1_000 },
        )
    }

    private val assistant = WakeFacts(connected = true, active = true, role = true)

    @Test
    fun theAssistantsSheetOpensAndIsCountedOnlyOnceItIsUp() = runTest {
        val phone = Phone(assistant, sheets = mutableListOf(180))
        val attempt = phone.opener.open("Hey Assistant")
        assertEquals(listOf("session", "wait-sheet"), phone.did)
        assertEquals(WakeAttempt.OUT_OPENED, attempt.outcome)
        assertEquals("session", attempt.way)
        assertEquals(180L, attempt.sheetMs)
        assertEquals("", attempt.fallback)
        assertEquals("Hey Assistant", attempt.phrase)
        assertTrue(attempt.assistant && attempt.active)
    }

    @Test
    fun aSlowSessionIsWaitedForAgainAndNothingElseIsPutBesideIt() = runTest {
        // No sheet in the first wait, then it comes: one sheet, no activity, no notification.
        val phone = Phone(assistant, sheets = mutableListOf(null, 400))
        val attempt = phone.opener.open("Hey Assistant")
        assertEquals(listOf("session", "wait-sheet", "wait-sheet"), phone.did)
        assertEquals(WakeAttempt.OUT_OPENED, attempt.outcome)
        assertEquals(WakeOpener.SHEET_WAIT_MS + 400, attempt.sheetMs)
    }

    @Test
    fun aSessionThatNeverComesFallsToTheActivityThenToTheNotification() = runTest {
        // Connected, so the activity may start: it is waited for, and counts when it comes.
        val came = Phone(assistant, sheets = mutableListOf(null, null, 300))
        val opened = came.opener.open("Hey Assistant")
        assertEquals(listOf("session", "wait-sheet", "wait-sheet", "activity", "wait-sheet"), came.did)
        assertEquals(WakeAttempt.OUT_NO_SHEET, opened.outcome)
        assertEquals("activity", opened.fallback)
        // Nothing comes at all: the notification, and it is said.
        val never = Phone(assistant)
        val told = never.opener.open("Hey Assistant")
        assertEquals("notify", never.did.last())
        assertEquals(1, never.did.count { it == "notify" })
        assertEquals("notification", told.fallback)
        assertEquals(-1L, told.sheetMs)
    }

    @Test
    fun aRefusalIsKeptInAndroidsOwnWords() = runTest {
        val phone = Phone(assistant, refuses = SecurityException("Caller is not the current voice interaction service"), sheets = mutableListOf(250))
        val attempt = phone.opener.open("Hey Assistant")
        // Refused: no waiting for a session that was never asked for; straight to the activity.
        assertEquals(listOf("session", "activity", "wait-sheet"), phone.did)
        assertEquals(WakeAttempt.OUT_REFUSED, attempt.outcome)
        assertEquals("SecurityException: Caller is not the current voice interaction service", attempt.detail)
        assertEquals("activity", attempt.fallback)
    }

    @Test
    fun notConnectedAndNotOnScreenIsANotificationAndSaysWhy() = runTest {
        // The role held, but Android does not use the service: nothing to wait for.
        val phone = Phone(WakeFacts(connected = false, active = false, role = true, locked = true, screenOn = false))
        val attempt = phone.opener.open("Hey Assistant")
        // The activity is still tried (a phone may allow it), but it is not relied on or waited for.
        assertEquals(listOf("activity", "notify"), phone.did)
        assertEquals(WakeAttempt.OUT_NOT_CONNECTED, attempt.outcome)
        assertEquals("notification", attempt.way)
        assertEquals("notification", attempt.fallback)
        assertTrue(attempt.assistant)
        assertFalse(attempt.active)
        assertFalse(attempt.waited)
        assertTrue(attempt.locked)
        assertFalse(attempt.screenOn)
    }

    @Test
    fun activeButNotConnectedYetIsGivenAMomentToConnect() = runTest {
        val waiting = WakeFacts(connected = false, active = true, role = true)
        val phone = Phone(waiting, connects = true, sheets = mutableListOf(200))
        val attempt = phone.opener.open("Hey Assistant")
        assertEquals(listOf("wait-connect", "session", "wait-sheet"), phone.did)
        assertEquals(WakeAttempt.OUT_OPENED, attempt.outcome)
        assertTrue(attempt.waited)
        // It does not connect in time: the record says that it was waited for.
        val late = Phone(waiting, connects = false)
        val missed = late.opener.open("Hey Assistant")
        assertEquals(listOf("wait-connect", "activity", "notify"), late.did)
        assertEquals(WakeAttempt.OUT_NOT_CONNECTED, missed.outcome)
        assertTrue(missed.waited)
    }

    @Test
    fun onScreenWithoutTheAssistantTheSheetIsTheAppsOwnScreen() = runTest {
        val phone = Phone(WakeFacts(connected = false, active = false, role = false, visible = true), sheets = mutableListOf(120))
        val attempt = phone.opener.open("Hey Assistant")
        assertEquals(listOf("activity", "wait-sheet"), phone.did)
        assertEquals("activity", attempt.way)
        assertEquals("activity", attempt.fallback)
        assertEquals(120L, attempt.sheetMs)
        assertFalse(attempt.assistant)
    }

    @Test
    fun withTheScreenOffTheSheetIsTheActivityAndItIsNotStartedTwice() = runTest {
        val dark = assistant.copy(screenOn = false)
        val phone = Phone(dark, sheets = mutableListOf(350))
        assertEquals("assistant_activity", phone.opener.open("Hey Assistant").way)
        assertEquals(listOf("activity", "wait-sheet"), phone.did)
        // It does not come up: a notification, not the same activity once more.
        val never = Phone(dark)
        val told = never.opener.open("Hey Assistant")
        assertEquals(listOf("activity", "wait-sheet", "notify"), never.did)
        assertEquals(WakeAttempt.OUT_NO_SHEET, told.outcome)
    }

    @Test
    fun theRecordCarriesWhatTheAssistantServiceHadSaid() = runTest {
        val phone = Phone(WakeFacts(connected = false, active = false, role = true, recognizers = false))
        val link = WakeAttempt(createdAt = 10, destroyedAt = 20, readyAt = 11, shutdownAt = 19)
        val attempt = phone.opener.open("OK Computer", link)
        assertEquals(1_000L, attempt.at)
        assertEquals(listOf(10L, 20L, 11L, 19L), listOf(attempt.createdAt, attempt.destroyedAt, attempt.readyAt, attempt.shutdownAt))
        assertFalse(attempt.recognizers)
    }
}
