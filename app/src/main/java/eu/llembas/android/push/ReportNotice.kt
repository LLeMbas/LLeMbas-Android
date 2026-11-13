package eu.llembas.android.push

import eu.llembas.android.api.PushPayload

/**
 * What a filed report's push asks the app to show (harness spec 3.8.0, `reports.md` §4:
 * notifications come from the report, not from the run). [title] and [summary] are a model's
 * words about the person's own things: for an unlocked phone only. The lock screen gets the
 * app's fixed words and nothing else. A payload without the id falls back to its `url`
 * (`/reports/{id}`), as a browser would open it.
 */
data class ReportNotice(val reportId: String, val title: String, val summary: String, val high: Boolean) {
    /** One notification per report: a later push about the same report replaces it. */
    val id: Int get() = "report:$reportId".hashCode()

    companion object {
        const val KIND = "report"

        fun from(payload: PushPayload): ReportNotice? {
            if (payload.kind != KIND) return null
            val id = payload.reportId?.takeIf { it.isNotBlank() } ?: fromUrl(payload.url) ?: return null
            // The report's own words are in the payload's `notification` (the payload's top is
            // what an older client would show: the app's name and "New report"). A payload
            // without one is read by its top.
            val words = payload.notification
            return ReportNotice(
                reportId = id,
                title = words?.title ?: payload.title,
                summary = words?.body ?: payload.body,
                high = (words?.importance?.ifBlank { null } ?: payload.importance) == "high",
            )
        }

        /** `/reports/{id}` → the id; null for anything else. */
        fun fromUrl(url: String): String? {
            if (!url.startsWith("/reports/")) return null
            return url.removePrefix("/reports/").substringBefore('?').substringBefore('/').takeIf { it.isNotBlank() }
        }
    }
}
