package eu.llembas.android.wake

import android.content.Context
import eu.llembas.android.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
 *
 * The wake word's own hold is apart from the count: when the word is heard the microphone is
 * the sheet's for a few seconds ([holdFor]), whether or not a sheet then says it is up. It ends
 * by itself on a scope that outlives the service, so a service that is destroyed in those
 * seconds cannot leave the wake word "paused while the assistant is open" for good.
 */
object AssistPresence {
    private val sheets = MutableStateFlow(0)
    private val held = MutableStateFlow(0L)
    private val holds = AtomicLong(0)
    private val _open = MutableStateFlow(0)
    val open: StateFlow<Int> = _open.asStateFlow()

    private val process = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun count() {
        _open.value = sheets.value + if (held.value != 0L) 1 else 0
    }

    fun shown() {
        sheets.update { it + 1 }
        count()
    }

    fun hidden() {
        sheets.update { (it - 1).coerceAtLeast(0) }
        count()
    }

    /** The word was heard: the microphone is the sheet's for [millis], then the hold lets go by itself. */
    fun holdFor(millis: Long, scope: CoroutineScope = process) {
        // Each hold has a number of its own, never used twice, so an old one cannot end a newer one.
        val mine = holds.incrementAndGet()
        held.value = mine
        count()
        scope.launch {
            try {
                delay(millis)
            } finally {
                // Only the newest hold lets go; an older one that ends later changes nothing.
                if (held.compareAndSet(mine, 0L)) count()
            }
        }
    }

    /** No hold is left standing: the wake-word service starts from what the sheets say alone. */
    fun release() {
        held.value = 0L
        count()
    }
}

/** Whether the app has an activity on screen: counted from every activity's start and stop (LembasApp). */
object AppVisibility {
    private val started = AtomicInteger(0)
    val visible: Boolean get() = started.get() > 0

    fun activityStarted() {
        started.incrementAndGet()
    }

    fun activityStopped() {
        started.updateAndGet { (it - 1).coerceAtLeast(0) }
    }
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

/**
 * "Test the wake word" (Settings): until [until] (the phone's elapsed time, in ms) a heard phrase
 * is counted in [heard] instead of opening the assistant. A deadline, not a switch, so a test
 * nobody ends cannot leave the wake word deaf.
 */
data class WakeTrial(val until: Long = 0, val heard: Int = 0)

/**
 * The wake word's state for the whole process: written by the service, read by everything else.
 *
 * A service instance writes with the token [begin] gave it, and only the newest token's writes
 * count: an old instance on its way out (its loop ending after Settings turned the wake word off
 * and on again) cannot write its "off" over the new one's state.
 */
object WakeStatus {
    private val _state = MutableStateFlow<WakeState>(WakeState.Off)
    val state: StateFlow<WakeState> = _state.asStateFlow()

    private val _trial = MutableStateFlow(WakeTrial())
    val trial: StateFlow<WakeTrial> = _trial.asStateFlow()

    private val _retries = MutableStateFlow(0)

    /** Counts up each time somebody asks for a retry now ([retryNow]). */
    val retries: StateFlow<Int> = _retries.asStateFlow()

    private val generation = AtomicInteger(0)

    /** A new writer takes over: its token, which makes every older one's writes count for nothing. */
    fun begin(): Int = generation.incrementAndGet()

    /** [now], if [token] is still the newest writer's. */
    fun set(token: Int, now: WakeState) {
        synchronized(generation) {
            if (token == generation.get()) _state.value = now
        }
    }

    /** [now], said by the app itself, outside any service instance: not started and why, turned off. */
    fun set(now: WakeState) {
        synchronized(generation) { _state.value = now }
    }

    /** Try again now, instead of when the back-off says: the person tapped the failure. */
    fun retryNow() = _retries.update { it + 1 }

    /** Start a test at [now]: the next [TRIAL_MS] of heard phrases are counted, not acted on. */
    fun startTrial(now: Long) {
        _trial.value = WakeTrial(until = now + TRIAL_MS)
    }

