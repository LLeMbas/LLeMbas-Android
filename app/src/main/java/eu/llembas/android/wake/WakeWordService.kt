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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private var listening: Job? = null
    private var detector: WakeDetector? = null
    private var phraseId: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> screenOn.value = true
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
        // The notification first: Android stops a foreground service that is slow to show one.
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(null, WakePause.None), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        if (listening == null) follow()
        return START_NOT_STICKY
    }

    /** Listen, or pause, as the phone and Settings change; stop when Settings turns it off. */
    private fun follow() {
        listening = lifecycleScope.launch {
            combine(lembas.instances.prefs, screenOn, batterySaver, AssistPresence.open) { prefs, on, saver, open ->
                if (!prefs.wakeWord) {
                    null
                } else {
                    prefs.wakePhrase to wakePause(on, prefs.wakeScreenOnly, saver, open > 0, micAllowed())
                }
            }.distinctUntilChanged().collect { now ->
                if (now == null) {
                    stopSelf()
                    return@collect
                }
                val (id, pause) = now
                val phrase = WakePhrases.byId(id)
                notify(phrase, pause)
                hearing?.cancel()
                hearing = if (pause == WakePause.None) launch { hear(phrase) } else null
            }
        }
    }

    private var hearing: Job? = null

    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun hear(phrase: WakePhrase) {
        val engine = withContext(Dispatchers.Default) {
            if (phraseId != phrase.id) {
                detector?.release()
                detector = WakeEngine.create(this@WakeWordService, phrase)
                phraseId = phrase.id
            }
            detector
        } ?: return notify(phrase, WakePause.NoMicrophone)
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val audio = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE))
        }.getOrNull() ?: return
        var heard = false
        withContext(Dispatchers.IO) {
            try {
                audio.startRecording()
                val chunk = ShortArray(RATE / 10)
                val samples = FloatArray(chunk.size)
                while (isActive && !heard) {
                    val read = audio.read(chunk, 0, chunk.size)
                    if (read <= 0) continue
                    for (i in 0 until read) samples[i] = chunk[i] / 32768f
                    heard = engine.accept(if (read == chunk.size) samples else samples.copyOf(read))
                }
            } finally {
                runCatching { audio.stop() }
                audio.release()
            }
        }
        if (heard) wake()
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

    private fun notify(phrase: WakePhrase, pause: WakePause) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(phrase, pause))
    }

    /** What the persistent notification says: the phrase it listens for, or why it is paused. Nothing from the account. */
    private fun notification(phrase: WakePhrase?, pause: WakePause): Notification {
        val title = when (pause) {
            WakePause.None -> getString(R.string.wake_listening_for, (phrase ?: WakePhrases.default).words)
            WakePause.ScreenOff -> getString(R.string.wake_paused_screen)
            WakePause.BatterySaver -> getString(R.string.wake_paused_battery)
            WakePause.Assistant -> getString(R.string.wake_paused_assistant)
            WakePause.NoMicrophone -> getString(R.string.wake_paused_microphone)
        }
        val stop = PendingIntent.getService(this, 2, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(getString(R.string.wake_notification_line))
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .addAction(0, getString(R.string.wake_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun micAllowed() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun power() = getSystemService(PowerManager::class.java)

    override fun onDestroy() {
        hearing?.cancel()
        listening?.cancel()
        runCatching { unregisterReceiver(receiver) }
        detector?.release()
        detector = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "wake_word"
        private const val NOTIFICATION_ID = 7001
        private const val HEARD_ID = 7002
        private const val RATE = 16_000
        private const val SHEET_GRACE_MS = 3_000L
        const val ACTION_STOP = "eu.llembas.android.action.WAKE_STOP"

        /** Start listening. Only from the app on screen: Android allows a microphone service from there. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }

        fun channel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.channel_wake), NotificationManager.IMPORTANCE_LOW),
            )
        }
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
