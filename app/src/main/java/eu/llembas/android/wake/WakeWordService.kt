package eu.llembas.android.wake

import android.Manifest
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

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
        if (intent?.action == ACTION_STOP) {
            lifecycleScope.launch {
                lembas.instances.savePrefs { it.copy(wakeWord = false) }
                stopSelf()
            }
            return START_NOT_STICKY
        }
        mayRecord.value = micAllowed(this)
        // The notification first: Android stops a foreground service that is slow to show one.
        // It says what is true now -- "starting" for a service that just began, and on every
        // later start (the app opens) whatever the state already is; never a phrase by default.
        val now = WakeStatus.state.value.takeUnless { following == null || it == WakeState.Off } ?: WakeState.Starting
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
        if (following == null) {
            keepState = false
            WakeStatus.set(now)
            following = lifecycleScope.launch { follow() }
            // The one place the notification is written: from the state, whenever it changes.
            val manager = getSystemService(NotificationManager::class.java)
            lifecycleScope.launch {
                WakeStatus.state.collect { state ->
                    if (state != WakeState.Off) manager.notify(NOTIFICATION_ID, wakeNotification(this@WakeWordService, state))
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Listen, or pause, as the phone and Settings change; stop when Settings turns it off. */
    private suspend fun follow() {
        val plans = combine(lembas.instances.prefs, screenOn, batterySaver, AssistPresence.open, mayRecord) { prefs, on, saver, open, mic ->
            if (!prefs.wakeWord) {
                null
            } else {
                val phrase = WakePhrases.byId(prefs.wakePhrase)
                WakePlan(phrase, wakeKeywords(phrase, WakeSensitivity.byId(prefs.wakeSensitivity)), wakePause(on, prefs.wakeScreenOnly, saver, open > 0, mic))
            }
        }
        val listener = WakeListener(
            say = WakeStatus::set,
            engine = { keywords -> WakeEngine.create(this, keywords) },
            microphone = { PhoneMicrophone() },
            // A test in Settings takes the word; otherwise the assistant opens.
            heard = { if (!WakeStatus.heardInTrial(SystemClock.elapsedRealtime())) wake() },
            log = { message, cause -> Log.w(TAG, message, cause) },
        )
        try {
            listener.follow(plans.takeWhile { it != null }.filterNotNull())
        } finally {
            // After the loop has ended, so nothing it said on its way out is left standing.
            if (!keepState) WakeStatus.set(WakeState.Off)
        }
        stopSelf()
    }

    /**
     * The word was heard: the assistant opens listening. Through the voice-interaction service
     * when LLeMbas is the phone's assistant (Android lets it open the sheet from the background),
     * else as an activity; and if Android refuses that too, a notification to tap.
     */
    private fun wake() {
        AssistPresence.shown() // the microphone is the sheet's from here, until it says it is gone
        val shownBySystem = LembasAssistService.current?.let { service ->
            runCatching { service.showSession(Bundle().apply { putBoolean(AssistActivity.EXTRA_LISTEN, true) }, 0) }.isSuccess
        } ?: false
        val opened = shownBySystem || runCatching { startActivity(AssistActivity.listening(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (!opened) {
            getSystemService(NotificationManager::class.java).notify(
                HEARD_ID,
                NotificationCompat.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(getString(R.string.wake_heard))
                    .setContentIntent(PendingIntent.getActivity(this, 1, AssistActivity.listening(this), PendingIntent.FLAG_IMMUTABLE))
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build(),
            )
        }
        // Released by the sheet as it shows and hides; if nothing opened, at once.
        lifecycleScope.launch {
            delay(SHEET_GRACE_MS)
            AssistPresence.hidden()
        }
    }

    private fun power() = getSystemService(PowerManager::class.java)

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        // The scope ends with the service: the listener's loop is joined and its engine released
        // on the way out (WakeListener.follow), not here under a loop that may still run.
        if (!keepState) WakeStatus.set(WakeState.Off)
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "wake_word"
        private const val NOTIFICATION_ID = 7001
        private const val HEARD_ID = 7002
        private const val SHEET_GRACE_MS = 3_000L
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

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
            WakeStatus.set(WakeState.Off)
        }

        fun micAllowed(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        fun channel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.channel_wake), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }
}

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
