package eu.llembas.android

import eu.llembas.android.wake.WakePhrases
import eu.llembas.android.wake.WakeSensitivity
import eu.llembas.android.wake.WakeWords
import eu.llembas.android.wake.checkKeywords
import eu.llembas.android.wake.wakeKeywords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The generated table (`wake/WakeWords.kt`, by `scripts/gen-wake-phrases.py`) against the model
 * that will hear it: every piece of every phrase must be in the model's `tokens.txt` -- the
 * engine ends the app on one that is not. The table is the model's, so the test reads the very
 * file the build puts into the APK (`scripts/fetch-wakeword.py` fetches it before Gradle).
 */
class WakeWordsTest {
    private val tokens: String by lazy {
        val file = File("src/full/assets/wakeword/tokens.txt")
        check(file.isFile) { "no ${file.path}: run scripts/fetch-wakeword.py before the tests" }
        file.readText()
    }

    @Test
    fun everyPieceOfEveryPhraseIsInTheModel() {
        val known = tokens.lineSequence().map { it.substringBeforeLast(' ') }.toSet()
        assertTrue(known.size > 400)
        for (word in WakeWords.greetings + WakeWords.names) {
            for (piece in word.pieces.split(' ')) assertTrue("${word.words}: the model has no piece \"$piece\"", piece in known)
        }
        // And as the engine gets them: the whole keywords line of every phrase, at every sensitivity.
        for (phrase in WakePhrases.all) {
            for (sensitivity in WakeSensitivity.entries) checkKeywords(wakeKeywords(phrase, sensitivity), tokens)
        }
    }

    @Test
    fun theTableIsWhatTheSettingsOffer() {
        assertEquals(listOf("Hey", "OK", "Hello"), WakeWords.greetings.map { it.words })
        assertEquals(listOf("Assistant", "Computer", "Google"), WakeWords.names.take(3).map { it.words })
        // Ids are what Settings saves: lower case, one word, unique.
        val ids = (WakeWords.greetings + WakeWords.names).map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { id -> id.isNotEmpty() && id.all { it in 'a'..'z' } })
        // Written as the model's speech was: capitals only, each word starting with the word mark.
        for (word in WakeWords.greetings + WakeWords.names) {
            assertTrue(word.pieces.startsWith("▁"))
            assertTrue(word.pieces.all { it == '▁' || it == ' ' || it in 'A'..'Z' })
        }
    }
}
