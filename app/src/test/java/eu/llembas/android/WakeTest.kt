package eu.llembas.android

import eu.llembas.android.api.LembasJson
import eu.llembas.android.auth.Prefs
import eu.llembas.android.wake.WakeError
import eu.llembas.android.wake.WakeFailure
import eu.llembas.android.wake.WakePause
import eu.llembas.android.wake.WakePhrases
import eu.llembas.android.wake.WakeSensitivity
import eu.llembas.android.wake.WakeStatus
import eu.llembas.android.wake.WakeWords
import eu.llembas.android.wake.checkKeywords
import eu.llembas.android.wake.unpackWakeModel
import eu.llembas.android.wake.wakeBackoff
import eu.llembas.android.wake.wakeKeywords
import eu.llembas.android.wake.wakePause
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
