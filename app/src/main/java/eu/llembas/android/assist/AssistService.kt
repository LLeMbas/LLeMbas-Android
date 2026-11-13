package eu.llembas.android.assist

import android.app.KeyguardManager
import android.app.assist.AssistStructure
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
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
import kotlinx.coroutines.Job
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
    override fun onCreate() {
        super.onCreate()
        AssistLink.created(System.currentTimeMillis())
    }

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
        // Only the instance that is the current one disconnects it: an old one going away late
        // must not say "not connected" over a new one.
        if (current === this) {
            current = null
            AssistLink.shutdown(System.currentTimeMillis())
        }
        Log.i(TAG, "assistant service disconnected by Android")
        super.onShutdown()
    }

    override fun onDestroy() {
        AssistLink.destroyed(System.currentTimeMillis())
        super.onDestroy()
    }

    /** Android 14+: Android is about to show a session it was asked for -- or, below, could not. Kept for the wake word's record. */
    override fun onPrepareToShowSession(args: Bundle, showFlags: Int) {
        AssistLink.prepared(SystemClock.elapsedRealtime())
        super.onPrepareToShowSession(args, showFlags)
    }

    override fun onShowSessionFailed(args: Bundle) {
        AssistLink.showFailed(SystemClock.elapsedRealtime())
        Log.w(TAG, "Android could not show the assistant's session")
        super.onShowSessionFailed(args)
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

        /**
         * Whether Android itself uses this service as its voice-interaction service right now.
         * Holding the assistant role is not the same: Android keeps the service only while a
         * speech recogniser is set that it accepts, and resets it at start-up otherwise -- the
         * long press then opens the assistant as an ordinary screen, and nothing is connected
         * that could open it from the background.
         */
        fun isActive(context: Context): Boolean =
            runCatching { VoiceInteractionService.isActiveService(context, ComponentName(context, LembasAssistService::class.java)) }.getOrDefault(false)

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

    /** Whether this session's sheet is up, as far as the wake word's pause is concerned. */
    private var up = false

    /** Over the lock screen: waiting for what is heard, to hand it to the sheet's activity. */
    private var handing: Job? = null

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
                        onUnlockAndAsk = { text -> handOff(Held(text, null, spoken = false).takeIf { text.isNotBlank() }) },
                        onOpenApp = {
                            startAssistantActivity(current.value.appIntent())
                            hide()
                        },
                        onNeedMic = { handOff(null, listen = true) },
                        onDismiss = { hide() },
                    )
                }
            }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        registry.currentState = Lifecycle.State.RESUMED
        // Android calls this again for a show asked for while the sheet is up, and hides once:
        // the sheet is counted as up once.
        if (!up) {
            up = true
            AssistPresence.shown()
        }
        AssistLink.sheetShown(SystemClock.elapsedRealtime())
        // One sheet, never two: a sheet the wake word opened as a screen of the app, because
        // this one was slow to come, goes now that this one is here.
        AssistActivity.closeWoken()
        // The wake word asks for the sheet listening, whatever Settings says about opening --
        // over the lock screen too, where what it hears is held and not asked.
        val showing = controller
        showing.opened(listen = args?.getBoolean(AssistActivity.EXTRA_LISTEN) == true)
        // A question that waits for the unlock (heard, or typed, over the lock screen) goes to
        // the sheet's activity, which can ask Android to unlock. Once: Android may call onShow
        // again while the sheet is up.
        handing?.cancel()
        handing = scope.launch {
            showing.state.first { it.held && locked() }
            showing.takeHeld()?.let { handOff(it) }
        }
    }

    /**
     * Each showing starts fresh -- a new question, the screen not included, listening first --
     * so a new controller is made as this one hides: ready for the screen the next invocation
     * hands over, which may come before or after its `onShow`.
     */
    override fun onHide() {
        super.onHide()
        if (up) {
            up = false
            AssistPresence.hidden()
        }
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
     * which asks Android to unlock first and only then sends it. It is left in the app's own
     * memory ([AssistHandOff]) and the intent carries its ticket. The microphone's permission
     * prompt needs an activity too.
     */
    private fun handOff(question: Held?, listen: Boolean = false) {
        startAssistantActivity(AssistActivity.handedOver(context, question?.let { AssistHandOff.leave(it) }, listen))
        hide()
    }
}
