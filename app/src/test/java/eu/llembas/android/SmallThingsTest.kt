package eu.llembas.android

import eu.llembas.android.api.LembasJson
import eu.llembas.android.assist.AssistController
import eu.llembas.android.assist.Recorder
import eu.llembas.android.update.Latest
import eu.llembas.android.update.LatestFile
import eu.llembas.android.update.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun aBuildUpdatesItselfToItsOwnKindOnly() {
        val plain = LembasJson.decodeFromString(Latest.serializer(), """{"version":"1.0.0","url":"https://example.org/a.apk","sha256":"aa"}""")
        assertEquals(LatestFile("https://example.org/a.apk", "aa"), plain.fileFor(notificationsBuild = false))
        // A release that names no notifications APK: that build installs nothing, rather than lose its listener.
        assertNull(plain.fileFor(notificationsBuild = true))
        val both = LembasJson.decodeFromString(
            Latest.serializer(),
            """{"version":"1.0.0","url":"https://example.org/a.apk","sha256":"aa","notifications":{"url":"https://example.org/a-notifications.apk","sha256":"bb"}}""",
        )
        assertEquals(LatestFile("https://example.org/a-notifications.apk", "bb"), both.fileFor(notificationsBuild = true))
        assertEquals(LatestFile("https://example.org/a.apk", "aa"), both.fileFor(notificationsBuild = false))
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
