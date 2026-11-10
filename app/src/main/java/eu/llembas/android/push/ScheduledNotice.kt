package eu.llembas.android.push

import eu.llembas.android.api.PushPayload

/**
 * What a scheduled run's push asks the app to show, read from the server's payload alone
 * (services/schedule/notify.py `payload`): the schedule and the run it is about, whether it
 * failed, the actions the server offers, and -- from a server that sends its `notification` --
 * the words: [title] and [body] (the result's first lines) for an unlocked phone, and the
 * actions' [labels]. [publicTitle] / [publicBody] are read but not shown: they name the task,
 * and the lock screen gets nothing from the account. A payload without ids falls back to its `url` (`/scheduled/{schedule}?run={run}`),
 * as a browser would open it.
 */
data class ScheduledNotice(
    val scheduleId: String,
    val runId: String?,
    val failed: Boolean,
    /** The server asks whether to pause it (it failed several times in a row). */
    val askPause: Boolean,
    val open: Boolean,
    val pause: Boolean,
    val runAgain: Boolean = false,
    /** Empty when the server sent no `notification`: the app's fixed sentences are used then. */
    val title: String = "",
    val body: String = "",
    val publicTitle: String = "",
    val publicBody: String = "",
    val labels: Map<String, String> = emptyMap(),
) {
    /** One notification per run: a later push about the same run replaces it. */
    val id: Int get() = "scheduled:${runId ?: scheduleId}".hashCode()

    /** The server's label for [action] in the owner's language, or [fallback]. */
    fun label(action: String, fallback: String): String = labels[action]?.takeIf { it.isNotBlank() } ?: fallback

    companion object {
        const val KIND_RESULT = "schedule"
        const val KIND_FAILED = "schedule_failed"

        fun from(payload: PushPayload): ScheduledNotice? {
            if (payload.kind != KIND_RESULT && payload.kind != KIND_FAILED) return null
            val (urlSchedule, urlRun) = fromUrl(payload.url)
            val schedule = payload.scheduleId?.takeIf { it.isNotBlank() } ?: urlSchedule ?: return null
            val run = payload.runId?.takeIf { it.isNotBlank() } ?: urlRun
            val failed = payload.kind == KIND_FAILED
            val words = payload.notification
            val ask = words?.ask?.takeIf { it.isNotBlank() } ?: payload.ask
            val askPause = ask == "pause"
            // The notification's own actions first; an older payload named none: Open always,
            // Pause for a failure.
            val actions = words?.actions?.takeIf { it.isNotEmpty() }
                ?: payload.actions.ifEmpty { if (failed) listOf("open", "pause") else listOf("open") }
            return ScheduledNotice(
                scheduleId = schedule,
                runId = run,
                failed = failed,
                askPause = askPause,
                open = "open" in actions,
                pause = "pause" in actions || askPause,
                runAgain = "run_again" in actions,
                title = words?.title.orEmpty(),
                body = words?.body.orEmpty(),
                publicTitle = words?.public?.title.orEmpty(),
                publicBody = words?.public?.body.orEmpty(),
                labels = words?.labels.orEmpty(),
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
