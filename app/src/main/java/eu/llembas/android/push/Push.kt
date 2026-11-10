package eu.llembas.android.push

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import eu.llembas.android.LembasApp
import eu.llembas.android.MainActivity
import eu.llembas.android.R
import eu.llembas.android.api.CardAnswer
import eu.llembas.android.api.LembasJson
import eu.llembas.android.api.PushPayload
import eu.llembas.android.auth.Instance
import eu.llembas.android.lembas
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/** Work that outlives a callback: a registration, a notification's button. */
private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Notifications without Google: UnifiedPush. The server already sends Web Push (RFC 8291,
 * signed with its VAPID key) to browsers, and a UnifiedPush endpoint *is* a Web Push endpoint,
 * so the server sends to this phone with the code it sends to a browser with. The distributor
 * (ntfy, or any other) carries the encrypted message; only this app can read it.
 *
 * One registration per instance, named by the instance's id.
 */
object Push {
    fun distributors(context: Context): List<String> = UnifiedPush.getDistributors(context)

    /** Pick (or reuse) a distributor and register this instance with it. */
    fun enable(activity: Activity, instance: Instance, done: (Boolean) -> Unit) {
        background.launch {
            val key = runCatching { activity.lembas.instances.clientFor(instance).pushKey().key }.getOrNull()
            activity.runOnUiThread {
                UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { ok ->
                    if (ok) {
                        UnifiedPush.register(activity, instance.id, activity.getString(R.string.app_name), key?.takeIf { it.length == 87 })
                    }
                    done(ok)
                }
            }
        }
    }

    fun disable(context: Context, instance: Instance) {
        UnifiedPush.unregister(context, instance.id)
        background.launch { runCatching { context.lembas.instances.clientFor(instance).forgetPush() } }
    }

    suspend fun instance(context: Context, id: String): Instance? = context.lembas.instances.instances.first().firstOrNull { it.id == id }
}

class LembasPushService : PushService() {
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val keys = endpoint.pubKeySet ?: return
        background.launch {
            val found = Push.instance(this@LembasPushService, instance) ?: return@launch
            runCatching { lembas.instances.clientFor(found).registerPush(endpoint.url, keys.pubKey, keys.auth) }
        }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        if (!message.decrypted) return
        val payload = runCatching {
            LembasJson.decodeFromString(PushPayload.serializer(), message.content.toString(Charsets.UTF_8))
        }.getOrNull() ?: return
        Notifications.show(this, instance, payload)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) = Unit

    override fun onUnregistered(instance: String) {
        background.launch {
            val found = Push.instance(this@LembasPushService, instance) ?: return@launch
            runCatching { lembas.instances.clientFor(found).forgetPush() }
        }
    }
}

object Notifications {
    const val EXTRA_CHAT = "eu.llembas.android.CHAT"
    const val EXTRA_INSTANCE = "eu.llembas.android.INSTANCE"

    const val EXTRA_SCHEDULE = "eu.llembas.android.SCHEDULE"
    const val EXTRA_RUN = "eu.llembas.android.RUN"

