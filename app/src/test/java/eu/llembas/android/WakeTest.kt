package eu.llembas.android

import eu.llembas.android.wake.WakePause
import eu.llembas.android.wake.WakePhrases
import eu.llembas.android.wake.wakePause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeTest {
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
