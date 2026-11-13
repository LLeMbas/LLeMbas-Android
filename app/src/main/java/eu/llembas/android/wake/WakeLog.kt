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
    /**
     * Whether Android itself said LLeMbas's service is its active voice-interaction service
     * (`VoiceInteractionService.isActiveService`). The role can be held without it: Android then
     * opens the assistant as an ordinary screen on a long press, and keeps no connection that
     * could open it from the background.
     */
    val active: Boolean = false,
    /** Whether the phone has voice recognisers at all (`FEATURE_VOICE_RECOGNIZERS`): without it Android runs no voice-interaction service. */
    val recognizers: Boolean = true,
    val screenOn: Boolean = true,
    val locked: Boolean = false,
    /** Whether one of the app's own screens was showing. */
    val visible: Boolean = false,
    /** How long the sheet took to say it was up, in ms; -1 when none came. */
    val sheetMs: Long = -1,
    /** When the assistant service was last made and destroyed in this run of the app; 0: never. */
    val createdAt: Long = 0,
    val destroyedAt: Long = 0,
    /** Whether the wake waited for Android to connect the service (it was active but not connected yet). */
    val waited: Boolean = false,
    /** What Android told the service about this show (API 34+): it prepared one, or it failed. */
    val prepared: Boolean = false,
    val showFailed: Boolean = false,
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

/**
 * The assistant service's life in this run of the app, as Android told it: when it was made,
 * connected (`onReady`), disconnected (`onShutdown`) and destroyed, what Android said of the last
 * show it was asked for, and when a sheet last came up. Written by the assistant service, the
 * session and the sheet's activity; read when the word is heard.
 */
object AssistLink {
    @Volatile var createdAt = 0L
        private set

    @Volatile var destroyedAt = 0L
        private set

    @Volatile var readyAt = 0L
        private set

    @Volatile var shutdownAt = 0L
        private set

    /** On the phone's elapsed clock: when Android last said it prepares to show a session, and when one failed to show. */
    @Volatile var preparedAt = 0L
        private set

    @Volatile var showFailedAt = 0L
        private set

    private val _shown = MutableStateFlow(0L)

    /** When a sheet last came up, on the phone's elapsed clock (ms); 0: not yet. */
    val shown: StateFlow<Long> = _shown.asStateFlow()

    private val _connected = MutableStateFlow(false)

