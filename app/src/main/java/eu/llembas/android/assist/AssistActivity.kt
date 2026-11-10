package eu.llembas.android.assist

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
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
        val keyguard = getSystemService(KeyguardManager::class.java)
        locked.value = keyguard.isKeyguardLocked
        val waiting = intent.getStringExtra(EXTRA_QUESTION).orEmpty()
        val wantsMic = intent.getBooleanExtra(EXTRA_LISTEN, false)
        if (keyguard.isKeyguardLocked) {
            keyguard.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        locked.value = false
                        if (waiting.isNotBlank()) controller.ask(waiting) else controller.opened(locked = false)
                    }

                    override fun onDismissCancelled() = finish()

                    override fun onDismissError() = finish()
                },
            )
        } else if (waiting.isNotBlank()) {
            controller.ask(waiting)
        } else if (wantsMic && !controller.micAllowed()) {
            mic.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            controller.opened(locked = false)
        }
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

    override fun onDestroy() {
        controller.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_QUESTION = "eu.llembas.android.QUESTION"

        /** Handed over by the session: ask for the microphone, then listen. */
        const val EXTRA_LISTEN = "eu.llembas.android.LISTEN"
    }
}
