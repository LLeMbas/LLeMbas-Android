package eu.llembas.android

import eu.llembas.android.assist.EndOfSpeech
import eu.llembas.android.assist.EndOfSpeech.Verdict
import eu.llembas.android.assist.PHONE_GRACE_MS
import eu.llembas.android.assist.PHONE_NOTHING_SAID_MS
import eu.llembas.android.assist.PhoneWatch
import eu.llembas.android.assist.phoneWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the sheet stops listening, from sequences of microphone levels (one each 100 ms). */
class EndOfSpeechTest {
    /** How it ended and after how many milliseconds. */
    private data class Ended(val verdict: Verdict, val ms: Long)

    /**
     * Feed [start], then [rest] over and over, until a verdict; [words] are what the instance
     * has written down by each tick.
     */
    private fun listen(start: List<Float>, rest: List<Float>, words: (Int) -> String = { "" }): Ended {
        val ear = EndOfSpeech()
        var tick = 0
        while (tick < 5_000) {
            val level = if (tick < start.size) start[tick] else rest[(tick - start.size) % rest.size]
            val verdict = ear.tick(level, words(tick))
            tick++
            if (verdict != Verdict.Listening) return Ended(verdict, tick * EndOfSpeech.TICK_MS)
        }
        error("it never stopped listening")
    }

    /** A steady noise around [level]: no two moments quite the same. */
    private fun noise(level: Float): List<Float> = listOf(1.0f, 0.9f, 1.1f, 0.95f, 1.15f, 0.85f, 1.05f).map { it * level }

    /** Two seconds of a voice whose loudest moments are [peak]: it rises and falls between words. */
    private fun voice(peak: Float): List<Float> {
        val second = listOf(0.6f, 1.0f, 0.8f, 0.3f, 0.9f, 1.0f, 0.5f, 0.7f, 1.0f, 0.4f)
        return (second + second).map { it * peak }
    }

    private fun still(level: Float, ticks: Int): List<Float> = List(ticks) { level }

    /** Ended as said, a pause after [start]'s last sound: not before it was over, and not long after. */
    private fun assertEndsAfter(start: List<Float>, ended: Ended, late: Long = 400) {
        val over = start.size * EndOfSpeech.TICK_MS
        assertEquals(Verdict.Said, ended.verdict)
        assertTrue("ended at ${ended.ms}, speech over at $over", ended.ms > over + EndOfSpeech.PAUSE_MS - 400 && ended.ms <= over + EndOfSpeech.PAUSE_MS + late)
    }

    /** A voice that never dips far: its quietest moments are six tenths of its loudest. One second. */
    private fun steadyVoice(peak: Float): List<Float> = listOf(1.0f, 0.7f, 0.9f, 0.6f, 1.0f, 0.8f, 0.65f, 0.95f, 0.75f, 0.6f).map { it * peak }

    @Test
    fun aSilentRoomWithNothingSaidGivesUpSoon() {
        assertEquals(Ended(Verdict.Nothing, EndOfSpeech.NOTHING_SAID_MS), listen(emptyList(), still(0.001f, 1)))
    }

    @Test
    fun aNoisyRoomWithNothingSaidGivesUpAsSoon() {
        // Louder than any fixed "this is a voice" level could allow for: the noise is the floor.
        assertEquals(Ended(Verdict.Nothing, EndOfSpeech.NOTHING_SAID_MS), listen(emptyList(), noise(0.03f)))
        assertEquals(Ended(Verdict.Nothing, EndOfSpeech.NOTHING_SAID_MS), listen(emptyList(), noise(0.012f)))
    }

    @Test
    fun aMicrophoneThatGivesNothingAtAllGivesUpToo() {
        assertEquals(Ended(Verdict.Nothing, EndOfSpeech.NOTHING_SAID_MS), listen(emptyList(), still(0f, 1)))
    }

    @Test
    fun speechThenAPauseEndsAfterThePause() {
        val start = still(0.001f, 5) + voice(0.06f)
        assertEndsAfter(start, listen(start, still(0.001f, 1)))
    }

