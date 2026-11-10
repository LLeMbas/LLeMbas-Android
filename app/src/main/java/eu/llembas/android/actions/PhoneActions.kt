package eu.llembas.android.actions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.view.KeyEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/** How a phone action ended, as the tool's result says it. */
enum class ActionStatus(val wire: String) { Done("done"), Declined("declined"), Failed("failed") }

data class ActionOutcome(val status: ActionStatus, val message: String = "")

/** The arguments of an action, read leniently: a model writes numbers as strings now and then. */
class Args(val json: JsonObject) {
    private val o get() = json

    fun str(key: String): String = (o[key] as? JsonPrimitive)?.takeIf { it.isString || it.booleanOrNull == null }?.content.orEmpty()

    fun int(key: String): Int? = (o[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toIntOrNull() }

    fun long(key: String): Long? = (o[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.content.toLongOrNull() }

    fun bool(key: String): Boolean? = (o[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.content.toBooleanStrictOrNull() }

    fun obj(key: String): Args? = (o[key] as? JsonObject)?.let(::Args)

    fun list(key: String): List<JsonElement> = (o[key] as? JsonArray).orEmpty()

    fun place(key: String): Place? = when (val v = o[key]) {
        is JsonObject -> placeOf(v)
        is JsonPrimitive -> v.content.takeIf { it.isNotBlank() }?.let { Place(query = it) }
        else -> null
    }

    fun places(key: String): List<Place> = list(key).mapNotNull { e ->
        when (e) {
            is JsonObject -> placeOf(e)
            is JsonPrimitive -> e.content.takeIf { it.isNotBlank() }?.let { Place(query = it) }
            else -> null
        }
    }

    private fun placeOf(v: JsonObject): Place {
        fun d(k: String) = (v[k] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }
        return Place(
            name = (v["name"] as? JsonPrimitive)?.content.orEmpty(),
            lat = d("lat"),
            lon = d("lon"),
            query = (v["query"] as? JsonPrimitive)?.content.orEmpty(),
        )
    }

    /** A time the model wrote: ISO 8601 with an offset, or local (the phone's zone). */
    fun time(key: String): Long? {
        val raw = str(key).ifBlank { return null }
        return runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(raw).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
    }
}

/**
 * The phone actions a model may propose (`phone_action`), carried out with Android's standard
 * intents once the person tapped Do it on the card. Each one either opens the app that does it,
 * with everything filled in (an alarm, an event, a draft -- the person sends a message or saves
 * an event there themselves), or does a small thing at once (the torch, play/pause).
 *
 * Nothing here runs without the card's Do it, and none of it from the lock screen. A model's
 * words are data: a URL opens only for http and https, a broadcast never names a system action,
 * a phone number goes to the dialler and is never called.
 */
object PhoneActions {
    /** Media keys the `media` action sends. */
    private val MEDIA = mapOf(
        "play_pause" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        "play" to KeyEvent.KEYCODE_MEDIA_PLAY,
        "pause" to KeyEvent.KEYCODE_MEDIA_PAUSE,
        "next" to KeyEvent.KEYCODE_MEDIA_NEXT,
        "previous" to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        "stop" to KeyEvent.KEYCODE_MEDIA_STOP,
    )

    /** Settings panels, by the action's name. Panels (Android 10+) float over the app; the rest are pages. */
    private val PANELS = mapOf(
        "wifi" to Settings.Panel.ACTION_WIFI,
        "internet" to Settings.Panel.ACTION_INTERNET_CONNECTIVITY,
        "nfc" to Settings.Panel.ACTION_NFC,
        "volume" to Settings.Panel.ACTION_VOLUME,
        "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
        "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
        "display" to Settings.ACTION_DISPLAY_SETTINGS,
        "battery" to Intent.ACTION_POWER_USAGE_SUMMARY,
        "airplane" to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
        "do_not_disturb" to Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS,
    )

    /** A broadcast an automation app (Tasker, Automate, MacroDroid) listens for: never a system one. */
    fun broadcastAllowed(action: String): Boolean =
        action.isNotBlank() && action.length <= 200 && !action.startsWith("android.") && !action.startsWith("com.android.") &&
            Regex("^[A-Za-z0-9_.]+$").matches(action)

    /** A URL the phone may open for a model: web pages only. */
    fun urlAllowed(url: String): Boolean = Uri.parse(url.trim()).scheme?.lowercase() in setOf("http", "https")

    /**
     * The intent for an action that is one intent (everything but navigation, the torch and media
     * keys), or null when [action] is not one of these.
     */
    fun intentFor(context: Context, action: String, a: Args): Intent? = when (action) {
        "alarm" -> Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, a.int("hour") ?: 7)
            .putExtra(AlarmClock.EXTRA_MINUTES, a.int("minute") ?: 0)
            .apply { a.str("label").takeIf { it.isNotBlank() }?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } }
            .apply {
                val days = a.list("days").mapNotNull { (it as? JsonPrimitive)?.content?.let(::calendarDay) }
                if (days.isNotEmpty()) putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
            }

        "timer" -> Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, (a.int("seconds") ?: 60).coerceIn(1, 86_400))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            .apply { a.str("label").takeIf { it.isNotBlank() }?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } }

