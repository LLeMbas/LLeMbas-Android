package eu.llembas.android.assist

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import eu.llembas.android.AppLanguage
import eu.llembas.android.auth.Prefs
import eu.llembas.android.lembas
import eu.llembas.android.ui.theme.LembasTheme
import eu.llembas.android.wake.AssistLink
import eu.llembas.android.wake.AssistPresence
import eu.llembas.android.wake.WakeWordService
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

/**
 * The assistant as an activity: what the Quick Settings tile, the "Ask" shortcut and a plain
 * `ACTION_ASSIST` open, and where the voice-interaction session hands over what needs an
 * activity -- a question asked over the lock screen, or the microphone's permission prompt
 * ([EXTRA_LISTEN]). The same sheet as the session's, in a see-through window over the app
 * underneath. Shown over the lock screen, but it asks Android to unlock before it sends
 * anything -- and shows nothing from the account until then.
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

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        controller = AssistController(this, lifecycleScope)
        keyguard = getSystemService(KeyguardManager::class.java)
        locked.value = keyguard.isKeyguardLocked
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
                        keyguard.requestDismissKeyguard(
                            this@AssistActivity,
                            object : KeyguardManager.KeyguardDismissCallback() {
                                override fun onDismissSucceeded() {
                                    locked.value = false
                                    if (text.isBlank()) controller.opened(locked = false) else controller.ask(text)
                                }
                            },
                        )
                    },
                    onOpenApp = {
                        startActivity(controller.appIntent())
                        finish()
                    },
                    onNeedMic = { mic.launch(Manifest.permission.RECORD_AUDIO) },
                    onDismiss = { finish() },
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
     * voice button, the widget's microphone, the Speak shortcut), the last assistant chat (the
     * widget, the shortcut), or just the sheet. Over the lock screen each waits for Android to
     * unlock first.
     */
    private fun take(intent: Intent) {
        val opening = Opening.of(intent)
        val waiting = intent.getStringExtra(EXTRA_QUESTION).orEmpty()
        val go = {
            when {
                waiting.isNotBlank() -> controller.ask(waiting)
                opening == Opening.Continue -> controller.continueWhenReady()
                opening == Opening.Listen && !controller.micAllowed() -> mic.launch(Manifest.permission.RECORD_AUDIO)
                opening == Opening.Listen -> controller.opened(locked = false, listen = true)
                else -> controller.opened(locked = false)
            }
        }
        if (keyguard.isKeyguardLocked) {
            keyguard.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        locked.value = false
                        go()
                    }

                    override fun onDismissCancelled() = finish()

                    override fun onDismissError() = finish()
                },
            )
        } else {
            go()
        }
    }

    override fun onDestroy() {
        controller.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_QUESTION = "eu.llembas.android.QUESTION"

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
