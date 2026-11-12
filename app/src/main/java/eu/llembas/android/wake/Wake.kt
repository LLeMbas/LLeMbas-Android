package eu.llembas.android.wake

import android.content.Context
import eu.llembas.android.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale

/** One word of a wake phrase: its [id], how it is written for the person, and its spelling in the model's [pieces]. */
data class WakeWord(val id: String, val words: String, val pieces: String)

/**
 * A wake phrase: a greeting and a name ("Hey Assistant"). What the person says ([words]), the
 * engine's spelling of it ([tokens], the model's BPE pieces -- generated with its `bpe.model` by
 * `scripts/gen-wake-phrases.py`, since the phone has no tokenizer), and the [id] Settings keeps.
 *
 * Only words from [WakeWords] can be chosen: an open-vocabulary spotter hears any phrase it can
 * spell, but one it was never trained to hear (a made-up name) has to be repeated until it lands,
 * and a free text box would invite one that fires on every other sentence. English only: the
 * model is trained on English speech.
 */
data class WakePhrase(val greeting: WakeWord, val name: WakeWord) {
    val id: String get() = "${greeting.id}_${name.id}"
    val words: String get() = "${greeting.words} ${name.words}"
    val tokens: String get() = "${greeting.pieces} ${name.pieces}"
}

object WakePhrases {
    /** "Hey Assistant". */
    val default: WakePhrase = WakePhrase(WakeWords.greetings.first { it.id == "hey" }, WakeWords.names.first { it.id == "assistant" })

    val all: List<WakePhrase> = WakeWords.greetings.flatMap { greeting -> WakeWords.names.map { WakePhrase(greeting, it) } }

    /**
     * The phrase Settings saved as [id] (`greeting_name`). An id that is no longer a phrase --
     * the "Lembas" ones of the first wake word, which the model could not hear -- is the default.
     */
    fun byId(id: String): WakePhrase = all.firstOrNull { it.id == id } ?: default
}

/**
 * How easily the phrase is taken as heard: Settings › Wake word › Sensitivity.
 *
 * The engine has two numbers per keyword (sherpa-onnx's keyword-spotting documentation): a
 * boosting score that keeps the phrase's path alive in the search ([boost], larger is easier),
 * and a trigger threshold, the least probability the heard pieces must reach ([threshold], 0 to
 * 1, lower is easier). Normal is the engine's own default threshold with the boost its
 * documentation uses for a two-word phrase; Low takes that documentation's stricter example
 * (0.35), High goes as far below the default as Low goes above it. Starting values: nobody has
 * measured them on a phone yet.
 */
enum class WakeSensitivity(val id: String, val boost: Float, val threshold: Float) {
    Low("low", 1.0f, 0.35f),
    Normal("normal", 1.5f, 0.25f),
    High("high", 2.0f, 0.15f),
    ;

    companion object {
        fun byId(id: String): WakeSensitivity = entries.firstOrNull { it.id == id } ?: Normal
    }
}

/**
 * The engine, as the service sees it: 16 kHz mono samples in, the phrase out when it was heard.
 * Everything happens on the phone; [accept] neither records nor sends anything.
 */
interface WakeDetector {
    /** Feed [samples] (-1..1); true when the wake word ended in them. */
    fun accept(samples: FloatArray): Boolean

    fun release()
}

/** The microphone, as the listener sees it: 16 kHz mono samples, already recording when it is made. */
interface WakeMicrophone {
    /** Fill [into]; how many samples were read, or a negative number when the microphone failed. */
    fun read(into: ShortArray): Int

    fun close()
}

/**
 * Whether the assistant's sheet is up -- the wake-word service lets go of the microphone while
 * it is, and takes it back once the sheet is gone. Counted, since the session and the activity
 * can overlap for a moment when one hands over to the other.
 */
object AssistPresence {
    private val _open = MutableStateFlow(0)
    val open: StateFlow<Int> = _open.asStateFlow()

    fun shown() = _open.update { it + 1 }

    fun hidden() = _open.update { (it - 1).coerceAtLeast(0) }
}

/**
 * Why listening for the wake word is paused, or [None]: what the notification and Settings say.
 * [Silent] is the one the listener finds out itself: the microphone is open but gives no sound at
 * all, which is what Android hands a background app while a call or another recorder has it, or
 * while the phone's microphone switch is off.
 */
enum class WakePause { None, ScreenOff, BatterySaver, Assistant, NoMicrophone, Silent }

/**
 * When the service listens, from what the phone is doing: never while the sheet is up (it has the
 * microphone), never in battery saver (the person asked the phone to spare power), and -- when
 * Settings says so, the default -- only while the screen is on.
 */
fun wakePause(screenOn: Boolean, screenOnly: Boolean, batterySaver: Boolean, sheetOpen: Boolean, micAllowed: Boolean): WakePause = when {
    !micAllowed -> WakePause.NoMicrophone
    sheetOpen -> WakePause.Assistant
    batterySaver -> WakePause.BatterySaver
    screenOnly && !screenOn -> WakePause.ScreenOff
    else -> WakePause.None
}

/** What went wrong: this build has no engine, the model's files, the engine itself, or the microphone. */
enum class WakeFailure { NoEngine, Model, Engine, Microphone }

/** A failure with its kind, so the notification and Settings can say it in plain words. */
class WakeError(val why: WakeFailure, cause: Throwable? = null, message: String? = cause?.message) : Exception(message, cause) {
    /** The technical half, for Settings and the log: the exception's name and what it said. */
    val detail: String
        get() = listOfNotNull(cause?.javaClass?.simpleName, message?.takeIf { it.isNotBlank() }).joinToString(": ").take(DETAIL_MAX)

    private companion object {
        const val DETAIL_MAX = 200
    }
}

