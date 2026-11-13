package eu.llembas.android.wake

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/**
 * What happened the last times the wake word was heard: kept (the last [WAKE_LOG_SIZE], in the
 * phone's settings) and shown in Settings › Wake word, because "it heard me and nothing opened"
 * cannot be put right without knowing which step said no. Nothing of the account is in it: the
 * phrase, clock times, and Android's own words where it refused.
 */
@Serializable
data class WakeAttempt(
    /** When the phrase was heard (wall clock, ms). */
    val at: Long = 0,
    val phrase: String = "",
    /** What was tried first: a [WakeWay]'s id. */
    val way: String = "",
    /** How that went: one of the `OUT_…` words. */
    val outcome: String = "",
    /** Android's own words when it refused: the exception's class and message. */
    val detail: String = "",
    /** What was done instead, when the first way did not open the sheet: `activity`, `notification` or nothing. */
    val fallback: String = "",
    /** Whether Android said LLeMbas holds the assistant role at that moment. */
    val assistant: Boolean = false,
    /** When Android last connected the assistant service in this run of the app, and last disconnected it; 0: never. */
    val readyAt: Long = 0,
    val shutdownAt: Long = 0,
    /** Set afterwards, when the service was not connected and Android connected it later. */
    val connectedLater: Long = 0,
) {
    companion object {
        /** The sheet came up. */
        const val OUT_OPENED = "opened"

        /** Android was asked and did not refuse, but no sheet came up in time. */
        const val OUT_NO_SHEET = "no_sheet"

        /** The assistant service was not connected (`LembasAssistService.current` was null). */
        const val OUT_NOT_CONNECTED = "not_connected"

        /** Android refused, with [detail]. */
        const val OUT_REFUSED = "refused"

        /** A test in Settings took the word: nothing was to open. */
        const val OUT_TESTED = "tested"
    }
}

const val WAKE_LOG_SIZE = 5

/** [log] with [attempt] put first, cut to the last [WAKE_LOG_SIZE]. */
fun wakeLogAdd(log: List<WakeAttempt>, attempt: WakeAttempt): List<WakeAttempt> = (listOf(attempt) + log).take(WAKE_LOG_SIZE)

/**
 * Android connected the assistant service at [now]: the newest attempt that found it not
 * connected, and has not been answered yet, is told so. Anything else is left as it is.
 */
fun wakeLogConnected(log: List<WakeAttempt>, now: Long): List<WakeAttempt> {
    val newest = log.firstOrNull() ?: return log
    if (newest.outcome != WakeAttempt.OUT_NOT_CONNECTED || newest.connectedLater != 0L || now < newest.at) return log
    return listOf(newest.copy(connectedLater = now)) + log.drop(1)
}

/** The ways the assistant may be opened when the word was heard, in the order they are worth trying. */
enum class WakeWay(val id: String) {
    /** The assistant service is connected and the screen is on: it shows its sheet, from anywhere. */
    Session("session"),

    /**
     * The assistant service is connected and the screen is off: a session's window turns no
     * screen on, so the sheet's activity is started through the service instead (Android lets an
     * app whose service the system has bound start one), and the activity turns the screen on.
     */
    AssistantActivity("assistant_activity"),

    /** No connected assistant service, but the app is on screen: the sheet's activity may start. */
    Activity("activity"),

    /**
     * Neither: Android blocks an activity started from the background, and says nothing when it
     * does -- so it is not relied on, and a notification to tap is posted.
     */
    Notification("notification"),

    // A further way (answering by voice without a sheet) would be one more entry here, chosen in
    // [wakeWay] and carried out where the others are.
}

/** The first way to try, from what is true at that moment. */
fun wakeWay(connected: Boolean, screenOn: Boolean, appVisible: Boolean): WakeWay = when {
    connected && screenOn -> WakeWay.Session
    connected -> WakeWay.AssistantActivity
    appVisible -> WakeWay.Activity
    else -> WakeWay.Notification
}

/** What is left when the assistant's own way did not open the sheet. */
fun wakeFallback(appVisible: Boolean): WakeWay = if (appVisible) WakeWay.Activity else WakeWay.Notification

/**
 * The assistant service's life in this run of the app, as Android told it: when it was made,
 * connected (`onReady`), disconnected (`onShutdown`), and when a sheet last came up. Written by
 * the assistant service, the session and the sheet's activity; read when the word is heard.
 */
object AssistLink {
    @Volatile var readyAt = 0L
        private set

    @Volatile var shutdownAt = 0L
        private set

    private val _shown = MutableStateFlow(0L)

    /** When a sheet last came up, on the phone's elapsed clock (ms); 0: not yet. */
    val shown: StateFlow<Long> = _shown.asStateFlow()

    private val _connected = MutableStateFlow(false)

    /** Whether Android has the assistant service connected right now. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    fun ready(now: Long) {
        readyAt = now
        _connected.value = true
    }

    fun shutdown(now: Long) {
        shutdownAt = now
        _connected.value = false
    }

    fun sheetShown(elapsed: Long) {
        _shown.value = elapsed
    }
}
