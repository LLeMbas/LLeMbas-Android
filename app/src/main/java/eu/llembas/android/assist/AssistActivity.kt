package eu.llembas.android.assist

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import eu.llembas.android.AppLanguage
import eu.llembas.android.auth.Prefs
import eu.llembas.android.lembas
import eu.llembas.android.ui.theme.LembasTheme
import eu.llembas.android.wake.AssistLink
import eu.llembas.android.wake.AssistPresence
import eu.llembas.android.wake.WakeWordService
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/** What a way into the sheet asks for. */
enum class Opening {
    Sheet,
    Listen,
    Continue,
    ;

    companion object {
        /**
         * A headset's voice button sends `VOICE_COMMAND` (or, locked, `VOICE_SEARCH_HANDS_FREE`):
         * the person means to speak, so the sheet opens listening whatever Settings says.
         */
        fun of(intent: Intent): Opening = when {
            intent.getBooleanExtra(AssistActivity.EXTRA_LISTEN, false) -> Listen
            intent.action == AssistActivity.ACTION_CONTINUE -> Continue
            intent.action in LISTENING -> Listen
            else -> Sheet
        }

        private val LISTENING = setOf(
            AssistActivity.ACTION_LISTEN,
            Intent.ACTION_VOICE_COMMAND,
            "android.speech.action.VOICE_SEARCH_HANDS_FREE",
        )
    }
}

/** How the sheet starts, given whether the phone is locked. */
enum class LockedStart {
    /** Not locked: do what the way in asked for. */
    Go,

    /** Locked, and the way in means to speak: stay over the lock screen and listen; what is heard waits for the unlock. */
    Listen,

    /** Locked, with a question handed over: it is shown and waits for the unlock. */
    Hold,

    /** Locked, anything else: ask Android to unlock first; the sheet goes if that is turned down. */
    Unlock,
}

/**
 * What a sheet opened while the phone is [locked] does. The wake word with the screen off, and a
 * headset's button, open it listening ([Opening.Listen]) and nobody is there to unlock: asking
 * Android to unlock first -- and closing when that is cancelled, which is what happens when
 * nobody answers the lock screen -- showed the sheet for a moment and took it away again. So it
 * stays and listens, and only *sending* waits for the unlock. Without the microphone's
 * permission there is nothing to listen with, and its prompt needs the phone unlocked anyway.
 */
fun lockedStart(locked: Boolean, opening: Opening, question: Boolean, micAllowed: Boolean): LockedStart = when {
    !locked -> LockedStart.Go
    question -> LockedStart.Hold
    opening == Opening.Listen && micAllowed -> LockedStart.Listen
    else -> LockedStart.Unlock
}

/**
 * The assistant as an activity: what the Quick Settings tile, the "Ask" shortcut and a plain
 * `ACTION_ASSIST` open, and where the voice-interaction session hands over what needs an
 * activity -- a question asked over the lock screen, or the microphone's permission prompt
 * ([EXTRA_LISTEN]). The same sheet as the session's, in a see-through window over the app
 * underneath. Shown over the lock screen, but it asks Android to unlock before it sends
 * anything -- and shows nothing from the account until then. Opened there to listen (the wake
 * word, a headset's button) it stays up and listens; the question it heard is shown and waits
 * for the unlock ([lockedStart]).
 */
class AssistActivity : ComponentActivity() {
    private lateinit var controller: AssistController
    private lateinit var keyguard: KeyguardManager
    private var started = false
    private var byWake = false

