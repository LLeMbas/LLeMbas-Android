package eu.llembas.android

import eu.llembas.android.api.LembasJson
import eu.llembas.android.auth.Prefs
import eu.llembas.android.wake.AppVisibility
import eu.llembas.android.wake.AssistLink
import eu.llembas.android.wake.AssistPresence
import eu.llembas.android.wake.WakeAttempt
import eu.llembas.android.wake.WakeError
import eu.llembas.android.wake.WakeFailure
import eu.llembas.android.wake.WakePause
import eu.llembas.android.wake.WakePhrases
import eu.llembas.android.wake.WakeSensitivity
import eu.llembas.android.wake.WakeState
import eu.llembas.android.wake.WakeStatus
import eu.llembas.android.wake.WakeWay
import eu.llembas.android.wake.WakeWords
import eu.llembas.android.wake.checkKeywords
import eu.llembas.android.wake.unpackWakeModel
import eu.llembas.android.wake.wakeBackoff
import eu.llembas.android.wake.wakeFallback
import eu.llembas.android.wake.wakeKeywords
import eu.llembas.android.wake.wakeLogAdd
import eu.llembas.android.wake.wakeLogConnected
import eu.llembas.android.wake.wakePause
import eu.llembas.android.wake.wakeStateAtStart
import eu.llembas.android.wake.wakeWay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class WakeTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val files = listOf("encoder.int8.onnx", "tokens.txt")

    /** The APK's assets as [unpackWakeModel] opens them: by stream, counted. */
    private class Assets(private val content: Map<String, String>) : (String) -> InputStream {
        var opened = 0

        override fun invoke(name: String): InputStream {
            if (name != "version") opened++
            return content[name]?.byteInputStream() ?: throw FileNotFoundException(name)
        }
    }

    @Test
    fun theModelIsCopiedOncePerVersion() {
        val dir = File(folder.root, "wakeword")
        val first = Assets(mapOf("version" to "aaaa\n", "encoder.int8.onnx" to "model one", "tokens.txt" to "A 1"))
        unpackWakeModel(first, dir, files)
        assertEquals("model one", File(dir, "encoder.int8.onnx").readText())
        assertEquals(2, first.opened)
        // The same version again: nothing is copied.
        unpackWakeModel(first, dir, files)
        assertEquals(2, first.opened)
        // An update brings another model: it replaces the old one, though the file names are the same.
        val second = Assets(mapOf("version" to "bbbb\n", "encoder.int8.onnx" to "model two, longer", "tokens.txt" to "A 1"))
        unpackWakeModel(second, dir, files)
        assertEquals("model two, longer", File(dir, "encoder.int8.onnx").readText())
        assertFalse(File(dir, "encoder.int8.onnx.part").exists())
    }

    @Test
    fun aCopyThatWasCutShortIsMadeAgain() {
        val dir = File(folder.root, "wakeword")
        // The second file is not in the APK: the copy fails, loudly, and leaves no marker.
        val broken = Assets(mapOf("version" to "aaaa", "encoder.int8.onnx" to "model"))
        assertThrows(FileNotFoundException::class.java) { unpackWakeModel(broken, dir, files) }
        assertFalse(File(dir, "version").exists())
        val whole = Assets(mapOf("version" to "aaaa", "encoder.int8.onnx" to "model", "tokens.txt" to "A 1"))
        unpackWakeModel(whole, dir, files)
        assertEquals(2, whole.opened)
        // A file that went missing afterwards (storage cleared in part) is noticed too.
        File(dir, "tokens.txt").delete()
        unpackWakeModel(whole, dir, files)
        assertEquals(4, whole.opened)
    }

    @Test
    fun aPieceTheModelDoesNotKnowNeverReachesTheEngine() {
        val tokens = "<blk> 0\n▁HE 5\nY 6\n▁AS 7\nS 3\nIST 8\nANT 9\n"
        checkKeywords("▁HE Y ▁AS S IST ANT :1.5 #0.25 @hey_assistant\n", tokens)
        val refused = assertThrows(WakeError::class.java) { checkKeywords("▁HE Y ▁LE M B AS @hey_lembas\n", tokens) }
        assertEquals(WakeFailure.Engine, refused.why)
        assertTrue(refused.detail.contains("▁LE"))
    }

    @Test
    fun theKeywordsFileCarriesBoostAndThresholdInTheEnginesSyntax() {
        val assistant = WakePhrases.byId("hey_assistant")
        assertEquals("▁HE Y ▁AS S IST ANT :1.5 #0.25 @hey_assistant\n", wakeKeywords(assistant))
        assertEquals("▁HE Y ▁AS S IST ANT :1.0 #0.35 @hey_assistant\n", wakeKeywords(assistant, WakeSensitivity.Low))
        assertEquals("▁HE Y ▁AS S IST ANT :2.0 #0.15 @hey_assistant\n", wakeKeywords(assistant, WakeSensitivity.High))
        // Four pieces: short enough to turn up inside other speech, so it must be a little surer.
        assertEquals("▁OKAY ▁COMP U TER :1.5 #0.30 @ok_computer\n", wakeKeywords(WakePhrases.byId("ok_computer")))
        // A phone set to a language with a decimal comma still writes numbers the engine reads.
        val phone = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("sk-SK"))
            assertEquals("▁HE Y ▁AS S IST ANT :1.5 #0.25 @hey_assistant\n", wakeKeywords(assistant))
        } finally {
            Locale.setDefault(phone)
        }
        assertEquals(WakeSensitivity.Normal, WakeSensitivity.byId("nonsense"))
    }

    @Test
    fun aPhraseIsAGreetingAndAName() {
        assertEquals("Hey Assistant", WakePhrases.default.words)
        assertEquals(WakeWords.greetings.size * WakeWords.names.size, WakePhrases.all.size)
        assertEquals(WakePhrases.all.size, WakePhrases.all.map { it.id }.toSet().size)
        assertEquals("OK Google", WakePhrases.byId("ok_google").words)
        assertEquals("Hello Samantha", WakePhrases.byId("hello_samantha").words)
        // Nothing the model cannot hear is offered.
        assertFalse(WakePhrases.all.any { "lembas" in it.id })
    }

    @Test
    fun aSavedPhraseThatIsGoneBecomesHeyAssistant() {
        for (old in listOf("hey_lembas", "ok_lembas", "hello_lembas", "nonsense", "")) assertEquals("hey_assistant", WakePhrases.byId(old).id)
        assertEquals("hey_assistant", WakePhrases.byId("hey_assistant").id)
        // Settings as beta.7 saved them: the old phrase id, no sensitivity yet.
        val saved = LembasJson.decodeFromString(Prefs.serializer(), """{"wakeWord":true,"wakePhrase":"hey_lembas","wakeScreenOnly":false}""")
        assertTrue(saved.wakeWord)
        assertFalse(saved.wakeScreenOnly)
        assertEquals("Hey Assistant", WakePhrases.byId(saved.wakePhrase).words)
        assertEquals(WakeSensitivity.Normal, WakeSensitivity.byId(saved.wakeSensitivity))
        // And a phone that never had the wake word starts on the same phrase.
        assertEquals("hey_assistant", Prefs().wakePhrase)
    }

    @Test
    fun aTestTakesTheWordForTenSecondsAndNoLonger() {
        WakeStatus.startTrial(now = 1_000)
        assertEquals(0, WakeStatus.trial.value.heard)
        // Heard during the test: counted, and the assistant stays shut.
        assertTrue(WakeStatus.heardInTrial(now = 4_000))
        assertTrue(WakeStatus.heardInTrial(now = 10_999))
        assertEquals(2, WakeStatus.trial.value.heard)
        // The test is over by itself, whether or not anybody ended it: the word opens the assistant again.
        assertFalse(WakeStatus.heardInTrial(now = 11_000))
        assertEquals(2, WakeStatus.trial.value.heard)
        // A new test starts its count again.
        WakeStatus.startTrial(now = 20_000)
        assertEquals(0, WakeStatus.trial.value.heard)
    }

    @Test
    fun theAssistantIsOpenedOnlyTheWayAndroidAllows() {
        // The assistant service connected and the screen on: it shows its sheet, on screen or not.
        assertEquals(WakeWay.Session, wakeWay(connected = true, screenOn = true, appVisible = false))
        assertEquals(WakeWay.Session, wakeWay(connected = true, screenOn = true, appVisible = true))
        // Connected, the screen off: the sheet's activity through the service, which turns the screen on.
        assertEquals(WakeWay.AssistantActivity, wakeWay(connected = true, screenOn = false, appVisible = false))
        // Not connected -- whether or not LLeMbas is the assistant in name: only what the app itself may do.
        assertEquals(WakeWay.Activity, wakeWay(connected = false, screenOn = true, appVisible = true))
        assertEquals(WakeWay.Notification, wakeWay(connected = false, screenOn = true, appVisible = false))
        assertEquals(WakeWay.Notification, wakeWay(connected = false, screenOn = false, appVisible = false))
        // And when the assistant's own way did not open the sheet.
        assertEquals(WakeWay.Activity, wakeFallback(appVisible = true))
        assertEquals(WakeWay.Notification, wakeFallback(appVisible = false))
        repeat(3) { AppVisibility.activityStopped() }
        assertFalse(AppVisibility.visible)
        AppVisibility.activityStarted()
        AppVisibility.activityStarted()
        AppVisibility.activityStopped()
        assertTrue(AppVisibility.visible)
        AppVisibility.activityStopped()
        assertFalse(AppVisibility.visible)
    }

    @Test
    fun theHoldAfterTheWordEndsEvenIfWhoeverStartedItIsGone() = runTest {
        AssistPresence.release()
        val before = AssistPresence.open.value
        // The service that heard the word is destroyed before its three seconds are up.
        val service = CoroutineScope(coroutineContext + Job())
        AssistPresence.holdFor(3_000, service)
        assertEquals(before + 1, AssistPresence.open.value)
        runCurrent()
        service.cancel()
        runCurrent()
        assertEquals(before, AssistPresence.open.value)
        // Left to itself it ends after its time; a second word meanwhile holds on from then.
        AssistPresence.holdFor(3_000, this)
        advanceTimeBy(2_000)
        AssistPresence.holdFor(3_000, this)
        advanceTimeBy(2_000)
        assertEquals(before + 1, AssistPresence.open.value)
        advanceTimeBy(1_001)
        assertEquals(before, AssistPresence.open.value)
        // A sheet that is up is counted apart, and a starting service clears only the hold.
        AssistPresence.shown()
        AssistPresence.holdFor(60_000, this)
        AssistPresence.release()
        assertEquals(before + 1, AssistPresence.open.value)
        AssistPresence.hidden()
        assertEquals(before, AssistPresence.open.value)
    }

    @Test
    fun aStartSaysWhatIsTrueAndNeverAPhraseByDefault() {
        // A service that has only just begun knows no phrase.
        assertEquals(WakeState.Starting, wakeStateAtStart(running = false, current = WakeState.Off))
        // Whatever an earlier instance left behind is not this one's to repeat.
        assertEquals(WakeState.Starting, wakeStateAtStart(running = false, current = WakeState.Listening("Hey Assistant")))
        // The app is opened again while it runs: the start says the same as before.
        val listening = WakeState.Listening("OK Google")
        assertEquals(listening, wakeStateAtStart(running = true, current = listening))
        val paused = WakeState.Paused(WakePause.BatterySaver)
        assertEquals(paused, wakeStateAtStart(running = true, current = paused))
        assertEquals(WakeState.Starting, wakeStateAtStart(running = true, current = WakeState.Off))
    }

    @Test
    fun theLastFiveWakesAreKeptNewestFirst() {
        var log = emptyList<WakeAttempt>()
        for (i in 1..7) log = wakeLogAdd(log, WakeAttempt(at = i * 1_000L, phrase = "Hey Assistant", way = "session", outcome = WakeAttempt.OUT_OPENED))
        assertEquals(listOf(7_000L, 6_000L, 5_000L, 4_000L, 3_000L), log.map { it.at })
        // They live in the phone's settings, and read back as they were written.
        val saved = LembasJson.encodeToString(Prefs.serializer(), Prefs(wakeLog = log))
        assertEquals(log, LembasJson.decodeFromString(Prefs.serializer(), saved).wakeLog)
        assertTrue(Prefs().wakeLog.isEmpty())
    }

    @Test
    fun anAttemptThatFoundTheAssistantNotConnectedLearnsThatItCameLater() {
        val missed = WakeAttempt(at = 5_000, outcome = WakeAttempt.OUT_NOT_CONNECTED, fallback = "notification", assistant = true)
        val earlier = WakeAttempt(at = 1_000, outcome = WakeAttempt.OUT_NOT_CONNECTED)
        val told = wakeLogConnected(listOf(missed, earlier), now = 9_000)
        assertEquals(9_000L, told[0].connectedLater)
        // Only the newest, only once, and never by a connection from before it.
        assertEquals(0L, told[1].connectedLater)
        assertEquals(told, wakeLogConnected(told, now = 12_000))
        assertEquals(listOf(missed), wakeLogConnected(listOf(missed), now = 4_000))
        // An attempt that opened, or an empty record, has nothing to learn.
        val opened = listOf(WakeAttempt(at = 5_000, outcome = WakeAttempt.OUT_OPENED))
        assertEquals(opened, wakeLogConnected(opened, now = 9_000))
        assertTrue(wakeLogConnected(emptyList(), now = 9_000).isEmpty())
    }

    @Test
    fun theAssistantsLinkIsWhatAndroidLastSaid() {
        AssistLink.ready(now = 1_000)
        assertTrue(AssistLink.connected.value)
        assertEquals(1_000L, AssistLink.readyAt)
        AssistLink.shutdown(now = 2_000)
        assertFalse(AssistLink.connected.value)
        assertEquals(2_000L, AssistLink.shutdownAt)
        AssistLink.sheetShown(elapsed = 77)
        assertEquals(77L, AssistLink.shown.value)
    }

    @Test
    fun onlyTheNewestServiceWritesTheState() {
        val old = WakeStatus.begin()
        WakeStatus.set(old, WakeState.Listening("Hey Assistant"))
        assertEquals(WakeState.Listening("Hey Assistant"), WakeStatus.state.value)
        // Turned off and on again: a new instance takes over while the old one is still ending.
        val new = WakeStatus.begin()
        WakeStatus.set(new, WakeState.Starting)
        WakeStatus.set(old, WakeState.Off)
        assertEquals(WakeState.Starting, WakeStatus.state.value)
        WakeStatus.set(new, WakeState.Off)
        assertEquals(WakeState.Off, WakeStatus.state.value)
    }

    @Test
    fun retriesBackOff() {
        assertEquals(listOf(2_000L, 5_000L, 15_000L, 60_000L, 300_000L, 300_000L), (0..5).map(::wakeBackoff))
    }

    @Test
    fun itListensOnlyWhenNothingStandsInTheWay() {
        assertEquals(WakePause.None, wakePause(screenOn = true, screenOnly = true, batterySaver = false, sheetOpen = false, micAllowed = true))
        assertEquals(WakePause.ScreenOff, wakePause(screenOn = false, screenOnly = true, batterySaver = false, sheetOpen = false, micAllowed = true))
        // Told to listen with the screen off too.
        assertEquals(WakePause.None, wakePause(screenOn = false, screenOnly = false, batterySaver = false, sheetOpen = false, micAllowed = true))
        assertEquals(WakePause.BatterySaver, wakePause(screenOn = true, screenOnly = true, batterySaver = true, sheetOpen = false, micAllowed = true))
        // The sheet has the microphone.
        assertEquals(WakePause.Assistant, wakePause(screenOn = true, screenOnly = true, batterySaver = true, sheetOpen = true, micAllowed = true))
        assertEquals(WakePause.NoMicrophone, wakePause(screenOn = true, screenOnly = true, batterySaver = false, sheetOpen = false, micAllowed = false))
    }

    @Test
    fun phrasesAreSpelledInTheModelsPieces() {
        // Every piece of the spelling starts a word with ▁ or continues one; no spaces inside a word.
        for (phrase in WakePhrases.all) {
            assertTrue(phrase.tokens.startsWith("▁"))
            assertEquals(phrase.words.split(' ').size, phrase.tokens.split(' ').count { it.startsWith("▁") })
        }
    }
}
