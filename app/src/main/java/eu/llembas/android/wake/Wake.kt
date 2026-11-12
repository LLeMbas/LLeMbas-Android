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

/**
 * A wake word the engine ships: what the person says ([words]), the engine's spelling of it
 * ([tokens], the model's BPE pieces, worked out once with its `bpe.model` -- the phone has no
 * tokenizer), and the id Settings keeps.
 *
 * Only phrases from this list can be chosen: an open-vocabulary spotter hears any phrase it can
 * spell, but how well each one works was not measured, and a free text box would invite one that
 * fires on every other sentence. English only: the model is trained on English speech.
 */
data class WakePhrase(val id: String, val words: String, val tokens: String)

object WakePhrases {
    val all = listOf(
        WakePhrase("hey_lembas", "Hey Lembas", "▁HE Y ▁LE M B AS"),
        WakePhrase("ok_lembas", "OK Lembas", "▁O K ▁LE M B AS"),
        WakePhrase("hello_lembas", "Hello Lembas", "▁HE LL O ▁LE M B AS"),
        WakePhrase("hey_assistant", "Hey Assistant", "▁HE Y ▁AS S IST ANT"),
    )

    val default: WakePhrase get() = all.first()

    fun byId(id: String): WakePhrase = all.firstOrNull { it.id == id } ?: default
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

/** The engine's keywords file for [phrase]: its pieces, then `@` and the id the engine answers with. */
fun wakeKeywords(phrase: WakePhrase): String = "${phrase.tokens} @${phrase.id}\n"

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
