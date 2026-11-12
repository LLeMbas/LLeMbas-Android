package eu.llembas.android

import eu.llembas.android.wake.WakeError
import eu.llembas.android.wake.WakeFailure
import eu.llembas.android.wake.WakePause
import eu.llembas.android.wake.WakePhrases
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
    fun theKeywordsFileNamesThePhraseForTheEngine() {
        val phrase = WakePhrases.byId("hey_assistant")
        assertTrue(wakeKeywords(phrase).startsWith("▁HE Y ▁AS S IST ANT "))
        assertTrue(wakeKeywords(phrase).endsWith("@hey_assistant\n"))
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
        assertEquals("hey_lembas", WakePhrases.byId("nonsense").id)
        // Every piece of the spelling starts a word with ▁ or continues one; no spaces inside a word.
        for (phrase in WakePhrases.all) {
            assertTrue(phrase.tokens.startsWith("▁"))
            assertEquals(phrase.words.split(' ').size, phrase.tokens.split(' ').count { it.startsWith("▁") })
        }
    }
}
