package eu.llembas.android.assist

import android.Manifest
import android.app.KeyguardManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import eu.llembas.android.auth.Prefs
import eu.llembas.android.lembas
import eu.llembas.android.ui.theme.LembasTheme

/**
 * The assistant as an activity: what the Quick Settings tile, the "Ask" shortcut and a plain
 * `ACTION_ASSIST` open, and where the voice-interaction session hands a question asked over the
 * lock screen. Shown over the lock screen, but it asks Android to unlock before it sends
 * anything -- and shows nothing from the account until then.
 */
class AssistActivity : ComponentActivity() {
    private lateinit var controller: AssistController
    private val mic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) controller.listen()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        controller = AssistController(this, lifecycleScope)
        val keyguard = getSystemService(KeyguardManager::class.java)
        val waiting = intent.getStringExtra(EXTRA_QUESTION).orEmpty()
        if (keyguard.isKeyguardLocked) {
            keyguard.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        if (waiting.isNotBlank()) controller.ask(waiting)
                    }

                    override fun onDismissCancelled() = finish()

                    override fun onDismissError() = finish()
                },
            )
        } else if (waiting.isNotBlank()) {
            controller.ask(waiting)
        }
        setContent {
            val prefs by lembas.instances.prefs.collectAsState(initial = Prefs())
            LembasTheme(prefs.theme) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { finish() }
                        .imePadding(),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
                        AssistPanel(
                            controller = controller,
                            locked = keyguard.isKeyguardLocked,
                            onUnlockAndAsk = { text ->
                                keyguard.requestDismissKeyguard(
                                    this@AssistActivity,
                                    object : KeyguardManager.KeyguardDismissCallback() {
                                        override fun onDismissSucceeded() = controller.ask(text)
                                    },
                                )
                            },
                            onOpenApp = {
                                startActivity(controller.appIntent())
                                finish()
                            },
                            onNeedMic = { mic.launch(Manifest.permission.RECORD_AUDIO) },
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        controller.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_QUESTION = "eu.llembas.android.QUESTION"
    }
}
