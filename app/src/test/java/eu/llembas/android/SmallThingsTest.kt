package eu.llembas.android

import eu.llembas.android.assist.AssistController
import eu.llembas.android.assist.Recorder
import eu.llembas.android.update.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmallThingsTest {
    @Test
    fun versionsCompareAsNumbers() {
        assertTrue(Updater.newer("0.10.0", "0.9.9"))
        assertTrue(Updater.newer("1.0.0", "0.99.0"))
        assertFalse(Updater.newer("0.1.0", "0.1.0"))
        assertFalse(Updater.newer("0.1.0", "0.2.0"))
    }

    @Test
    fun aVoiceReadsNoMarkup() {
        val said = AssistController.plain("**Bake** it.\n\n```sh\nbake\n```\nSee [the recipe](https://example.org).")
        assertEquals("Bake it. See the recipe.", said)
    }

    @Test
    fun aRecordingIsAValidWavFile() {
        val wav = Recorder.wav(ByteArray(3200))
        assertEquals(44 + 3200, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("data", String(wav, 36, 4))
    }
}
