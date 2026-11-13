package eu.llembas.android.assist

import android.app.KeyguardManager
import android.app.assist.AssistStructure
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.SystemClock
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import eu.llembas.android.AppLanguage
import eu.llembas.android.lembas
import eu.llembas.android.ui.theme.LembasTheme
import eu.llembas.android.wake.AssistLink
import eu.llembas.android.wake.AssistPresence
import eu.llembas.android.wake.wakeLogConnected
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The digital assistant. Being the device's voice-interaction service is what Settings ›
 * Default apps › Digital assistant app chooses, and what the power button's long press, the
 * assist gesture and a headset's button open. The service itself does nothing until asked:
 * there is no hotword here -- Android keeps always-on hotword detection for the system's own
 * preinstalled assistant (`CAPTURE_AUDIO_HOTWORD` is a privileged permission).
 */
class LembasAssistService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        current = this
        // When Android connected it is kept: the wake word's record says whether it was, and an
        // attempt that found it not connected is told that it came later.
        val now = System.currentTimeMillis()
        AssistLink.ready(now)
        Log.i(TAG, "assistant service connected by Android")
        background.launch { runCatching { lembas.instances.savePrefs { it.copy(wakeLog = wakeLogConnected(it.wakeLog, now)) } } }
    }

    override fun onShutdown() {
        if (current === this) current = null
        AssistLink.shutdown(System.currentTimeMillis())
        Log.i(TAG, "assistant service disconnected by Android")
        super.onShutdown()
    }

    companion object {
        /**
         * The service while the system has it bound -- while LLeMbas is the phone's assistant. The
         * wake word opens the sheet through it ([showSession]), which Android allows from the
         * background, as it does for the power button.
         */
        @Volatile
        var current: LembasAssistService? = null
            private set

        private const val TAG = "LLeMbasWake"
        private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** Whether Android says LLeMbas holds the assistant role (Settings › Default apps › Digital assistant app). */
        fun holdsRole(context: Context): Boolean =
            runCatching { context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) }.getOrNull() ?: false
    }
}

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
    private val current = mutableStateOf(controller)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
        // A see-through window over the whole screen: the sheet draws its own dim and sits at
        // the bottom, the app underneath stays in sight, and the sheet moves with the keyboard.
        window.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            w.setDimAmount(0f)
            WindowCompat.setDecorFitsSystemWindows(w, false)
            @Suppress("DEPRECATION")
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    override fun onCreateContentView(): View {
        val theme = runBlocking { context.lembas.instances.prefs.first() }.theme
        // In the app's language on a phone older than Android 13, which has no per-app one.
        return ComposeView(AppLanguage.wrap(context)).apply {
            setViewTreeLifecycleOwner(this@LembasAssistSession)
            setViewTreeSavedStateRegistryOwner(this@LembasAssistSession)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                LembasTheme(theme) {
                    // The controller is made again for each showing: read it from state, so the
                    // sheet follows the new one.
                    AssistPanel(
                        controller = current.value,
                        locked = locked(),
                        onUnlockAndAsk = { text -> handOff(text) },
                        onOpenApp = {
                            startAssistantActivity(current.value.appIntent())
                            hide()
                        },
                        onNeedMic = { handOff("", listen = true) },
                        onDismiss = { hide() },
                    )
                }
            }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        registry.currentState = Lifecycle.State.RESUMED
        AssistPresence.shown()
        AssistLink.sheetShown(SystemClock.elapsedRealtime())
        // The wake word asks for the sheet listening, whatever Settings says about opening.
        controller.opened(locked(), listen = args?.getBoolean(AssistActivity.EXTRA_LISTEN) == true)
    }

    /**
     * Each showing starts fresh -- a new question, the screen not included, listening first --
     * so a new controller is made as this one hides: ready for the screen the next invocation
     * hands over, which may come before or after its `onShow`.
     */
    override fun onHide() {
        super.onHide()
        AssistPresence.hidden()
        controller.close()
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        controller = AssistController(context, scope)
        current.value = controller
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

    /** And a picture of it, if allowed ("Use screenshot"). Sent only when "Use screen" is on. */
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
    private fun handOff(text: String, listen: Boolean = false) {
        startAssistantActivity(
            Intent(context, AssistActivity::class.java)
                .putExtra(AssistActivity.EXTRA_QUESTION, text)
                .putExtra(AssistActivity.EXTRA_LISTEN, listen)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        hide()
    }
}
