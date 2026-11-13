package eu.llembas.android.wake

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import eu.llembas.android.MainActivity
import eu.llembas.android.R
import eu.llembas.android.assist.AssistActivity
import eu.llembas.android.assist.LembasAssistService
import eu.llembas.android.lembas
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Listening for the wake word: opt-in (Settings › Assistant › Wake word), off by default.
 *
 * A `microphone` foreground service with its notification up the whole time it runs -- Android
 * lets an app hear the microphone in the background only that way, and only once the person has
 * started it from the app. The audio goes to the on-device engine ([WakeEngine]) in memory and
 * nowhere else; it is not kept, and nothing is sent anywhere until the word is heard. Then the
 * microphone is let go and the assistant opens, listening, as the power button would open it.
 *
 * It pauses itself (the notification says why) while the assistant's sheet is up, in battery
 * saver, and -- unless Settings says otherwise -- while the screen is off; it goes on once the
 * reason is gone. It cannot start itself at boot (Android forbids a microphone service there):
 * [WakeBootReceiver] posts "tap to listen again" instead.
 */
class WakeWordService : LifecycleService() {
    private val screenOn = MutableStateFlow(true)
    private val batterySaver = MutableStateFlow(false)
    private val mayRecord = MutableStateFlow(true)
    private var following: Job? = null
    private var watching: Job? = null

    /** This instance's right to write the state (WakeStatus.begin). */
    private var token = 0

    /** The newest start Android has delivered: what the service stops itself by, so a start still on its way is not stopped. */
    private var lastStart = 0

