package eu.llembas.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import eu.llembas.android.auth.InstanceStore

/** The process: one store of instances, and the notification channels, made once. */
class LembasApp : Application() {
    lateinit var instances: InstanceStore
        private set

    override fun onCreate() {
        super.onCreate()
        instances = InstanceStore(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_REPLIES, getString(R.string.channel_replies), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_ASKS, getString(R.string.channel_asks), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CHANNEL_SCHEDULED, getString(R.string.channel_scheduled), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_SCHEDULED_FAILURES, getString(R.string.channel_scheduled_failures), NotificationManager.IMPORTANCE_HIGH),
            ).onEach { it.lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE },
        )
        // LLeMbas 2.0 has no reports, so 0.1's channel for them goes rather than sit empty in
        // the system's settings.
        manager.deleteNotificationChannel("reports")
    }

    companion object {
        const val CHANNEL_REPLIES = "replies"
        const val CHANNEL_ASKS = "asks"
        const val CHANNEL_SCHEDULED = "scheduled_results"
        const val CHANNEL_SCHEDULED_FAILURES = "scheduled_failures"
    }
}

val android.content.Context.lembas: LembasApp get() = applicationContext as LembasApp
