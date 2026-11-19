package eu.llembas.android

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.core.content.ContextCompat
import eu.llembas.android.api.EventsHub
import eu.llembas.android.api.Frame
import eu.llembas.android.api.StreamClient
import eu.llembas.android.auth.InstanceStore
import eu.llembas.android.wake.AppVisibility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow

/** The process: one store of instances, and the notification channels, made once. */
class LembasApp : Application() {
    lateinit var instances: InstanceStore
        private set

    /** The one connection to the person's events route, shared by all that watch it. */
    val events = EventsHub(CoroutineScope(SupervisorJob() + Dispatchers.Default))

    /** The events route of the instance in use, as its frames; null when not signed in. */
    suspend fun eventFrames(): Flow<Frame>? {
        val instance = instances.current() ?: return null
        val api = instances.clientFor(instance)
        return events.frames(instance.id) { StreamClient(api).watch { api.events() } }
    }

    /** Whether a new request with the account's files may start: the app in front, the phone unlocked. */
    val gate = InstanceGate { getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true }

    override fun onCreate() {
        super.onCreate()
        instances = InstanceStore(this)
        // Copies of the account's files left by a process that was killed. A viewer's own are
        // nobody's now; one another app was handed may still be read there (a mail being
        // written), so those go by their age only.
        eu.llembas.android.ui.files.FileCache.atStart(this)
        // The gate hears the lock from the screen going off and the unlock; the activities below say the rest.
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    gate.look()
                }
            },
            IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Whether the app is on screen: the wake word may open the assistant's sheet as an
        // activity only then (Android blocks one started from the background, silently).
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    AppVisibility.activityStarted()
                    gate.activityStarted()
                }

                override fun onActivityStopped(activity: Activity) {
                    AppVisibility.activityStopped()
                    gate.activityStopped()
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

                override fun onActivityResumed(activity: Activity) {
                    gate.look()
                }

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_REPLIES, getString(R.string.channel_replies), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_ASKS, getString(R.string.channel_asks), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CHANNEL_SCHEDULED, getString(R.string.channel_scheduled), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_SCHEDULED_FAILURES, getString(R.string.channel_scheduled_failures), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CHANNEL_REPORTS, getString(R.string.channel_reports), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_REPORTS_HIGH, getString(R.string.channel_reports_high), NotificationManager.IMPORTANCE_HIGH),
            ).onEach { it.lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE } +
                // A new version of the app says nothing about the account.
                NotificationChannel(CHANNEL_UPDATES, getString(R.string.channel_updates), NotificationManager.IMPORTANCE_LOW),
        )
        // 0.1's channel for its reports goes rather than sit empty in the system's settings;
        // the reports of LLeMbas 2.0 have a channel of their own (CHANNEL_REPORTS).
        manager.deleteNotificationChannel("reports")
        // The launcher's long-press shortcuts, in the app's language as it is now.
        eu.llembas.android.widget.Shortcuts.publish(this)
        eu.llembas.android.widget.AskWidget.refresh(this)
    }

    companion object {
        const val CHANNEL_REPLIES = "replies"
        const val CHANNEL_ASKS = "asks"
        const val CHANNEL_SCHEDULED = "scheduled_results"
        const val CHANNEL_SCHEDULED_FAILURES = "scheduled_failures"
        const val CHANNEL_REPORTS = "reports_filed"
        const val CHANNEL_REPORTS_HIGH = "reports_important"
        const val CHANNEL_UPDATES = "updates"
    }
}

val android.content.Context.lembas: LembasApp get() = applicationContext as LembasApp
