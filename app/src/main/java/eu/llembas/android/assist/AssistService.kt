package eu.llembas.android.assist

import android.app.KeyguardManager
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.View
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import eu.llembas.android.lembas
import eu.llembas.android.ui.theme.LembasTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * The digital assistant. Being the device's voice-interaction service is what Settings ›
 * Default apps › Digital assistant app chooses, and what the power button's long press, the
 * assist gesture and a headset's button open. The service itself does nothing until asked:
 * there is no hotword here -- Android keeps always-on hotword detection for the system's own
 * preinstalled assistant (`CAPTURE_AUDIO_HOTWORD` is a privileged permission).
 */
class LembasAssistService : VoiceInteractionService()

class LembasAssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = LembasAssistSession(this)
}

/**
 * One invocation: a sheet over whatever is on screen. A Compose view in a window that is not an
 * activity's, so this session is its own lifecycle and saved-state owner for the view tree.
 */
class LembasAssistSession(context: Context) :
    VoiceInteractionSession(context),
    LifecycleOwner,
    SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller = AssistController(context, scope)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    override fun onCreateContentView(): View {
        val theme = runBlocking { context.lembas.instances.prefs.first() }.theme
        return ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@LembasAssistSession)
            setViewTreeSavedStateRegistryOwner(this@LembasAssistSession)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                LembasTheme(theme) {
                    AssistPanel(
                        controller = controller,
                        locked = locked(),
                        onUnlockAndAsk = { text -> handOff(text) },
                        onOpenApp = {
                            startAssistantActivity(controller.appIntent())
                            hide()
                        },
                        onNeedMic = { handOff("") },
                    )
                }
            }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (!scope.isActiveScope()) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            controller = AssistController(context, scope)
        }
        registry.currentState = Lifecycle.State.RESUMED
    }

    override fun onHide() {
        super.onHide()
        controller.close()
        registry.currentState = Lifecycle.State.CREATED
    }

    override fun onDestroy() {
        super.onDestroy()
        controller.close()
        scope.cancel()
        registry.currentState = Lifecycle.State.DESTROYED
    }

    /** What is on screen, if the person allowed it (Settings › "Use text from screen"). */
    override fun onHandleAssist(state: VoiceInteractionSession.AssistState) {
        super.onHandleAssist(state)
        val structure = state.assistStructure ?: return
        val text = buildString {
            for (i in 0 until structure.windowNodeCount) collect(structure.getWindowNodeAt(i).rootViewNode, this)
        }
        controller.offerScreen(text, null)
    }

    /** And a picture of it, if allowed ("Use screenshot"). Sent only when "Include screen" is on. */
    override fun onHandleScreenshot(screenshot: Bitmap?) {
        super.onHandleScreenshot(screenshot)
        controller.offerScreen(null, screenshot)
    }

    private fun collect(node: AssistStructure.ViewNode?, out: StringBuilder) {
        if (node == null || out.length > AssistController.MAX_SCREEN_CHARS) return
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.appendLine(it) }
        for (i in 0 until node.childCount) collect(node.getChildAt(i), out)
    }

    private fun locked(): Boolean = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    /**
     * Over the lock screen nothing is sent from here: the question goes to [AssistActivity],
     * which asks Android to unlock first and only then sends it. The microphone's permission
     * prompt needs an activity too.
     */
    private fun handOff(text: String) {
        startAssistantActivity(
            Intent(context, AssistActivity::class.java)
                .putExtra(AssistActivity.EXTRA_QUESTION, text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        hide()
    }

    private fun CoroutineScope.isActiveScope(): Boolean = coroutineContext[kotlinx.coroutines.Job]?.isActive == true
}