    /** Set when the service could not even start: the state then says why, and is not wiped on the way out. */
    private var keepState = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    screenOn.value = true
                    mayRecord.value = micAllowed(this@WakeWordService)
                }

                Intent.ACTION_SCREEN_OFF -> screenOn.value = false

                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> batterySaver.value = power().isPowerSaveMode
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        channel(this)
        screenOn.value = power().isInteractive
        batterySaver.value = power().isPowerSaveMode
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Every start counts, Stop's too: the service stops itself by the newest one it was given.
        lastStart = startId
        if (intent?.action == ACTION_STOP) {
            // Stop is the setting going off; the loop sees it and ends the service on the path
            // that has shown its notification first (follow). The service is stopped from here
            // only when nothing is running in it, and only if no newer start is on its way
            // (stopSelf(startId)): stopping under a pending foreground start closes the app.
            lifecycleScope.launch {
                lembas.instances.savePrefs { it.copy(wakeWord = false) }
                if (following?.isActive != true) stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        mayRecord.value = micAllowed(this)
        val running = following?.isActive == true
        // The notification first: Android stops a foreground service that is slow to show one.
        // It says what is true now -- "starting" for a service that just began, and on every
        // later start (the app opens) whatever the state already is; never a phrase by default.
        val now = wakeStateAtStart(running, WakeStatus.state.value)
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, wakeNotification(this, now), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            // Android refuses a microphone service without the permission, or from the background.
            Log.w(TAG, "wake word: the service could not start", e)
            keepState = true
            WakeStatus.set(
                if (mayRecord.value) {
                    WakeState.Failed(WakeFailure.Microphone, WakeError(WakeFailure.Microphone, e).detail)
                } else {
                    WakeState.Paused(WakePause.NoMicrophone)
                },
            )
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        // It is listening again: "tap to listen again" (after a restart) has nothing left to say.
        manager.cancel(WakeBootReceiver.RESUME_ID)
        if (!running) {
            keepState = false
            // This instance writes the state from here; an older one's last words count for nothing.
            token = WakeStatus.begin()
            // And it starts from what the sheets say: no hold of an earlier instance is left standing.
            AssistPresence.release()
            WakeStatus.set(token, now)
            following = lifecycleScope.launch { follow() }
        }
        if (watching == null) {
            // The one place the notification is written: from the state, whenever it changes.
            watching = lifecycleScope.launch {
                WakeStatus.state.collect { state ->
                    if (state != WakeState.Off) manager.notify(NOTIFICATION_ID, wakeNotification(this@WakeWordService, state))
                }
            }
            // A tap on the failure in Settings: try now, not when the back-off says.
            lifecycleScope.launch { WakeStatus.retries.drop(1).collect { listener.retryNow() } }
        }
        return START_NOT_STICKY
    }

    private val listener by lazy {
        WakeListener(
            say = { WakeStatus.set(token, it) },
            engine = { keywords -> WakeEngine.create(this, keywords) },
            microphone = { PhoneMicrophone() },
            // A test in Settings takes the word; otherwise the assistant opens.
            heard = { phrase ->
                if (WakeStatus.heardInTrial(SystemClock.elapsedRealtime())) {
                    val tested = WakeAttempt(at = System.currentTimeMillis(), phrase = phrase.words, outcome = WakeAttempt.OUT_TESTED)
                    wakeScope.launch { record(this@WakeWordService, tested) }
                } else {
                    wake(phrase)
                }
            },
            log = { message, cause -> Log.w(TAG, message, cause) },
        )
    }

    /** Listen, or pause, as the phone and Settings change; stop when Settings turns it off. */
    private suspend fun follow() {
        val mine = token
        val plans = combine(lembas.instances.prefs, screenOn, batterySaver, AssistPresence.open, mayRecord) { prefs, on, saver, open, mic ->
            if (!prefs.wakeWord) {
                null
            } else {
                val phrase = WakePhrases.byId(prefs.wakePhrase)
                WakePlan(phrase, wakeKeywords(phrase, WakeSensitivity.byId(prefs.wakeSensitivity)), wakePause(on, prefs.wakeScreenOnly, saver, open > 0, mic))
            }
        }
        try {
            listener.follow(plans.takeWhile { it != null }.filterNotNull())
        } finally {
            // After the loop has ended, so nothing it said on its way out is left standing --
            // and only if no newer writer has taken over meanwhile.
            if (!keepState) WakeStatus.set(mine, WakeState.Off)
        }
        // Not if a newer start is already on its way: that one finds the loop ended and begins it again.
        stopSelf(lastStart)
    }

    /**
     * The word was heard: the assistant opens listening ([WakeOpener] decides how and checks that
     * it did), and what was tried and how it went is written down ([WakeAttempt], kept in the
     * settings and shown in Settings › Wake word), because a silent "no" here looks to the
     * person like a wake word that does not work.
     *
     * It runs on the app's own scope, never inside the listening loop: the hold below pauses
     * the listener, which cancels the loop that heard the word -- and a wake that ran in that
     * loop was cancelled by its own first step. One at a time: a word heard while the last one
     * is still being answered is let go.
     */
    private fun wake(phrase: WakePhrase) {
        if (waking?.isActive == true) return
        // The microphone is the sheet's from here, for as long as the attempt can take; once it
        // is over, for a moment more, and then for as long as a sheet says it is up.
        AssistPresence.holdFor(WakeOpener.ATTEMPT_MAX_MS)
        waking = wakeScope.launch {
            try {
                val asked = SystemClock.elapsedRealtime()
                val opener = WakeOpener(
                    facts = { wakeFacts(this@WakeWordService) },
                    awaitConnected = { ms -> withTimeoutOrNull(ms) { AssistLink.connected.first { it } } != null },
                    showSession = {
                        val assistant = LembasAssistService.current ?: error("the assistant service is not connected")
                        assistant.showSession(Bundle().apply { putBoolean(AssistActivity.EXTRA_LISTEN, true) }, 0)
                    },
                    // Through the assistant service where it is connected (the same permission either way: Android lets
                    // the whole app start activities while it holds the service bound).
                    startActivity = { (LembasAssistService.current ?: this@WakeWordService).startActivity(AssistActivity.woken(this@WakeWordService)) },
                    sheetCame = { ms ->
                        withTimeoutOrNull(ms) { AssistLink.shown.first { it >= asked } }?.let { (it - asked).coerceAtLeast(0) }
                    },
                    notify = { attempt ->
                        getSystemService(NotificationManager::class.java).notify(HEARD_ID, wakeHeardNotification(this@WakeWordService, attempt.assistant))
                    },
                    showSignals = { (AssistLink.preparedAt >= asked) to (AssistLink.showFailedAt >= asked) },
                )
                val link = WakeAttempt(
                    createdAt = AssistLink.createdAt,
                    destroyedAt = AssistLink.destroyedAt,
                    readyAt = AssistLink.readyAt,
                    shutdownAt = AssistLink.shutdownAt,
                )
                record(this@WakeWordService, opener.open(phrase.words, link))
            } finally {
                AssistPresence.holdFor(SHEET_GRACE_MS)
            }
        }
    }

    private var waking: Job? = null

    private fun power() = getSystemService(PowerManager::class.java)

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        // The scope ends with the service: the listener's loop is joined and its engine released
        // on the way out (WakeListener.follow), not here under a loop that may still run.
        if (!keepState) WakeStatus.set(token, WakeState.Off)
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "wake_word"
        const val CHANNEL_HEARD = "wake_heard"
        private const val NOTIFICATION_ID = 7001
        const val HEARD_ID = 7002
        private const val SHEET_GRACE_MS = 3_000L

        /** The app's own scope: a wake and its record outlive the listening loop, and the service too. */
        private val wakeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        /** What is true at this moment, for the opener and the record. */
        fun wakeFacts(context: Context): WakeFacts = WakeFacts(
            connected = LembasAssistService.current != null,
            active = LembasAssistService.isActive(context),
            role = LembasAssistService.holdsRole(context),
            recognizers = context.packageManager.hasSystemFeature(PackageManager.FEATURE_VOICE_RECOGNIZERS),
            screenOn = context.getSystemService(PowerManager::class.java).isInteractive,
            locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked,
            visible = AppVisibility.visible,
        )

        /**
         * Write [attempt] down: in the log under a fixed tag, and first among the last few in the
         * settings. Not cancellable: what happened is kept whatever ends meanwhile.
         */
        suspend fun record(context: Context, attempt: WakeAttempt) = withContext(NonCancellable) {
            Log.i(TAG, "wake word heard: $attempt")
            runCatching { context.lembas.instances.savePrefs { it.copy(wakeLog = wakeLogAdd(it.wakeLog, attempt)) } }
            Unit
        }
        private const val TAG = "LLeMbasWake"
        const val ACTION_STOP = "eu.llembas.android.action.WAKE_STOP"

        /**
         * Start listening, or say why not. Only from the app on screen: Android allows a
         * microphone service from there, and only with the microphone permission -- without it
         * the service is not started at all, and the state says so.
         */
        fun start(context: Context) {
            if (!WakeEngine.AVAILABLE) return WakeStatus.set(WakeState.Failed(WakeFailure.NoEngine))
            if (!micAllowed(context)) return WakeStatus.set(WakeState.Paused(WakePause.NoMicrophone))
            try {
                ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "wake word: the service could not be started", e)
                WakeStatus.set(WakeState.Failed(WakeFailure.Microphone, WakeError(WakeFailure.Microphone, e).detail))
            }
        }

        /**
         * The wake word was turned off. The service is not stopped from outside: it ends itself
         * when it sees the setting, after it has shown its notification -- stopping it under a
         * foreground start that is still on its way closes the app. The state is "off" at once.
         */
        fun stop() {
            WakeStatus.set(WakeState.Off)
        }

        fun micAllowed(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        fun channel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannels(
                listOf(
                    NotificationChannel(CHANNEL, context.getString(R.string.channel_wake), NotificationManager.IMPORTANCE_LOW),
                    // "Heard you. Tap to ask.": it must come up over what is on screen, or the word was for nothing.
                    NotificationChannel(CHANNEL_HEARD, context.getString(R.string.channel_wake_heard), NotificationManager.IMPORTANCE_HIGH),
                ),
            )
        }
    }
}