    @Test
    fun speechInANoisyRoomEndsAfterThePauseToo() {
        val start = noise(0.03f) + voice(0.12f)
        // The room is louder than the first guess allows for: found a moment after the voice ends.
        assertEndsAfter(start, listen(start, noise(0.03f)), late = 800)
    }

    @Test
    fun aRoomThatIsNeverQuietByAFixedLevelStillEnds() {
        // The room sits at 0.015: above what used to count as quiet, so it listened to the limit.
        val start = still(0.015f, 5) + voice(0.06f)
        assertEndsAfter(start, listen(start, still(0.015f, 1)))
    }

    @Test
    fun speakingAtOnceIsHeard() {
        // No quiet moment first to measure the room by: the first dip between words gives it.
        for (peak in listOf(0.05f, 0.08f, 0.3f)) {
            val start = voice(peak)
            assertEndsAfter(start, listen(start, still(0.002f, 1)))
        }
    }

    @Test
    fun silenceFromAHeadsetThatGatesItsNoiseIsAPause() {
        // After speech the microphone gives exact zeros: that is quiet, not "no microphone".
        val start = still(0.001f, 5) + voice(0.06f)
        assertEndsAfter(start, listen(start, still(0f, 1)))
        val atOnce = voice(0.06f)
        assertEndsAfter(atOnce, listen(atOnce, still(0f, 1)))
    }

    @Test
    fun listeningThatBeginsInTheMiddleOfASentenceHearsIt() {
        // The wake word's shape: the person goes straight on, and for eight seconds the voice
        // never dips far. It must not become "the room", nor end as "nothing said" at five.
        for (peak in listOf(0.035f, 0.05f, 0.1f)) {
            var start = emptyList<Float>()
            repeat(8) { start = start + steadyVoice(peak) }
            assertEndsAfter(start, listen(start, still(0.002f, 1)))
            assertEndsAfter(start, listen(start, still(0.004f, 1)))
        }
    }

    @Test
    fun aShortWordIsSpeech() {
        val start = still(0.001f, 10) + listOf(0.03f, 0.05f, 0.04f, 0.01f)
        assertEndsAfter(start, listen(start, still(0.001f, 1)))
    }

    @Test
    fun aClickIsNotSpeech() {
        assertEquals(Ended(Verdict.Nothing, EndOfSpeech.NOTHING_SAID_MS), listen(still(0.001f, 5) + listOf(0.3f), still(0.001f, 1)))
        val twice = still(0.001f, 5) + listOf(0.3f, 0.3f) + still(0.001f, 20) + listOf(0.3f, 0.3f)
        assertEquals(Ended(Verdict.Nothing, EndOfSpeech.NOTHING_SAID_MS), listen(twice, still(0.001f, 1)))
    }

    @Test
    fun aLongQuestionIsNotCutShort() {
        var start = still(0.001f, 5)
        repeat(10) { start = start + voice(0.06f) }
        assertEndsAfter(start, listen(start, still(0.001f, 1)))
    }

    @Test
    fun soundThatNeverPausesEndsAtTheLimit() {
        val ended = listen(emptyList(), listOf(0.01f, 0.2f, 0.01f, 0.2f, 0.2f, 0.2f, 0.01f))
        assertEquals(Ended(Verdict.Said, EndOfSpeech.LONGEST_MS), ended)
    }

    @Test
    fun theSameWordsForAWhileEndIt() {
        // Music that never pauses, and the words stopped coming: that is the end.
        val ended = listen(still(0.001f, 5), listOf(0.01f, 0.2f, 0.01f, 0.2f, 0.2f, 0.2f, 0.01f)) { tick -> if (tick > 20) "play something" else "" }
        assertEquals(Verdict.Said, ended.verdict)
        assertTrue(ended.ms < 8_000)
    }

