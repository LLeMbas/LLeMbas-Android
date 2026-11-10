package eu.llembas.android.wake

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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

/** Why listening for the wake word is paused, or [None]: what the notification and Settings say. */
enum class WakePause { None, ScreenOff, BatterySaver, Assistant, NoMicrophone }

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
