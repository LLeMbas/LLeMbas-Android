package eu.llembas.android.notify

import android.app.KeyguardManager
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat

/**
 * Notification summaries on request: the assistant reads the notifications on the phone only when
 * the person asks it to ("summarise my notifications", or the sheet's chip), and sends their text
 * with that one question. Off by default, twice over: Android's own notification-access switch
 * (which only the person can turn on, in the system's settings) and Settings › Assistant's.
 *
 * The listener keeps nothing: it is how Android lets an app read the notifications that are up
 * right now ([activeNotifications]), and nothing is read until a question asks for it. Never over
 * the lock screen ([read] returns nothing while the phone is locked).
 */
class LembasNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        current = this
    }

    override fun onListenerDisconnected() {
        if (current === this) current = null
        super.onListenerDisconnected()
    }

    companion object {
        @Volatile
        var current: LembasNotificationListener? = null
            private set
    }
}

/** One notification, as the question carries it. */
data class Notice(val app: String, val title: String, val text: String, val at: Long)

object NotificationReader {
    /** Whether the person gave LLeMbas notification access in the system's settings. */
    fun granted(context: Context): Boolean = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    /** The system's page for this app's notification access (the detail page where Android has one). */
    fun settingsIntent(context: Context): Intent = if (Build.VERSION.SDK_INT >= 30) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(context, LembasNotificationListener::class.java).flattenToString())
    } else {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The notifications up now, newest first -- or none, when locked or not allowed. */
    fun read(context: Context): List<Notice> {
        if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return emptyList()
        val listener = LembasNotificationListener.current ?: return emptyList()
        val shown = runCatching { listener.activeNotifications }.getOrNull().orEmpty()
        return shown
            .filter { it.packageName != context.packageName && !it.isOngoing && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .sortedByDescending { it.postTime }
            .mapNotNull { notice(context, it) }
            .take(MAX_NOTICES)
    }

    private fun notice(context: Context, sbn: StatusBarNotification): Notice? {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n").orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()
            ?.ifBlank { null } ?: lines
        if (title.isBlank() && text.isBlank()) return null
        val app = runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        return Notice(app, title, text.take(MAX_TEXT), sbn.postTime)
    }

    /** The notifications as the question's attachment of text: one block per notification. */
    fun asText(notices: List<Notice>, now: Long = System.currentTimeMillis()): String = notices.joinToString("\n\n") { n ->
        val minutes = ((now - n.at) / 60_000).coerceAtLeast(0)
        val ago = if (minutes < 60) "${minutes}m ago" else "${minutes / 60}h ago"
        listOf("[${n.app} · $ago] ${n.title}".trim(), n.text).filter { it.isNotBlank() }.joinToString("\n")
    }.take(MAX_CHARS)

    /**
     * Whether a question asks about the notifications: one of [phrases] (in the app's language,
     * "my notifications" and the like) is in it.
     */
    fun asksAbout(question: String, phrases: List<String>): Boolean {
        val q = question.lowercase()
        return phrases.map { it.trim().lowercase() }.any { it.isNotEmpty() && it in q }
    }

    private const val MAX_NOTICES = 40
    private const val MAX_TEXT = 600
    private const val MAX_CHARS = 12_000
}
