package eu.llembas.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.widget.RemoteViews
import eu.llembas.android.R
import eu.llembas.android.assist.AssistActivity

/**
 * The home-screen widget: a bar that opens the assistant's sheet (Ask), the sheet listening (the
 * microphone), or the sheet at the last chat the assistant made. Nothing on it comes from the
 * account, so it never needs updating and shows nothing private wherever it is placed.
 */
class AskWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_ask).apply {
            setOnClickPendingIntent(R.id.widget_ask, open(context, REQUEST_ASK, Intent(context, AssistActivity::class.java).setAction(Intent.ACTION_ASSIST)))
            setOnClickPendingIntent(R.id.widget_mic, open(context, REQUEST_LISTEN, AssistActivity.listening(context)))
            setOnClickPendingIntent(R.id.widget_last, open(context, REQUEST_CONTINUE, AssistActivity.continuing(context)))
        }
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    private fun open(context: Context, request: Int, intent: Intent): PendingIntent = PendingIntent.getActivity(
        context,
        request,
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val REQUEST_ASK = 1
        private const val REQUEST_LISTEN = 2
        private const val REQUEST_CONTINUE = 3

        /**
         * Give every placed widget its buttons again. Nothing on it changes -- but the intents
         * behind the buttons carry the app's own mark ([eu.llembas.android.assist.OwnIntents]),
         * and a widget placed by an older version has buttons without it.
         */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(context, AskWidget::class.java)) }.getOrNull() ?: return
            if (ids.isNotEmpty()) AskWidget().onUpdate(context, manager, ids)
        }
    }
}

/**
 * The launcher icon's long-press shortcuts: Ask, Speak and the last assistant chat. Published from
 * code rather than `shortcuts.xml`, because a static shortcut names the package, and the test
 * build's is `….debug`; and so their labels follow the app's language.
 */
object Shortcuts {
    fun publish(context: Context) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val shortcuts = listOf(
            shortcut(
                context,
                "ask",
                R.string.tile_ask,
                R.string.tile_ask,
                R.drawable.ic_shortcut_ask,
                Intent(context, AssistActivity::class.java).setAction(Intent.ACTION_ASSIST),
            ),
            shortcut(context, "speak", R.string.widget_speak, R.string.widget_speak, R.drawable.ic_shortcut_mic, AssistActivity.listening(context)),
            shortcut(context, "continue", R.string.shortcut_last, R.string.assist_continue, R.drawable.ic_shortcut_history, AssistActivity.continuing(context)),
        )
        runCatching { manager.dynamicShortcuts = shortcuts }
    }

    private fun shortcut(context: Context, id: String, short: Int, long: Int, icon: Int, intent: Intent): ShortcutInfo = ShortcutInfo.Builder(context, id)
        .setShortLabel(context.getString(short))
        .setLongLabel(context.getString(long))
        .setIcon(Icon.createWithResource(context, icon))
        .setIntent(intent)
        .build()
}