    /** Whether the phone is locked: the sheet shows nothing from the account until it is not. */
    private val locked = mutableStateOf(false)
    private val mic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) controller.listen()
    }

    /**
     * The phone was unlocked by the person themselves (a fingerprint while the sheet is up): the
     * question that waited is sent. And the screen went off over the lock screen: the sheet goes,
     * so that it is not what the phone shows when it is next picked up.
     */
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_USER_PRESENT -> unlockedNow()
                Intent.ACTION_SCREEN_OFF -> if (locked.value) finish()
            }
        }
    }

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        controller = AssistController(this, lifecycleScope)
        keyguard = getSystemService(KeyguardManager::class.java)
        locked.value = keyguard.isKeyguardLocked
        ContextCompat.registerReceiver(
            this,
            screen,
            IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // A question waits for the unlock: Android is asked for it at once (a face or a
        // fingerprint is then enough), and the sheet stays if nobody answers.
        lifecycleScope.launch {
            controller.state.map { it.waiting }.distinctUntilChanged().collect { waiting ->
                if (waiting.isNotBlank() && locked.value) askToUnlock()
            }
        }
        // The lock screen turns the display off after a few seconds: not in the middle of a question.
        lifecycleScope.launch {
            controller.state.map { it.listening || it.busy }.distinctUntilChanged().collect { on ->
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        // Opened by the wake word: the screen comes on with it (a spoken phrase is not a touch,
        // so nothing else has woken the phone).
        woken(intent)
        take(intent)
        setContent {
            val prefs by lembas.instances.prefs.collectAsState(initial = Prefs())
            LembasTheme(prefs.theme) {
                AssistPanel(
                    controller = controller,
                    locked = locked.value,
                    onUnlockAndAsk = { text ->
                        askToUnlock(askWaiting = text.isBlank()) {
                            if (text.isBlank()) controller.opened(locked = false) else controller.ask(text)
                        }
                    },
                    onOpenApp = {
                        startActivity(controller.appIntent())
                        finish()
                    },
                    onNeedMic = { mic.launch(Manifest.permission.RECORD_AUDIO) },
                    onDismiss = { finish() },
                    onUnlock = { askToUnlock() },
                )
            }
        }
    }

    /** Opened again while it is up -- the headset's button pressed twice, the tile again, the wake word. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        woken(intent)
        // The same sheet answers again: it is up, and whoever asked for it is told so.
        if (started) AssistLink.sheetShown(SystemClock.elapsedRealtime())
        take(intent)
    }

    /**
     * Opened by the wake word: the screen comes on with it (a spoken phrase is not a touch, so
     * nothing else has woken the phone), and the sheet is remembered as the wake word's, so that
     * the assistant's own sheet can take its place if that one comes after all.
     */
    private fun woken(intent: Intent?) {
        byWake = intent?.getBooleanExtra(EXTRA_WAKE, false) == true
        if (byWake) setTurnScreenOn(true)
    }

    /**
     * On screen: only now is the sheet "up" -- for the wake word's pause (which must end when the
     * sheet is left with Home, not only when it is destroyed) and for whoever waits to hear that
     * it opened.
     */
    override fun onStart() {
        super.onStart()
        started = true
        // The phone may have locked, or been unlocked, while the sheet was out of sight.
        if (keyguard.isKeyguardLocked) locked.value = true else unlockedNow()
        open = WeakReference(this)
        AssistPresence.shown()
        AssistLink.sheetShown(SystemClock.elapsedRealtime())
        // "Heard you. Tap to ask." has been answered, however the sheet was opened.
        getSystemService(NotificationManager::class.java).cancel(WakeWordService.HEARD_ID)
    }

    override fun onStop() {
        started = false
        if (open?.get() === this) open = null
        AssistPresence.hidden()
        super.onStop()
    }

    /**
     * What the way in asked for: a question handed over by the session, listening (the headset's
     * voice button, the widget's microphone, the Speak shortcut, the wake word), the last
     * assistant chat (the widget, the shortcut), or just the sheet. Over the lock screen:
     * [lockedStart].
     */
    private fun take(intent: Intent) {
        val opening = Opening.of(intent)
        val waiting = intent.getStringExtra(EXTRA_QUESTION).orEmpty()
        val go = {
            when {
                waiting.isNotBlank() -> controller.ask(waiting, spoken = intent.getBooleanExtra(EXTRA_SPOKEN, false))
                opening == Opening.Continue -> controller.continueWhenReady()
                opening == Opening.Listen && !controller.micAllowed() -> mic.launch(Manifest.permission.RECORD_AUDIO)
                opening == Opening.Listen -> controller.opened(locked = false, listen = true)
                else -> controller.opened(locked = false)
            }
        }
        val isLocked = keyguard.isKeyguardLocked
        if (isLocked) locked.value = true
        when (lockedStart(isLocked, opening, waiting.isNotBlank(), controller.micAllowed())) {
            LockedStart.Go -> go()

            LockedStart.Listen -> controller.listenOverLock()

            // Shown, and Android asked to unlock (the collector in onCreate): it stays if nobody does.
            LockedStart.Hold -> controller.hold(waiting, spoken = intent.getBooleanExtra(EXTRA_SPOKEN, false))

            LockedStart.Unlock -> keyguard.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        unlockedNow()
                        go()
                    }

                    override fun onDismissCancelled() = finish()

                    override fun onDismissError() = finish()
                },
            )
        }
    }

    /**
     * Ask Android to unlock. When it does, the question that waited is sent (unless [askWaiting]
     * says another takes its place) and [then] runs. When it does not -- nobody there, or Back
     * on the lock screen -- nothing happens: the sheet stays as it is, with the way to try again.
     */
    private fun askToUnlock(askWaiting: Boolean = true, then: () -> Unit = {}) {
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    unlockedNow(askWaiting)
                    then()
                }
            },
        )
    }

    /** The phone is unlocked now, however that came about: once. */
    private fun unlockedNow(askWaiting: Boolean = true) {
        if (!locked.value) return
        locked.value = false
        controller.unlocked(ask = askWaiting)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screen) }
        controller.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_QUESTION = "eu.llembas.android.QUESTION"

        /** With [EXTRA_QUESTION]: it was spoken, and is answered as a spoken one. */
        const val EXTRA_SPOKEN = "eu.llembas.android.SPOKEN"

        /** Handed over by the session: ask for the microphone, then listen. */
        const val EXTRA_LISTEN = "eu.llembas.android.LISTEN"

        /** Open listening: the widget's microphone and the Speak shortcut. */
        const val ACTION_LISTEN = "eu.llembas.android.action.LISTEN"

        /** Open at the last chat the assistant made: the widget and the shortcut. */
        const val ACTION_CONTINUE = "eu.llembas.android.action.CONTINUE"

        /** The sheet, listening. */
        fun listening(context: Context): Intent = Intent(context, AssistActivity::class.java).setAction(ACTION_LISTEN)

        /** The sheet that is on screen now, if this activity is it. */
        @Volatile
        private var open: WeakReference<AssistActivity>? = null

        /** The assistant's own sheet came up: a sheet the wake word had opened in its place goes. */
        fun closeWoken() {
            open?.get()?.takeIf { it.byWake }?.finish()
        }

        /** On the intent the wake word opens the sheet with: turn the screen on. */
        const val EXTRA_WAKE = "eu.llembas.android.WAKE"

        /** The sheet, listening, as the wake word opens it. */
        fun woken(context: Context): Intent = listening(context).putExtra(EXTRA_WAKE, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** The sheet, at the last assistant chat. */
        fun continuing(context: Context): Intent = Intent(context, AssistActivity::class.java).setAction(ACTION_CONTINUE)
    }
}
