package eu.llembas.android.wake

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What the service wants right now: this phrase, spelled for the engine as [keywords], unless [pause]d. */
data class WakePlan(val phrase: WakePhrase, val keywords: String, val pause: WakePause)

/**
 * The listening itself, apart from Android so it can be tested: follows the plans, keeps one
 * engine and one microphone, and says every change of state through [say].
 *
 * Nothing fails silently. An engine that does not load, a microphone that does not start or
 * stops giving samples: each is a [WakeState.Failed] with its reason, logged, and tried again
 * after [backoff]. And the engine is never released while a loop may still be feeding it: a new
 * plan first cancels the old loop and waits for it to end ([cancelAndJoin]), then the engine is
 * swapped, on the one coroutine that owns it.
 */
class WakeListener(
    private val say: (WakeState) -> Unit,
    private val engine: (keywords: String) -> WakeDetector,
    private val microphone: () -> WakeMicrophone,
    private val heard: suspend (WakePhrase) -> Unit,
    private val work: CoroutineDispatcher = Dispatchers.IO,
    private val backoff: (attempt: Int) -> Long = ::wakeBackoff,
    private val log: (String, Throwable) -> Unit = { _, _ -> },
) {
    @Volatile private var detector: WakeDetector? = null

    @Volatile private var detectorFor: String? = null

    /** Listen as [plans] say, until the flow ends or this is cancelled; the engine is released on the way out. */
    suspend fun follow(plans: Flow<WakePlan>) = coroutineScope {
        var hearing: Job? = null
        try {
            plans.distinctUntilChanged().collect { plan ->
                hearing?.cancelAndJoin()
                hearing = null
                if (plan.pause != WakePause.None) {
                    say(WakeState.Paused(plan.pause))
                } else {
                    hearing = launch { hear(plan) }
                }
            }
        } finally {
            withContext(NonCancellable) {
                hearing?.cancelAndJoin()
                release()
            }
        }
    }

    private fun release() {
        runCatching { detector?.release() }
        detector = null
        detectorFor = null
    }

    private val nudges = MutableStateFlow(0)

    /** How many samples the last stretch of listening heard before it ended. */
    @Volatile private var listened = 0L

    /** Try again now rather than when the back-off says: somebody asked. The count of failures starts over. */
    fun retryNow() = nudges.update { it + 1 }

    /** Listen for [plan]'s phrase until cancelled: after the word, again; after a failure, again later. */
    private suspend fun hear(plan: WakePlan) {
        var failures = 0
        while (true) {
            try {
                listened = 0
                listen(plan)
                failures = 0
                heard(plan.phrase)
                // The sheet takes the microphone now (a new plan cancels this); otherwise listen again.
                delay(HEARD_REST_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: WakeError) {
                log("wake word: ${e.why}: ${e.detail}", e)
                say(WakeState.Failed(e.why, e.detail))
                // A build without an engine does not grow one: nothing to try again.
                if (e.why == WakeFailure.NoEngine) return
                // It had been listening well for a while: this is a new trouble, not the old one again.
                if (listened >= STEADY_SAMPLES) failures = 0
                val seen = nudges.value
                val asked = withTimeoutOrNull(backoff(failures++)) { nudges.first { it != seen } } != null
                if (asked) failures = 0
            }
        }
    }

    /** One stretch of listening: returns when the word was heard, throws a [WakeError] when something failed. */
    private suspend fun listen(plan: WakePlan) {
        val spotter = engineFor(plan)
        try {
            withContext(work) {
                // Opened and closed on this one thread, so a cancellation cannot leave it open.
                val mic = try {
                    microphone()
                } catch (e: Exception) {
                    throw WakeError(WakeFailure.Microphone, e)
                }
                try {
                    say(WakeState.Listening(plan.phrase.words))
                    val chunk = ShortArray(CHUNK)
                    var empty = 0
                    var silent = 0
                    while (true) {
                        ensureActive()
                        val read = mic.read(chunk)
                        if (read < 0) throw WakeError(WakeFailure.Microphone, message = "the microphone stopped (error $read)")
                        if (read == 0) {
                            if (++empty > EMPTY_READS) throw WakeError(WakeFailure.Microphone, message = "the microphone gives no samples")
                            continue
                        }
                        empty = 0
                        listened += read
                        val samples = FloatArray(read)
                        var sound = false
                        for (i in 0 until read) {
                            if (chunk[i] != 0.toShort()) sound = true
                            samples[i] = chunk[i] / 32768f
                        }
                        // Exact silence for seconds is not a quiet room: Android gives a background
                        // app zeros while another app has the microphone or its switch is off.
                        if (sound) {
                            if (silent >= SILENT_SAMPLES) say(WakeState.Listening(plan.phrase.words))
                            silent = 0
                        } else if (silent < SILENT_SAMPLES) {
                            silent += read
                            if (silent >= SILENT_SAMPLES) say(WakeState.Paused(WakePause.Silent))
                        }
                        if (spotter.accept(samples)) break
                    }
                } finally {
                    runCatching { mic.close() }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: WakeError) {
            throw e
        } catch (e: Throwable) {
            // The engine threw while hearing: it is not trusted again, a new one is made.
            release()
            throw WakeError(WakeFailure.Engine, e)
        }
    }

    /** The engine for [plan]'s keywords: the one there is, or a new one once the old is released. */
    private suspend fun engineFor(plan: WakePlan): WakeDetector {
        detector?.takeIf { detectorFor == plan.keywords }?.let { return it }
        release()
        say(WakeState.Starting)
        return try {
            // Kept the moment it exists, so a cancellation while it loads cannot lose it unreleased.
            withContext(work) {
                engine(plan.keywords).also {
                    detector = it
                    detectorFor = plan.keywords
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: WakeError) {
            throw e
        } catch (e: Throwable) {
            // A missing native library is an Error, not an Exception: it is a failure to show all the same.
            throw WakeError(WakeFailure.Engine, e)
        }
    }

    companion object {
        const val RATE = 16_000

        /** A tenth of a second at a time. */
        private const val CHUNK = RATE / 10
        private const val EMPTY_READS = 50
        private const val SILENT_SAMPLES = RATE * 5
        private const val HEARD_REST_MS = 1_500L

        /** Half a minute of sound taken in: the microphone and the engine work. */
        private const val STEADY_SAMPLES = RATE * 30L
    }
}