    fun show(context: Context, instance: String, payload: PushPayload) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (payload.kind == "schedule" || payload.kind == "schedule_failed") return scheduled(context, instance, payload)
        val chatId = payload.chatId ?: payload.url.removePrefix("/chat/").takeIf { payload.url.startsWith("/chat/") }
        val id = (chatId ?: payload.url).hashCode()
        val open = opener(context, instance, id, chatId = chatId)
        val channel = when (payload.kind) {
            "ask" -> LembasApp.CHANNEL_ASKS
            else -> LembasApp.CHANNEL_REPLIES
        }
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(payload.title)
            .setContentText(payload.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(payload.body))
            .setAutoCancel(true)
            .setContentIntent(open)
            .setCategory(if (payload.kind == "ask") Notification.CATEGORY_REMINDER else Notification.CATEGORY_MESSAGE)
            // The body can name a command or a file; on a locked screen only the title shows.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        // An approval can be answered from here: once, or no. "Always" and a corrected command
        // need the card itself, so they stay in the app. Either answer asks for the phone to be
        // unlocked first (Android 12+): a locked phone in somebody else's hand must not say yes
        // to a command -- nor no to a run that waits on it, a scheduled one included.
        if (payload.kind == "ask" && payload.ask == "approval" && chatId != null && payload.interactionId != null) {
            builder.addAction(unlocked(context.getString(R.string.card_allow_once), answer(context, instance, chatId, payload.interactionId, "allow", id)))
            builder.addAction(unlocked(context.getString(R.string.card_deny), answer(context, instance, chatId, payload.interactionId, "deny", id)))
        }
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    /**
     * A scheduled run's result or failure, on its own channel, built from the server's payload
     * alone ([ScheduledNotice]). Unlocked: the task's name and the result's first lines, as the
     * server cut them (BigTextStyle opens them). On the lock screen only the public version: the
     * app's name and "A scheduled task has a result." (or failed) -- nothing from the account, so
     * the server's `public` (which names the task) is not used. A tap and Open go to the run;
     * Run again starts it again, once the phone is unlocked; Pause is there when offered.
     */
    private fun scheduled(context: Context, instance: String, payload: PushPayload) {
        val notice = ScheduledNotice.from(payload) ?: return
        val channel = if (notice.failed) LembasApp.CHANNEL_SCHEDULED_FAILURES else LembasApp.CHANNEL_SCHEDULED
        val fixed = context.getString(if (notice.failed) R.string.scheduled_failed_public else R.string.scheduled_result_public)
        val open = opener(context, instance, notice.id, chatId = null, scheduleId = notice.scheduleId, runId = notice.runId)
        // The lock screen gets nothing from the account (Working-notes rule 5): the app's name
        // and a fixed sentence, never the task's name the server offers in `public`.
        val public = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(fixed)
            .build()
        val title = notice.title.ifBlank { payload.title.ifBlank { context.getString(R.string.scheduled) } }
        val body = notice.body.ifBlank { payload.body.ifBlank { fixed } }
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(open)
            .setCategory(if (notice.failed) Notification.CATEGORY_ERROR else Notification.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
        if (notice.open) builder.addAction(0, notice.label("open", context.getString(R.string.viewer_open)), open)
        if (notice.runAgain) {
            val again = scheduleAction(context, instance, notice.scheduleId, notice.id, ScheduleActionReceiver.RUN_AGAIN)
            builder.addAction(unlocked(notice.label("run_again", context.getString(R.string.schedule_run_again)), again))
        }
        if (notice.pause) {
            val pause = scheduleAction(context, instance, notice.scheduleId, notice.id, ScheduleActionReceiver.PAUSE)
            builder.addAction(0, notice.label("pause", context.getString(R.string.schedule_pause)), pause)
        }
        NotificationManagerCompat.from(context).notify(notice.id, builder.build())
    }

    /** A notification action Android runs only once the phone is unlocked. */
    private fun unlocked(title: String, intent: PendingIntent): NotificationCompat.Action =
        NotificationCompat.Action.Builder(0, title, intent).setAuthenticationRequired(true).build()

    private fun opener(context: Context, instance: String, id: Int, chatId: String?, scheduleId: String? = null, runId: String? = null): PendingIntent =
        PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_CHAT, chatId)
                .putExtra(EXTRA_SCHEDULE, scheduleId)
                .putExtra(EXTRA_RUN, runId)
                .putExtra(EXTRA_INSTANCE, instance),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun scheduleAction(context: Context, instance: String, schedule: String, notification: Int, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            (action + schedule).hashCode(),
            Intent(context, ScheduleActionReceiver::class.java)
                .putExtra("instance", instance)
                .putExtra("schedule", schedule)
                .putExtra("action", action)
                .putExtra("notification", notification),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun answer(context: Context, instance: String, chat: String, card: String, verdict: String, notification: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            (card + verdict).hashCode(),
            Intent(context, CardActionReceiver::class.java)
                .putExtra("instance", instance)
                .putExtra("chat", chat)
                .putExtra("card", card)
                .putExtra("verdict", verdict)
                .putExtra("notification", notification),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

/** A notification's Allow once / Deny: the same answer the card would send. */
class CardActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val instance = intent.getStringExtra("instance") ?: return pending.finish()
        val chat = intent.getStringExtra("chat") ?: return pending.finish()
        val card = intent.getStringExtra("card") ?: return pending.finish()
        val verdict = intent.getStringExtra("verdict") ?: return pending.finish()
        background.launch {
            try {
                val found = Push.instance(context, instance)
                if (found != null) {
                    // A 409 means it was answered elsewhere first; either way the card is settled.
                    runCatching { context.lembas.instances.clientFor(found).answer(chat, card, CardAnswer(verdict = verdict)) }
                }
                NotificationManagerCompat.from(context).cancel(intent.getIntExtra("notification", 0))
            } finally {
                pending.finish()
            }
        }
    }
}

/** A scheduled task's Pause or Run again, from its notification. */
class ScheduleActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val instance = intent.getStringExtra("instance") ?: return pending.finish()
        val schedule = intent.getStringExtra("schedule") ?: return pending.finish()
        val action = intent.getStringExtra("action") ?: PAUSE
        background.launch {
            try {
                Push.instance(context, instance)?.let { found ->
                    val client = context.lembas.instances.clientFor(found)
                    // A failure (gone, busy) is the server's to say on the run page; the
                    // notification goes either way, and a new run sends its own.
                    runCatching { if (action == RUN_AGAIN) client.runScheduleNow(schedule) else client.pauseSchedule(schedule, true) }
                }
                NotificationManagerCompat.from(context).cancel(intent.getIntExtra("notification", 0))
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val PAUSE = "pause"
        const val RUN_AGAIN = "run_again"
    }
}