    @Test
    fun wordsInventedFromSilenceAreNotSpeech() {
        // A speech-to-text model handed silence writes "Thank you." -- in a silent room and in a
        // noisy one the microphone heard nothing above the room, so nothing was said.
        val invented = { tick: Int -> if (tick > 12) "Thank you." else "" }
        assertEquals(Ended(Verdict.Nothing, EndOfSpeech.NOTHING_SAID_MS), listen(emptyList(), still(0.001f, 1), invented))
        assertEquals(Ended(Verdict.Nothing, EndOfSpeech.NOTHING_SAID_MS), listen(emptyList(), noise(0.03f), invented))
    }

    @Test
    fun aQuietVoiceCountsOnceItsWordsCome() {
        // Too soft to be sure of by loudness alone, but above the room, and the instance wrote words down.
        val start = still(0.001f, 5) + still(0.0035f, 20)
        assertEndsAfter(start, listen(start, still(0.001f, 1)) { tick -> if (tick > 15) "what time is it" else "" })
    }

    @Test
    fun theFloorFollowsTheRoom() {
        val ear = EndOfSpeech()
        assertFalse(ear.heardSomething)
        repeat(30) { ear.tick(noise(0.03f)[it % 7]) }
        // Near the quietest of the noise, not at a fixed number.
        assertTrue(ear.floor > 0.02f && ear.floor < 0.03f)
        assertFalse(ear.heardSomething)
        assertFalse(ear.spoke)
        voice(0.12f).forEach { ear.tick(it) }
        assertTrue(ear.spoke)
        assertTrue(ear.heardSomething)
    }

    @Test
    fun thePhonesRecogniserIsWatched() {
        // It normally ends by itself; the watch is for one that does not.
        assertEquals(PhoneWatch.Wait, phoneWatch(3_000, anyWords = false, sameForMs = 3_000))
        assertEquals(PhoneWatch.GiveUp, phoneWatch(PHONE_NOTHING_SAID_MS, anyWords = false, sameForMs = PHONE_NOTHING_SAID_MS))
        // Words still coming: left alone.
        assertEquals(PhoneWatch.Wait, phoneWatch(20_000, anyWords = true, sameForMs = 400))
        // The same words for a while: asked to stop, then taken as they are.
        assertEquals(PhoneWatch.Finish, phoneWatch(20_000, anyWords = true, sameForMs = EndOfSpeech.SAME_WORDS_MS))
        assertEquals(PhoneWatch.Take, phoneWatch(20_000, anyWords = true, sameForMs = EndOfSpeech.SAME_WORDS_MS + PHONE_GRACE_MS))
        // And never past the limit.
        assertEquals(PhoneWatch.Finish, phoneWatch(EndOfSpeech.LONGEST_MS, anyWords = true, sameForMs = 0))
        assertEquals(PhoneWatch.Take, phoneWatch(EndOfSpeech.LONGEST_MS + PHONE_GRACE_MS, anyWords = true, sameForMs = 0))
    }

    @Test
    fun aRecogniserThatShowsNoWordsWhileOneSpeaksIsNotGivenUpOn() {
        // No partial results, but it said speech began and its level is loud: somebody speaks.
        assertEquals(PhoneWatch.Wait, phoneWatch(12_000, anyWords = false, sameForMs = 12_000, silentForMs = 300))
        // Quiet for a good while after that: asked for what it has, then given up on.
        assertEquals(PhoneWatch.Finish, phoneWatch(20_000, anyWords = false, sameForMs = 20_000, silentForMs = PHONE_NOTHING_SAID_MS))
        assertEquals(PhoneWatch.GiveUp, phoneWatch(20_000, anyWords = false, sameForMs = 20_000, silentForMs = PHONE_NOTHING_SAID_MS + PHONE_GRACE_MS))
        // Words that stopped changing while the level is still loud: not cut off yet.
        assertEquals(PhoneWatch.Wait, phoneWatch(20_000, anyWords = true, sameForMs = EndOfSpeech.SAME_WORDS_MS, silentForMs = 200))
    }
}