    /** Whether Android has the assistant service connected right now. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    fun created(now: Long) {
        createdAt = now
    }

    fun destroyed(now: Long) {
        destroyedAt = now
    }

    fun ready(now: Long) {
        readyAt = now
        _connected.value = true
    }

    fun shutdown(now: Long) {
        shutdownAt = now
        _connected.value = false
    }

    fun prepared(elapsed: Long) {
        preparedAt = elapsed
    }

    fun showFailed(elapsed: Long) {
        showFailedAt = elapsed
    }

    fun sheetShown(elapsed: Long) {
        _shown.value = elapsed
    }
}

/** What is true of the phone and the assistant at the moment the phrase is heard. */
data class WakeFacts(
    /** The assistant service is connected (`LembasAssistService.current` is there). */
    val connected: Boolean,
    /** Android says the service is its active voice-interaction service. */
    val active: Boolean,
    /** LLeMbas holds the assistant role. */
    val role: Boolean,
    val recognizers: Boolean = true,
    val screenOn: Boolean = true,
    val locked: Boolean = false,
    val visible: Boolean = false,
)

/**
 * Opening the assistant when the phrase was heard, apart from Android so that the order of what
 * is tried -- and what is written down about it -- can be tested.
 *
 * 1. The assistant's own way, where its service is connected ([wakeWay]). When Android says the
 *    service is active but has not connected it yet, a moment is waited for the connection.
 * 2. "Opened" only once a sheet says it is up ([sheetCame]).
 * 3. Otherwise the sheet's activity -- relied on only where Android will start it: the service is
 *    connected (a bound service lets the whole app start activities) or the app is on screen. A
 *    session that was only slow is given a second wait first, so there are never two sheets.
 * 4. Otherwise a notification to tap.
 *
 * [open] returns what happened, for the record.
 */
class WakeOpener(
    private val facts: () -> WakeFacts,
    /** Wait up to the given ms for Android to connect the service; whether it did. */
    private val awaitConnected: suspend (Long) -> Boolean,
    /** Ask the connected service to show its sheet; throws what Android throws. */
    private val showSession: () -> Unit,
    /** Start the sheet's activity; throws what Android throws. */
    private val startActivity: () -> Unit,
    /** Wait up to the given ms for a sheet to say it is up; how long it took, or null. */
    private val sheetCame: suspend (Long) -> Long?,
    private val notify: (WakeAttempt) -> Unit,
    /** What Android said of the show since it was asked: prepared, failed. */
    private val showSignals: () -> Pair<Boolean, Boolean> = { false to false },
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun open(phrase: String, link: WakeAttempt = WakeAttempt()): WakeAttempt {
        var seen = facts()
        var waited = false
        if (!seen.connected && seen.active) {
            // Android's own assistant, only not connected at this instant: it may be on its way.
            waited = true
            if (awaitConnected(CONNECT_WAIT_MS)) seen = facts().copy(connected = true)
        }
        val way = wakeWay(seen.connected, seen.screenOn, seen.visible)
        var attempt = link.copy(
            at = now(),
            phrase = phrase,
            way = way.id,
            assistant = seen.role,
            active = seen.active,
            recognizers = seen.recognizers,
            screenOn = seen.screenOn,
            locked = seen.locked,
            visible = seen.visible,
            waited = waited,
        )
        if (way == WakeWay.Session || way == WakeWay.AssistantActivity) {
            val refused = runCatching { if (way == WakeWay.Session) showSession() else startActivity() }.exceptionOrNull()
            val took = if (refused == null) sheetCame(SHEET_WAIT_MS) else null
            val (prepared, failed) = showSignals()
            attempt = attempt.copy(prepared = prepared, showFailed = failed)
            if (took != null) return attempt.copy(outcome = WakeAttempt.OUT_OPENED, sheetMs = took)
            attempt = if (refused != null) {
                attempt.copy(outcome = WakeAttempt.OUT_REFUSED, detail = describe(refused))
            } else {
                attempt.copy(outcome = WakeAttempt.OUT_NO_SHEET)
            }
            // The activity was the way that just did not come up: starting it a second time is no fallback.
            if (way == WakeWay.AssistantActivity && refused == null) return notified(attempt)
            // A session Android did not refuse may only be slow: a second wait, before anything
            // else is put on screen beside it.
            if (refused == null) {
                sheetCame(SHEET_WAIT_MS)?.let { late -> return attempt.copy(outcome = WakeAttempt.OUT_OPENED, sheetMs = SHEET_WAIT_MS + late) }
            }
        } else {
            attempt = attempt.copy(outcome = WakeAttempt.OUT_NOT_CONNECTED)
        }
        // The sheet's activity. Android blocks one started from the background without a word,
        // so it counts only where it may start -- and only once it says it is up.
        val started = runCatching { startActivity() }.isSuccess
        if (started && (seen.connected || seen.visible)) {
            sheetCame(SHEET_WAIT_MS)?.let { took -> return attempt.copy(fallback = WakeWay.Activity.id, sheetMs = took) }
        }
        return notified(attempt)
    }

    private fun notified(attempt: WakeAttempt): WakeAttempt {
        val told = attempt.copy(fallback = WakeWay.Notification.id)
        notify(told)
        return told
    }

    private fun describe(e: Throwable): String = listOfNotNull(e.javaClass.simpleName, e.message?.takeIf { it.isNotBlank() }).joinToString(": ").take(200)

    companion object {
        /** How long a sheet has to say it is up before the way that was tried counts as not having opened it. */
        const val SHEET_WAIT_MS = 2_500L

        /** How long Android is given to connect a service it says is active. */
        const val CONNECT_WAIT_MS = 1_000L

        /** The longest an attempt can take: the connection, three waits for a sheet, and a margin. The microphone is held that long. */
        const val ATTEMPT_MAX_MS = CONNECT_WAIT_MS + 3 * SHEET_WAIT_MS + 1_500L
    }
}
