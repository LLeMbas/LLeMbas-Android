package eu.llembas.android.assist

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import eu.llembas.android.R

/**
 * A Quick Settings tile that opens the assistant's sheet: one more way in besides the power
 * button. It is a button, not a switch -- always shown as ready (`STATE_INACTIVE`), never as
 * "on", and the system's default (`STATE_UNAVAILABLE` on some phones until the first update)
 * would grey it out. Over the lock screen the sheet opens there and asks to unlock before it
 * sends anything, as from any other way in.
 */
class AskTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(R.string.tile_ask)
            updateTile()
        }
    }

    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            // Android 14 refuses the Intent overload; the PendingIntent one is the way now.
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
