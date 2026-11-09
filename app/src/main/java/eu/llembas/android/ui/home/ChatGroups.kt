package eu.llembas.android.ui.home

import eu.llembas.android.api.Chat
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * The drawer's list in its sections: the chats of today, the earlier ones, and the agent chats
 * (which run on a linked computer) on their own. Each keeps the server's order, newest first.
 */
data class ChatGroups(val today: List<Chat> = emptyList(), val earlier: List<Chat> = emptyList(), val agents: List<Chat> = emptyList()) {
    val isEmpty: Boolean get() = today.isEmpty() && earlier.isEmpty() && agents.isEmpty()
}

/** The day a chat was last written in, where the phone is; null when the server did not say. */
fun Chat.day(zone: ZoneId): LocalDate? = (updatedAt ?: createdAt)?.let { stamp ->
    runCatching { OffsetDateTime.parse(stamp).atZoneSameInstant(zone).toLocalDate() }.getOrNull()
}

/**
 * Split [chats] for the drawer, keeping those whose title holds [query] (any case; blank keeps
 * all). A chat without a date counts as earlier.
 */
fun groupChats(chats: List<Chat>, query: String, today: LocalDate, zone: ZoneId): ChatGroups {
    val wanted = query.trim()
    val shown = if (wanted.isEmpty()) chats else chats.filter { it.title.contains(wanted, ignoreCase = true) }
    val (agents, plain) = shown.partition { it.isAgent }
    val (now, before) = plain.partition { it.day(zone) == today }
    return ChatGroups(today = now, earlier = before, agents = agents)
}
