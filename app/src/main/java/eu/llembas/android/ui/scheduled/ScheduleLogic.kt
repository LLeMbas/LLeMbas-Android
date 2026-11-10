package eu.llembas.android.ui.scheduled

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How a run's status is drawn: its colour family, not its words. */
enum class Tone { Good, Quiet, Bad, Waiting, Neutral }

object ScheduleLogic {
    fun tone(status: String): Tone = when (status) {
        "ok" -> Tone.Good
        "silent", "unchanged" -> Tone.Quiet
        "failed", "missed" -> Tone.Bad
        "waiting", "running" -> Tone.Waiting
        else -> Tone.Neutral
    }

    /**
     * A moment the server named (ISO 8601 with an offset), where the phone is, short:
     * "Tue 10 Mar, 08:00". Null for nothing or for something that is not a time.
     */
    fun moment(iso: String?, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String? {
        if (iso.isNullOrBlank()) return null
        val at = runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(zone) }.getOrNull() ?: return null
        return DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", locale).format(at)
    }

    /** At most [n] upcoming runs, as the preview lists them; unreadable ones are left out. */
    fun upcoming(runs: List<String>, n: Int = 5, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): List<String> =
        runs.mapNotNull { moment(it, zone, locale) }.take(n)
}
