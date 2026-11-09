package eu.llembas.android.push

import eu.llembas.android.api.PushPayload

/**
 * What a scheduled run's push asks the app to show, read from the server's payload alone
 * (services/schedule/notify.py `payload`): the schedule and the run it is about, whether it
 * failed, and the actions the server offers. A payload without ids falls back to its `url`
 * (`/scheduled/{schedule}?run={run}`), as a browser would open it.
 */
data class ScheduledNotice(
    val scheduleId: String,
    val runId: String?,
    val failed: Boolean,
    /** The server asks whether to pause it (it failed several times in a row). */
    val askPause: Boolean,
    val open: Boolean,
    val pause: Boolean,
) {
    /** One notification per run: a later push about the same run replaces it. */
    val id: Int get() = "scheduled:${runId ?: scheduleId}".hashCode()

    companion object {
        const val KIND_RESULT = "schedule"
        const val KIND_FAILED = "schedule_failed"

        fun from(payload: PushPayload): ScheduledNotice? {
            if (payload.kind != KIND_RESULT && payload.kind != KIND_FAILED) return null
            val (urlSchedule, urlRun) = fromUrl(payload.url)
            val schedule = payload.scheduleId?.takeIf { it.isNotBlank() } ?: urlSchedule ?: return null
            val run = payload.runId?.takeIf { it.isNotBlank() } ?: urlRun
            val failed = payload.kind == KIND_FAILED
            val askPause = payload.ask == "pause"
            // An older payload named no actions: Open always, Pause for a failure.
            val actions = payload.actions.ifEmpty { if (failed) listOf("open", "pause") else listOf("open") }
            return ScheduledNotice(
                scheduleId = schedule,
                runId = run,
                failed = failed,
                askPause = askPause,
                open = "open" in actions,
                pause = "pause" in actions || askPause,
            )
        }

        /** `/scheduled/{schedule}?run={run}` → the two ids; nulls for anything else. */
        fun fromUrl(url: String): Pair<String?, String?> {
            if (!url.startsWith("/scheduled/")) return null to null
            val rest = url.removePrefix("/scheduled/")
            val schedule = rest.substringBefore('?').substringBefore('/').takeIf { it.isNotBlank() }
            val run = rest.substringAfter('?', "").split('&').firstOrNull { it.startsWith("run=") }?.removePrefix("run=")?.takeIf { it.isNotBlank() }
            return schedule to run
        }
    }
}
