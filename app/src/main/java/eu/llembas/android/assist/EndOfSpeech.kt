package eu.llembas.android.assist

/**
 * When to stop listening, for the instance's speech-to-text (which takes a whole recording and
 * finds no end of speech itself). Fed the microphone's level once a tick ([tick]); apart from
 * Android, so it is tested with sequences of levels.
 *
 * Nothing here is an absolute loudness: a room's own noise differs from phone to phone by more
 * than a voice differs from a room. The **noise floor** is measured from the first sound on and
 * followed all the time -- the quietest level of the last two seconds, taken at once when it
 * falls and slowly, in calm moments, when it rises: the dips between words keep it down while
 * somebody speaks, and a steady noise (a fan, a car) becomes the floor within a moment. Speech and quiet are
 * levels relative to it:
 *
 * - **speech** begins when the level is well above the floor in [ONSET_TICKS] of the last
 *   [ONSET_WINDOW] ticks -- a voice rises and falls, and one click or knock is not speech;
 * - listening may begin in the middle of a sentence (the wake word's shape: the person goes
 *   straight on), and a voice that never dips far must not become "the room": the floor rises
 *   only while the sound is calm, and sound that stays above the floor without being steady is
 *   speech;
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
    private var aboveRun = 0

    /** The floor is a guess: listening began in the middle of sound louder than any room is taken to be. */
    private var guessed = false
    private var overGuess = 0
    private var quietFor = 0L
    private var words = ""
    private var sameWordsFor = 0L

    /** The level from which a sound is speech beginning, and the level under which it is a pause. */
    private fun speechFrom(): Float = maxOf(floor * SPEECH_RATIO, floor + SPEECH_MARGIN)

    private fun pauseUnder(): Float = maxOf(floor * PAUSE_RATIO, floor + PAUSE_MARGIN)

    /** One tick: the microphone's [level] (0..1, RMS) and the [heard] words written down so far. */
    fun tick(level: Float, heard: String = ""): Verdict {
        waited += tickMs
        // Exact zero before any sound is a microphone that has not given anything yet (or gives
        // nothing at all): it says nothing about the room. After sound it is silence -- a
        // headset that gates its noise gives exactly that between sentences -- and so a pause.
        if (level > 0f) {
            follow(level)
        } else if (floor > 0f) {
            judge(loud = false, above = false)
        }
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
        // Steady over the whole window: a noise. Calm in the last half second: no voice now.
        val steady = recent.size >= CALM_TICKS && recent.max() <= lowest * STEADY_RATIO
        val last = recent.takeLast(CALM_TICKS)
        val calm = last.size >= CALM_TICKS && last.max() <= last.min() * STEADY_RATIO
        // The first sound is the room, unless somebody is already speaking -- then a guess,
        // until a dip or a calm moment shows the room. After it: down at once; up slowly, and
        // only while the sound is calm, so a voice does not lift the floor under itself.
        when {
            floor == 0f -> {
                floor = minOf(level, START_MAX)
                guessed = level > START_MAX
            }

            lowest < floor -> {
                floor = lowest
                guessed = false
            }

            calm -> {
                floor += (lowest - floor) * RISE
                guessed = false
            }
        }
        floor = floor.coerceAtLeast(MIN_FLOOR)
        judge(loud = level >= speechFrom(), above = level >= pauseUnder())
        // Sound that stays above the floor and is not steady is a voice: one that never dips
        // far, or -- while the floor is only a guess -- one that began before the listening did.
        overGuess = if (guessed && level > floor) overGuess + 1 else 0
        if ((aboveRun >= SUSTAINED_TICKS || overGuess >= SUSTAINED_TICKS) && !steady) spoke = true
    }

    /** One tick's sound, judged: [loud] enough to be speech beginning, or at least [above] a pause. */
    private fun judge(loud: Boolean, above: Boolean) {
        this.loud.addLast(loud)
        if (this.loud.size > ONSET_WINDOW) this.loud.removeFirst()
        if (this.loud.count { it } >= ONSET_TICKS) spoke = true
        if (above) {
            stirred++
            aboveRun++
            quietFor = 0
        } else {
            aboveRun = 0
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
        private const val STIRRED_TICKS = 4

        // Steady, calm: the loudest within half again of the quietest -- over the window, over
        // the last half second. Sustained: above the floor for a second without a break.
        private const val CALM_TICKS = 5
        private const val STEADY_RATIO = 1.5f
        private const val SUSTAINED_TICKS = 10

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
 * it has shown any words ([anyWords]), for how long they have stayed the same ([sameForMs]), and
 * how long ago it last gave a sign of somebody speaking ([silentForMs]: its "speech began", a
 * loud level, new words -- not every recogniser shows words while one speaks).
 * A recogniser normally ends by itself; some never call back, and the sheet would listen for good.
 * No words and nothing heard for a while gives up; when somebody did speak before that quiet, it
 * is first asked for what it has.
 */
fun phoneWatch(waitedMs: Long, anyWords: Boolean, sameForMs: Long, silentForMs: Long = waitedMs): PhoneWatch = when {
    waitedMs >= EndOfSpeech.LONGEST_MS + PHONE_GRACE_MS -> if (anyWords) PhoneWatch.Take else PhoneWatch.GiveUp
    waitedMs >= EndOfSpeech.LONGEST_MS -> PhoneWatch.Finish
    !anyWords && silentForMs >= PHONE_NOTHING_SAID_MS + PHONE_GRACE_MS -> PhoneWatch.GiveUp
    !anyWords && silentForMs >= PHONE_NOTHING_SAID_MS -> if (silentForMs < waitedMs) PhoneWatch.Finish else PhoneWatch.GiveUp
    anyWords && sameForMs >= EndOfSpeech.SAME_WORDS_MS + PHONE_GRACE_MS -> PhoneWatch.Take
    anyWords && sameForMs >= EndOfSpeech.SAME_WORDS_MS && silentForMs >= EndOfSpeech.PAUSE_MS -> PhoneWatch.Finish
    else -> PhoneWatch.Wait
}

/** Longer than the recogniser's own "no speech" (about five seconds on most): it gets to say so first. */
const val PHONE_NOTHING_SAID_MS = 8_000L

/** How long a recogniser that was asked to stop has to answer. */
const val PHONE_GRACE_MS = 2_500L