/**
 * "Heard you. Tap to ask.": what the wake word posts when it cannot open the assistant itself.
 * A fixed sentence, nothing from the account; the tap opens the assistant listening.
 */
fun wakeHeardNotification(context: Context, assistant: Boolean = false): Notification {
    val ask = PendingIntent.getActivity(context, 1, AssistActivity.listening(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    return NotificationCompat.Builder(context, WakeWordService.CHANNEL_HEARD)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(context.getString(R.string.wake_heard))
        // Why it is a notification and not the sheet, in fixed words: LLeMbas is not the phone's
        // assistant -- or it is, and could not open by itself this time (Settings says which step said no).
        .setContentText(context.getString(if (assistant) R.string.wake_heard_could_not else R.string.wake_heard_line))
        .setContentIntent(ask)
        .setAutoCancel(true)
        .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setTimeoutAfter(HEARD_TIMEOUT_MS)
        .build()
}

/** A minute: a "tap to ask" nobody tapped is no longer an answer to anything. */
private const val HEARD_TIMEOUT_MS = 60_000L

/**
 * The persistent notification, from [state] alone: what the wake word is doing in plain words
 * (the phrase it listens for, why it is paused, what failed). Nothing from the account.
 */
fun wakeNotification(context: Context, state: WakeState): Notification {
    val stop = PendingIntent.getService(
        context,
        2,
        Intent(context, WakeWordService::class.java).setAction(WakeWordService.ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE,
    )
    val open = PendingIntent.getActivity(context, 3, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    return NotificationCompat.Builder(context, WakeWordService.CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(wakeTitle(context, state))
        .setContentText(wakeLine(context, state))
        .setOngoing(true)
        .setSilent(true)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setContentIntent(open)
        .addAction(0, context.getString(R.string.wake_stop), stop)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()
}

/**
 * The phone's microphone at 16 kHz, recording from the moment it is made -- or an exception that
 * says what did not work. Android reports none of this by throwing: a recorder that could not be
 * opened is merely "uninitialized", and one that did not start merely "stopped".
 */
@android.annotation.SuppressLint("MissingPermission")
private class PhoneMicrophone : WakeMicrophone {
    private val audio: AudioRecord

    init {
        val rate = WakeListener.RATE
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(min > 0) { "the phone cannot record at 16 kHz" }
        audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
        try {
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "the microphone could not be opened" }
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "recording did not start; another app may have the microphone" }
        } catch (e: Exception) {
            audio.release()
            throw e
        }
    }

    override fun read(into: ShortArray): Int = audio.read(into, 0, into.size)

    override fun close() {
        runCatching { audio.stop() }
        audio.release()
    }
}

/**
 * After a restart: Android does not let a microphone service start at boot, so a wake word that
 * was on says "tap to listen again" -- the tap opens the app, which starts it.
 */
class WakeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val prefs = context.lembas.instances.prefs.first()
                if (!prefs.wakeWord || !WakeEngine.AVAILABLE) return@launch
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return@launch
                WakeWordService.channel(context)
                val open = PendingIntent.getActivity(
                    context,
                    4,
                    Intent(context, MainActivity::class.java).putExtra(EXTRA_RESUME, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                context.getSystemService(NotificationManager::class.java).notify(
                    RESUME_ID,
                    NotificationCompat.Builder(context, WakeWordService.CHANNEL)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle(context.getString(R.string.wake_resume))
                        .setContentText(context.getString(R.string.wake_resume_line))
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .build(),
                )
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** On MainActivity's intent: start listening for the wake word again. */
        const val EXTRA_RESUME = "eu.llembas.android.WAKE_RESUME"
        const val RESUME_ID = 7003
    }
}
