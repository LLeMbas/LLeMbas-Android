package eu.llembas.android.assist

import android.content.Context
import android.content.Intent
import java.security.SecureRandom

/**
 * Telling the app's own intents from anybody's. [AssistActivity] is exported -- Android's
 * `ACTION_ASSIST` and a headset's voice button must reach it -- so any app can start it with any
 * action and any extras. What only the app's own parts may ask for (open listening, continue the
 * last chat, turn the screen on, take a handed-over question) is therefore honoured only on an
 * intent that carries the app's own mark: a random value made once on this phone and kept in the
 * app's private storage. The widget's and the shortcuts' intents carry it inside a
 * `PendingIntent` or a shortcut, which no other app can read.
 */
object OwnIntents {
    private const val STORE = "own_intents"
    private const val KEY = "mark"
    private const val EXTRA_MARK = "eu.llembas.android.OWN"

    @Volatile
    private var cached: String? = null

    private fun mark(context: Context): String = cached ?: synchronized(this) {
        cached ?: run {
            val store = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            val kept = store.getString(KEY, null) ?: newMark().also { store.edit().putString(KEY, it).apply() }
            cached = kept
            kept
        }
    }

    private fun newMark(): String = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /** [intent], marked as the app's own. */
    fun own(context: Context, intent: Intent): Intent = intent.putExtra(EXTRA_MARK, mark(context))

    /** Whether [intent] was made by the app itself. */
    fun isOwn(context: Context, intent: Intent?): Boolean = sameMark(runCatching { intent?.getStringExtra(EXTRA_MARK) }.getOrNull(), mark(context))

    /** Compared in full, whatever differs: how long it takes says nothing about where. */
    internal fun sameMark(given: String?, kept: String): Boolean {
        if (given == null || given.length != kept.length) return false
        var differs = 0
        for (i in kept.indices) differs = differs or (given[i].code xor kept[i].code)
        return differs == 0
    }
}

/**
 * A question on its way from one sheet to the other -- the assistant's own sheet hands what was
 * typed or heard over the lock screen to [AssistActivity], which can ask Android to unlock. It
 * never travels in the intent: an intent's extras are whatever its sender says, and a question
 * found there would be asked on the account for any app that put it there. The question waits
 * here, in the app's own memory; the intent carries only the ticket, good once.
 */
object AssistHandOff {
    private var ticket: String? = null
    private var waiting: Held? = null

    /** Leave [question] here; the ticket to put on the intent. */
    @Synchronized
    fun leave(question: Held): String {
        val made = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        ticket = made
        waiting = question
        return made
    }

    /** The question left under [given], once; nothing for any other ticket, or none. */
    @Synchronized
    fun take(given: String?): Held? {
        val kept = ticket ?: return null
        if (given == null || !OwnIntents.sameMark(given, kept)) return null
        val question = waiting
        ticket = null
        waiting = null
        return question
    }
}