    /** The phrase was heard at [now]: true when a test took it, so the assistant stays shut. */
    fun heardInTrial(now: Long): Boolean {
        var taken = false
        _trial.update { trial ->
            taken = now < trial.until
            if (taken) trial.copy(heard = trial.heard + 1) else trial
        }
        return taken
    }

    const val TRIAL_MS = 10_000L
}

/**
 * One [attempt] in plain words, a line each: when and for which phrase; what was tried and how it
 * went (Android's own words where it refused); what was done instead; and, where the assistant
 * could not be used, what Android had said of it -- whether LLeMbas held the role, and when the
 * service was connected and disconnected in this run of the app.
 */
fun wakeAttemptWords(context: Context, attempt: WakeAttempt, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): List<String> {
    fun time(at: Long): String = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm:ss", locale).format(Instant.ofEpochMilli(at).atZone(zone))
    val lines = mutableListOf("${time(attempt.at)} · “${attempt.phrase}”")
    lines += when (attempt.outcome) {
        WakeAttempt.OUT_OPENED -> context.getString(R.string.wake_out_opened)
        WakeAttempt.OUT_TESTED -> context.getString(R.string.wake_out_tested)
        WakeAttempt.OUT_NO_SHEET -> context.getString(R.string.wake_out_no_sheet)
        WakeAttempt.OUT_REFUSED -> context.getString(R.string.wake_out_refused, attempt.detail)
        else -> context.getString(R.string.wake_out_not_connected)
    }
    when (attempt.fallback) {
        WakeWay.Activity.id -> lines += context.getString(R.string.wake_out_activity)
        WakeWay.Notification.id -> lines += context.getString(R.string.wake_out_notification)
    }
    if (attempt.outcome == WakeAttempt.OUT_TESTED) return lines
    if (attempt.sheetMs >= 0) lines += context.getString(R.string.wake_sheet_ms, attempt.sheetMs.toString())
    if (attempt.outcome == WakeAttempt.OUT_OPENED) return lines
    // Why the assistant's own way was not there, from what Android itself said at that moment.
    lines += when {
        !attempt.assistant -> context.getString(R.string.wake_role_not_held)
        !attempt.recognizers -> context.getString(R.string.wake_no_recognizers)
        !attempt.active -> context.getString(R.string.wake_not_active)
        else -> context.getString(R.string.wake_role_held)
    }
    if (attempt.waited) lines += context.getString(R.string.wake_waited)
    if (attempt.showFailed) lines += context.getString(R.string.wake_show_failed)
    lines += if (attempt.readyAt == 0L) context.getString(R.string.wake_link_never) else context.getString(R.string.wake_link_ready, time(attempt.readyAt))
    if (attempt.shutdownAt != 0L) lines += context.getString(R.string.wake_link_shutdown, time(attempt.shutdownAt))
    if (attempt.connectedLater != 0L) lines += context.getString(R.string.wake_link_later, time(attempt.connectedLater))
    // The rest as it was noted, for whoever looks for the cause: names and values, not a sentence.
    lines += listOf(
        "screen=${if (attempt.screenOn) "on" else "off"}",
        "locked=${attempt.locked}",
        "visible=${attempt.visible}",
        "created=${if (attempt.createdAt == 0L) "-" else time(attempt.createdAt)}",
        "destroyed=${if (attempt.destroyedAt == 0L) "-" else time(attempt.destroyedAt)}",
        "prepared=${attempt.prepared}",
    ).joinToString(" ")
    return lines
}

/**
 * What the service's notification says the moment Android starts it (again): what is true --
 * [current], while its loop is [running]; "starting" for a service that has only just begun.
 * Never a phrase by default.
 */
fun wakeStateAtStart(running: Boolean, current: WakeState): WakeState = if (running && current != WakeState.Off) current else WakeState.Starting

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
