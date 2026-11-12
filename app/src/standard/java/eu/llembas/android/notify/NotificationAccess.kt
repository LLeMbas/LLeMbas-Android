package eu.llembas.android.notify

import android.content.Context
import android.content.Intent

/**
 * The standard builds cannot read the phone's notifications: they declare no notification
 * listener at all, because Google Play Protect blocks installing an app from outside the Play
 * Store that does. Notification summaries are in the `notifications` build -- the same app, the
 * same signature, with the listener -- and Settings says so. Everything else about them is the
 * same code in both, which here simply never has anything to read.
 */
object NotificationAccess {
    const val AVAILABLE = false

    fun granted(context: Context): Boolean = false

    fun settingsIntent(context: Context): Intent? = null

    fun read(context: Context): List<Notice> = emptyList()
}
