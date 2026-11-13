package eu.llembas.android.assist

/**
 * When to stop listening, for the instance's speech-to-text (which takes a whole recording and
 * finds no end of speech itself). Fed the microphone's level once a tick ([tick]); apart from
 * Android, so it is tested with sequences of levels.
 *
 * Nothing here is an absolute loudness: a room's own noise differs from phone to phone by more
 * than a voice differs from a room. The **noise floor** is measured from the first sound on and
 * followed all the time -- the quietest level of the last two seconds, taken at once when it
 * falls and slowly when it rises, so the dips between words keep it down while somebody speaks
 * and a steady noise (a fan, a car) becomes the floor within a moment. Speech and quiet are
 * levels relative to it:
 *
 * - **speech** begins when the level is well above the floor in [ONSET_TICKS] of the last
 *   [ONSET_WINDOW] ticks -- a voice rises and falls, and one click or knock is not speech;
 * - once somebody spoke, a **pause** of [PAUSE_MS] back near the floor ends it ([Verdict.Said]);
 * - when **nothing is said**, it gives up after [NOTHING_SAID_MS] ([Verdict.Nothing]);
 * - the words written down so far are a second ear: the same words for [SAME_WORDS_MS] end it,
 *   and words count as speech for a quiet voice -- but only when the microphone heard at least
 *   something above the floor, because a speech-to-text model asked to write down silence
 *   invents a sentence;
 * - and never longer than [LONGEST_MS].
 */
class EndOfSpeech(private val tickMs: Long = TICK_MS) {
    enum class Verdict { Listening, Said, Nothing }

    /** The room's own level, as followed so far; 0 until the microphone gave any sound. */
    var floor = 0f
        private set

    /** Whether somebody spoke. */
    var spoke = false
        private set

    /**
     * Whether the microphone heard anything above the room at all: only then is the recording
     * worth writing down while it runs.
     */
    val heardSomething: Boolean get() = spoke || stirred >= STIRRED_TICKS

    private val recent = ArrayDeque<Float>()
    private var waited = 0L
    private val loud = ArrayDeque<Boolean>()
    private var stirred = 0
    private var quietFor = 0L
    private var words = ""
    private var sameWordsFor = 0L

    /** The level from which a sound is speech beginning, and the level under which it is a pause. */
    private fun speechFrom(): Float = maxOf(floor * SPEECH_RATIO, floor + SPEECH_MARGIN)

    private fun pauseUnder(): Float = maxOf(floor * PAUSE_RATIO, floor + PAUSE_MARGIN)

    /** One tick: the microphone's [level] (0..1, RMS) and the [heard] words written down so far. */
    fun tick(level: Float, heard: String = ""): Verdict {
        waited += tickMs
        // Exact zero is a microphone that has not given anything yet (or gives nothing at all):
        // it says nothing about the room.
        if (level > 0f) follow(level)
        if (heard != words) {
            words = heard
            sameWordsFor = 0
        } else if (heard.isNotBlank()) {
            sameWordsFor += tickMs
        }
        if (heard.isNotBlank() && stirred >= STIRRED_TICKS) spoke = true
        return when {
            spoke && quietFor >= PAUSE_MS -> Verdict.Said
            spoke && sameWordsFor >= SAME_WORDS_MS -> Verdict.Said
            !spoke && waited >= NOTHING_SAID_MS -> Verdict.Nothing
            waited >= LONGEST_MS -> if (spoke) Verdict.Said else Verdict.Nothing
            else -> Verdict.Listening
        }
    }

    private fun follow(level: Float) {
        recent.addLast(level)
        if (recent.size > WINDOW_TICKS) recent.removeFirst()
        val lowest = recent.min()
        // The first sound is the room, unless somebody is already speaking -- then a guess that
        // the next dip corrects. After it: down at once, up slowly.
        floor = when {
            floor == 0f -> minOf(level, START_MAX)
            lowest < floor -> lowest
            else -> floor + (lowest - floor) * RISE
        }.coerceAtLeast(MIN_FLOOR)
        loud.addLast(level >= speechFrom())
        if (loud.size > ONSET_WINDOW) loud.removeFirst()
        if (loud.count { it } >= ONSET_TICKS) spoke = true
        if (level >= pauseUnder()) {
            stirred++
            quietFor = 0
        } else {
            quietFor += tickMs
        }
    }

    companion object {
        const val TICK_MS = 100L

        /** How long the room's quietest level is looked for: two seconds. */
        private const val WINDOW_TICKS = 20

        /** How fast the floor follows a room that got louder: a twentieth of the way each tick. */
        private const val RISE = 0.05f

        /** No microphone is quieter than this; and the most the first sound may claim as "the room". */
        private const val MIN_FLOOR = 0.0008f
        private const val START_MAX = 0.02f

        // Speech begins at a little over twice the floor (and at least a small step above it, for
        // a silent room); a pause is back within 1.6 times of it. Between the two nothing changes.
        private const val SPEECH_RATIO = 2.2f
        private const val SPEECH_MARGIN = 0.004f
        private const val PAUSE_RATIO = 1.6f
        private const val PAUSE_MARGIN = 0.002f
        private const val ONSET_TICKS = 3
        private const val ONSET_WINDOW = 8
        private const val STIRRED_TICKS = 2

        const val PAUSE_MS = 1_500L
        const val SAME_WORDS_MS = 4_500L
        const val NOTHING_SAID_MS = 5_000L
        const val LONGEST_MS = 60_000L
    }
}

/** What to do about the phone's own recogniser, which finds the end of speech itself -- usually. */
enum class PhoneWatch {
    Wait,

    /** Ask it to stop and give what it has. */
    Finish,

    /** It did not answer the asking either: take the words it showed. */
    Take,

    /** Nothing was said, and it has not said so itself. */
    GiveUp,
}

/**
 * The watch kept over the phone's recogniser, from how long it has listened ([waitedMs]), whether
 * it has shown any words ([anyWords]) and for how long they have stayed the same ([sameForMs]).
 * A recogniser normally ends by itself; some never call back, and the sheet would listen for good.
 */
fun phoneWatch(waitedMs: Long, anyWords: Boolean, sameForMs: Long): PhoneWatch = when {
    !anyWords && waitedMs >= PHONE_NOTHING_SAID_MS -> PhoneWatch.GiveUp
    anyWords && sameForMs >= EndOfSpeech.SAME_WORDS_MS + PHONE_GRACE_MS -> PhoneWatch.Take
    anyWords && sameForMs >= EndOfSpeech.SAME_WORDS_MS -> PhoneWatch.Finish
    waitedMs >= EndOfSpeech.LONGEST_MS + PHONE_GRACE_MS -> if (anyWords) PhoneWatch.Take else PhoneWatch.GiveUp
    waitedMs >= EndOfSpeech.LONGEST_MS -> PhoneWatch.Finish
    else -> PhoneWatch.Wait
}

/** Longer than the recogniser's own "no speech" (about five seconds on most): it gets to say so first. */
const val PHONE_NOTHING_SAID_MS = 8_000L

/** How long a recogniser that was asked to stop has to answer. */
const val PHONE_GRACE_MS = 2_500L