        "calendar_event" -> Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, a.str("title"))
            .apply {
                a.time("start")?.let { putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
                a.time("end")?.let { putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
                if (a.bool("all_day") == true) putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
                a.str("location").takeIf { it.isNotBlank() }?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
                a.str("description").takeIf { it.isNotBlank() }?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
            }

        "sms_draft" -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(a.str("to"))))
            .putExtra("sms_body", a.str("body"))

        "email_draft" -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(
                Intent.EXTRA_EMAIL,
                a.list("to").mapNotNull {
                    (it as? JsonPrimitive)?.content
                }.ifEmpty { listOf(a.str("to")) }.filter { it.isNotBlank() }.toTypedArray(),
            )
            .putExtra(Intent.EXTRA_SUBJECT, a.str("subject"))
            .putExtra(Intent.EXTRA_TEXT, a.str("body"))

        // The dialler with the number in it: the person presses call.
        "dial" -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(a.str("number"))))

        "open_url" -> a.str("url").trim().takeIf(::urlAllowed)?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)) }

        "open_app" -> appIntent(context, a.str("package"), a.str("name"))

        "settings_panel" -> PANELS[a.str("panel")]?.let { Intent(it) }

        "automation" -> a.str("action").takeIf(::broadcastAllowed)?.let { name ->
            Intent(name).apply {
                a.str("package").takeIf { it.isNotBlank() }?.let { setPackage(it) }
                a.obj("extras")?.let { extras ->
                    // Strings only: an automation app reads its variables as text.
                    extras.json.forEach { (k, v) -> (v as? JsonPrimitive)?.let { putExtra(k, it.content) } }
                }
            }
        }

        else -> null
    }

    /** An app by its package, else by the name the launcher shows (the first app whose label matches). */
    fun appIntent(context: Context, pkg: String, name: String): Intent? {
        val pm = context.packageManager
        pkg.takeIf { it.isNotBlank() }?.let { p -> pm.getLaunchIntentForPackage(p)?.let { return it } }
        if (name.isBlank()) return null
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = runCatching { pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL) }.getOrDefault(emptyList())
        val wanted = name.trim().lowercase()
        val found = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == wanted }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(wanted) }
            ?: return null
        return pm.getLaunchIntentForPackage(found.activityInfo.packageName)
    }

    /** The torch on or off (or the other way round), through the camera service; no permission needed. */
    fun torch(context: Context, on: Boolean?): ActionOutcome {
        val cameras = context.getSystemService(CameraManager::class.java) ?: return ActionOutcome(ActionStatus.Failed, "no camera")
        val id = cameras.cameraIdList.firstOrNull { cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return ActionOutcome(ActionStatus.Failed, "this phone has no torch")
        val target = on ?: !torchOn
        return runCatching {
            cameras.setTorchMode(id, target)
            torchOn = target
            ActionOutcome(ActionStatus.Done, if (target) "torch on" else "torch off")
        }.getOrElse { ActionOutcome(ActionStatus.Failed, it.message.orEmpty()) }
    }

    @Volatile
    private var torchOn = false

    /** A media key to whatever is playing. */
    fun media(context: Context, key: String): ActionOutcome {
        val code = MEDIA[key] ?: return ActionOutcome(ActionStatus.Failed, "unknown media key: $key")
        val audio = context.getSystemService(AudioManager::class.java) ?: return ActionOutcome(ActionStatus.Failed, "no audio")
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return ActionOutcome(ActionStatus.Done, key)
    }

    /** Start [intent] as a new task; whether an app took it. */
    fun launch(context: Context, intent: Intent, broadcast: Boolean = false): ActionOutcome = try {
        if (broadcast) {
            context.sendBroadcast(intent)
        } else {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        ActionOutcome(ActionStatus.Done)
    } catch (e: ActivityNotFoundException) {
        ActionOutcome(ActionStatus.Failed, "no app on this phone does that")
    } catch (e: SecurityException) {
        ActionOutcome(ActionStatus.Failed, e.message.orEmpty())
    }

    private fun calendarDay(day: String): Int? = when (day.lowercase().take(3)) {
        "sun" -> java.util.Calendar.SUNDAY
        "mon" -> java.util.Calendar.MONDAY
        "tue" -> java.util.Calendar.TUESDAY
        "wed" -> java.util.Calendar.WEDNESDAY
        "thu" -> java.util.Calendar.THURSDAY
        "fri" -> java.util.Calendar.FRIDAY
        "sat" -> java.util.Calendar.SATURDAY
        else -> null
    }
}
