package eu.llembas.android.notify

/** One notification, as the question carries it. */
data class Notice(val app: String, val title: String, val text: String, val at: Long)

/**
 * Notification summaries, the half every build has: how the notifications are written into a
 * question, and when a question asks for them. Reading them is [NotificationAccess]'s, which only
 * the `notifications` build can do.
 */
object NotificationReader {
    /** The notifications as the question's attachment of text: one block per notification. */
    fun asText(notices: List<Notice>, now: Long = System.currentTimeMillis()): String = notices.joinToString("\n\n") { n ->
        val minutes = ((now - n.at) / 60_000).coerceAtLeast(0)
        val ago = if (minutes < 60) "${minutes}m ago" else "${minutes / 60}h ago"
        listOf("[${n.app} · $ago] ${n.title}".trim(), n.text).filter { it.isNotBlank() }.joinToString("\n")
    }.take(MAX_CHARS)

    /**
     * Whether a question asks about the notifications: one of [phrases] (in the app's language,
     * "my notifications" and the like) is in it.
     */
    fun asksAbout(question: String, phrases: List<String>): Boolean {
        val q = question.lowercase()
        return phrases.map { it.trim().lowercase() }.any { it.isNotEmpty() && it in q }
    }

    private const val MAX_CHARS = 12_000
}
