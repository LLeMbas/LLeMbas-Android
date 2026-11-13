package eu.llembas.android

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle
import eu.llembas.android.auth.InstanceStore
import eu.llembas.android.wake.AppVisibility

/** The process: one store of instances, and the notification channels, made once. */
class LembasApp : Application() {
    lateinit var instances: InstanceStore
        private set

    override fun onCreate() {
        super.onCreate()
        instances = InstanceStore(this)
        // Whether the app is on screen: the wake word may open the assistant's sheet as an
        // activity only then (Android blocks one started from the background, silently).
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) = AppVisibility.activityStarted()

                override fun onActivityStopped(activity: Activity) = AppVisibility.activityStopped()

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

                override fun onActivityResumed(activity: Activity) = Unit

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
            ).onEach { it.lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE } +
                // A new version of the app says nothing about the account.
                NotificationChannel(CHANNEL_UPDATES, getString(R.string.channel_updates), NotificationManager.IMPORTANCE_LOW),
        )
        // 0.1's channel for its reports goes rather than sit empty in the system's settings;
        // the reports of LLeMbas 2.0 have a channel of their own (CHANNEL_REPORTS).
        manager.deleteNotificationChannel("reports")
        // The launcher's long-press shortcuts, in the app's language as it is now.
        eu.llembas.android.widget.Shortcuts.publish(this)
    }

    companion object {
        const val CHANNEL_REPLIES = "replies"
        const val CHANNEL_ASKS = "asks"
        const val CHANNEL_SCHEDULED = "scheduled_results"
        const val CHANNEL_SCHEDULED_FAILURES = "scheduled_failures"
        const val CHANNEL_REPORTS = "reports_filed"
        const val CHANNEL_UPDATES = "updates"
    }
}

val android.content.Context.lembas: LembasApp get() = applicationContext as LembasApp
