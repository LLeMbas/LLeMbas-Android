package eu.llembas.android

import eu.llembas.android.wake.WakeDetector
import eu.llembas.android.wake.WakeError
import eu.llembas.android.wake.WakeFailure
import eu.llembas.android.wake.WakeListener
import eu.llembas.android.wake.WakeMicrophone
import eu.llembas.android.wake.WakePause
import eu.llembas.android.wake.WakePhrases
import eu.llembas.android.wake.WakePlan
import eu.llembas.android.wake.WakeState
import eu.llembas.android.wake.wakeKeywords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * The listening loop without a phone: an engine that does not load, a microphone that does not
 * start or stops, silence, a pause and a change of phrase -- each must end in a state somebody
 * can read, and the engine must never be released under a loop that still feeds it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WakeListenerTest {
    /** Gives [script] one chunk at a time (true: sound, false: exact silence), then [after]: more sound, or an error code. */
    private class Mic(private val script: List<Boolean> = emptyList(), private val after: Int = 0, private val pauseMs: Long = 0) : WakeMicrophone {
        @Volatile var closed = false
        private var reads = 0

        override fun read(into: ShortArray): Int {
            if (pauseMs > 0) Thread.sleep(pauseMs)
            val sound = script.getOrNull(reads++) ?: if (after < 0) return after else true
            into.fill(if (sound) 100 else 0)
            return into.size
        }

        override fun close() {
            closed = true
        }
    }

    /** Hears the word on its [hearOn]-th chunk; notes a release that came while it was at work, or work after a release. */
    private class Detector(private val hearOn: Int = Int.MAX_VALUE, private val workMs: Long = 0) : WakeDetector {
        @Volatile var released = false

        @Volatile var misused = false

        @Volatile private var busy = false
        private var accepts = 0

        override fun accept(samples: FloatArray): Boolean {
            if (released) misused = true
            busy = true
            if (workMs > 0) Thread.sleep(workMs)
            busy = false
            return ++accepts % hearOn == 0
        }

        override fun release() {
            if (busy) misused = true
            released = true
        }
    }

    private val assistant = WakePhrases.byId("hey_assistant")
    private val plan = WakePlan(assistant, wakeKeywords(assistant), WakePause.None)
    private val states: MutableList<WakeState> = Collections.synchronizedList(mutableListOf())

    private fun TestScope.listener(engine: (String) -> WakeDetector, microphone: () -> WakeMicrophone, heard: () -> Unit = {}) = WakeListener(
        say = { states += it },
        engine = engine,
        microphone = microphone,
        heard = { heard() },
        work = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun anEngineThatDoesNotLoadIsShownAndTriedAgain() = runTest {
        var tries = 0
        var heard = 0
        val detector = Detector(hearOn = 3)
        val listener = listener(
            engine = { if (++tries < 3) error("no such file") else detector },
            microphone = { Mic() },
            heard = { heard++ },
        )
        val job = launch { listener.follow(MutableStateFlow(plan)) }
        runCurrent()
        assertEquals(listOf(WakeState.Starting, WakeState.Failed(WakeFailure.Engine, "IllegalStateException: no such file")), states.toList())
        advanceTimeBy(2_001) // the first wait
        assertEquals(2, tries)
        advanceTimeBy(5_001) // the second, longer
        assertEquals(3, tries)
        assertEquals(WakeState.Listening("Hey Assistant"), states.last())
        assertEquals(1, heard)
        job.cancelAndJoin()
        assertTrue(detector.released)
    }

    @Test
    fun aBuildWithoutAnEngineSaysSoOnce() = runTest {
        var tries = 0
        val listener = listener(engine = {
            tries++
            throw WakeError(WakeFailure.NoEngine, message = "this build has no wake-word engine")
        }, microphone = { Mic() })
        val job = launch { listener.follow(MutableStateFlow(plan)) }
        advanceTimeBy(3_600_000)
        assertEquals(1, tries)
        assertEquals(WakeState.Failed(WakeFailure.NoEngine, "this build has no wake-word engine"), states.last())
        job.cancelAndJoin()
    }

    @Test
    fun aMicrophoneThatDoesNotStartIsAFailureNotASilentReturn() = runTest {
        var opens = 0
        val listener = listener(engine = { Detector() }, microphone = {
            opens++
            throw IllegalStateException("recording did not start")
        })
        val job = launch { listener.follow(MutableStateFlow(plan)) }
        runCurrent()
        assertEquals(WakeState.Failed(WakeFailure.Microphone, "IllegalStateException: recording did not start"), states.last())
        advanceTimeBy(2_001)
        assertEquals(2, opens)
        job.cancelAndJoin()
    }

    @Test
    fun aMicrophoneThatStopsIsAFailureAndIsClosed() = runTest {
        val mic = Mic(script = listOf(true, true), after = -3)
        val listener = listener(engine = { Detector() }, microphone = { mic })
        val job = launch { listener.follow(MutableStateFlow(plan)) }
        runCurrent()
        assertEquals(WakeState.Failed(WakeFailure.Microphone, "the microphone stopped (error -3)"), states.last())
        assertTrue(mic.closed)
        job.cancelAndJoin()
    }

    @Test
    fun exactSilenceForSecondsIsSaidAndSoIsSoundComingBack() = runTest {
        // Six seconds of zeros (a tenth of a second a chunk), then sound; the word on the 70th chunk.
        val listener = listener(engine = { Detector(hearOn = 70) }, microphone = { Mic(script = List(60) { false }) })
        val job = launch { listener.follow(MutableStateFlow(plan)) }
        runCurrent()
        val listening = WakeState.Listening("Hey Assistant")
        assertEquals(listOf(WakeState.Starting, listening, WakeState.Paused(WakePause.Silent), listening), states.toList())
        job.cancelAndJoin()
    }

    /** With real threads: wait until the listener has said [state]. */
    private suspend fun said(now: MutableStateFlow<WakeState>, state: WakeState) = withTimeout(10_000) { now.first { it == state } }

    @Test
    fun aPauseLetsGoOfTheMicrophoneAndKeepsTheEngine() = runBlocking {
        val now = MutableStateFlow<WakeState>(WakeState.Off)
        val mics = Collections.synchronizedList(mutableListOf<Mic>())
        val detectors = Collections.synchronizedList(mutableListOf<Detector>())
        val listener = WakeListener(
            say = { now.value = it },
            engine = { Detector().also { detectors += it } },
            microphone = { Mic(pauseMs = 2).also { mics += it } },
            heard = {},
        )
        val plans = MutableStateFlow(plan)
        val job = launch(Dispatchers.Default) { listener.follow(plans) }
        said(now, WakeState.Listening("Hey Assistant"))
        plans.value = plan.copy(pause = WakePause.ScreenOff)
        said(now, WakeState.Paused(WakePause.ScreenOff))
        assertTrue(mics.single().closed)
        assertFalse(detectors.single().released)
        plans.value = plan
        said(now, WakeState.Listening("Hey Assistant"))
        assertEquals(1, detectors.size)
        assertEquals(2, mics.size)
        job.cancelAndJoin()
        assertTrue(detectors.single().released)
        assertTrue(mics.all { it.closed })
        assertFalse(detectors.single().misused)
    }

    @Test
    fun aNewPhraseWaitsForTheOldLoopBeforeItsEngineIsReleased() = runBlocking {
        val now = MutableStateFlow<WakeState>(WakeState.Off)
        val detectors = Collections.synchronizedList(mutableListOf<Detector>())
        val listener = WakeListener(
            say = { now.value = it },
            // Slow at its work, so a release that did not wait would land in the middle of it.
            engine = { Detector(workMs = 30).also { detectors += it } },
            microphone = { Mic() },
            heard = {},
        )
        val plans = MutableStateFlow(plan)
        val job = launch(Dispatchers.Default) { listener.follow(plans) }
        val other = WakePhrases.all.first { it.id != assistant.id }
        repeat(5) { round ->
            val phrase = if (round % 2 == 0) other else assistant
            said(now, WakeState.Listening(if (round % 2 == 0) assistant.words else other.words))
            plans.value = WakePlan(phrase, wakeKeywords(phrase), WakePause.None)
        }
        said(now, WakeState.Listening(other.words))
        job.cancelAndJoin()
        assertEquals(6, detectors.size)
        assertTrue(detectors.all { it.released })
        assertFalse(detectors.any { it.misused })
    }
}
