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

    fun show(context: Context, instance: String, payload: PushPayload) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val chatId = payload.chatId ?: payload.url.removePrefix("/chat/").takeIf { payload.url.startsWith("/chat/") }
        val id = (chatId ?: payload.url).hashCode()
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_CHAT, chatId)
                .putExtra(EXTRA_INSTANCE, instance),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val channel = when (payload.kind) {
            "ask" -> LembasApp.CHANNEL_ASKS
            "report" -> LembasApp.CHANNEL_REPORTS
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
        // need the card itself, so they stay in the app.
        if (payload.kind == "ask" && payload.ask == "approval" && chatId != null && payload.interactionId != null) {
            builder.addAction(0, context.getString(R.string.card_allow_once), answer(context, instance, chatId, payload.interactionId, "allow", id))
            builder.addAction(0, context.getString(R.string.card_deny), answer(context, instance, chatId, payload.interactionId, "deny", id))
        }
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }

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