/**
 * What the wake word is doing, the one truth the service, its notification and Settings all read
 * ([WakeStatus.state]). [Failed] carries the plain reason and the technical [Failed.detail];
 * nothing here is ever a guess from the settings alone.
 */
sealed interface WakeState {
    /** Not running: turned off, or the phone stopped the service. */
    data object Off : WakeState

    /** Running, loading the engine. */
    data object Starting : WakeState

    /** The microphone is open and the engine hears it, for these words. */
    data class Listening(val phrase: String) : WakeState

    data class Paused(val why: WakePause) : WakeState

    data class Failed(val why: WakeFailure, val detail: String = "") : WakeState
}

/** The wake word's state for the whole process: written by the service, read by everything else. */
object WakeStatus {
    private val _state = MutableStateFlow<WakeState>(WakeState.Off)
    val state: StateFlow<WakeState> = _state.asStateFlow()

    fun set(now: WakeState) {
        _state.value = now
    }
}

/**
 * [state] in plain words: the notification's title and Settings' line. A failure names what
 * failed and that it is tried again; it never claims to listen while it does not.
 */
fun wakeTitle(context: Context, state: WakeState): String = when (state) {
    WakeState.Off -> context.getString(R.string.wake_off)

    WakeState.Starting -> context.getString(R.string.wake_starting)

    is WakeState.Listening -> context.getString(R.string.wake_listening_for, state.phrase)

    is WakeState.Paused -> context.getString(
        when (state.why) {
            WakePause.None -> R.string.wake_starting
            WakePause.ScreenOff -> R.string.wake_paused_screen
            WakePause.BatterySaver -> R.string.wake_paused_battery
            WakePause.Assistant -> R.string.wake_paused_assistant
            WakePause.NoMicrophone -> R.string.wake_paused_microphone
            WakePause.Silent -> R.string.wake_paused_silent
        },
    )

    is WakeState.Failed -> context.getString(
        when (state.why) {
            WakeFailure.NoEngine -> R.string.wake_failed_no_engine
            WakeFailure.Model -> R.string.wake_failed_model
            WakeFailure.Engine -> R.string.wake_failed_engine
            WakeFailure.Microphone -> R.string.wake_failed_microphone
        },
    )
}

/** The notification's second line: the promise while it listens, else nothing more to say. */
fun wakeLine(context: Context, state: WakeState): String? = when (state) {
    is WakeState.Listening -> context.getString(R.string.wake_notification_line)
    is WakeState.Failed -> if (state.why == WakeFailure.NoEngine) null else context.getString(R.string.wake_failed_line)
    else -> null
}

/**
 * The engine's keywords file for [phrase]: its pieces, `:` the boosting score, `#` the trigger
 * threshold, then `@` and the id the engine answers with (sherpa-onnx's syntax; no space after
 * `:` or `#`). A phrase of fewer than five pieces is short enough to turn up inside other speech,
 * so it has to be a little surer ([SHORT_PHRASE_STRICTER]).
 */
fun wakeKeywords(phrase: WakePhrase, sensitivity: WakeSensitivity = WakeSensitivity.Normal): String {
    val short = phrase.tokens.split(' ').size < SHORT_PHRASE_PIECES
    val threshold = sensitivity.threshold + if (short) SHORT_PHRASE_STRICTER else 0f
    // Locale.ROOT: a decimal comma would not be a number to the engine.
    return String.format(Locale.ROOT, "%s :%.1f #%.2f @%s\n", phrase.tokens, sensitivity.boost, threshold, phrase.id)
}

private const val SHORT_PHRASE_PIECES = 5
private const val SHORT_PHRASE_STRICTER = 0.05f

/**
 * Every piece of [keywords] must be in the model's token table ([tokens], `tokens.txt`: a piece
 * and its number per line): the engine ends the whole app on a piece it does not know, so it is
 * never handed one.
 */
fun checkKeywords(keywords: String, tokens: String) {
    val known = tokens.lineSequence().map { it.substringBeforeLast(' ') }.toSet()
    for (line in keywords.lineSequence().filter { it.isNotBlank() }) {
        val unknown = line.split(' ').filter { it.isNotEmpty() && it[0] !in ":#@" }.firstOrNull { it !in known }
        if (unknown != null) throw WakeError(WakeFailure.Engine, message = "the model has no piece \"$unknown\"")
    }
}

/**
 * The model's files, out of the APK's assets into [dir], where the engine reads them as files.
 * Copied by stream ([open] is `AssetManager.open`), so it works however the APK stores them, and
 * keyed by the assets' `version` (a hash of the pinned model, written by
 * `scripts/fetch-wakeword.py`): a new model replaces the old one, and a copy that was cut short
 * has no marker and is made again.
 */
fun unpackWakeModel(open: (String) -> InputStream, dir: File, files: List<String>) {
    val version = open("version").use { it.readBytes().decodeToString().trim() }
    val marker = File(dir, "version")
    if (marker.isFile && marker.readText() == version && files.all { File(dir, it).length() > 0 }) return
    if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot make ${dir.name}")
    marker.delete()
    for (name in files) {
        val part = File(dir, "$name.part")
        open(name).use { input -> part.outputStream().use { input.copyTo(it) } }
        if (part.length() == 0L) throw IOException("$name is empty")
        val target = File(dir, name)
        target.delete()
        if (!part.renameTo(target)) throw IOException("cannot write $name")
    }
    marker.writeText(version)
}

/** How long to wait before the next try after the [attempt]-th failure in a row: 2 s, 5 s, 15 s, 1 min, then every 5 min. */
fun wakeBackoff(attempt: Int): Long = listOf(2_000L, 5_000L, 15_000L, 60_000L).getOrElse(attempt) { 300_000L }
