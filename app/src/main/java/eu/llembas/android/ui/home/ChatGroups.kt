package eu.llembas.android.ui.home

import eu.llembas.android.api.Chat
import eu.llembas.android.api.Folder
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/** A folder in the drawer, [depth] levels in, with the chats filed in it that are shown. */
data class FolderGroup(val folder: Folder, val depth: Int, val chats: List<Chat>)

/**
 * The drawer's list in its sections, as the web's sidebar has them: the pinned chats (wherever
 * they are filed), the folders as a tree with their chats, then the unfiled chats of today, the
 * earlier ones, and the agent chats (which run on a linked computer) on their own. Each keeps
 * the server's order, newest first.
 */
data class ChatGroups(
    val today: List<Chat> = emptyList(),
    val earlier: List<Chat> = emptyList(),
    val agents: List<Chat> = emptyList(),
    val pinned: List<Chat> = emptyList(),
    val folders: List<FolderGroup> = emptyList(),
) {
    /** Nothing to show: no chat in any section (a collapsed folder may hold some, so it counts). */
    val isEmpty: Boolean get() =
        today.isEmpty() && earlier.isEmpty() && agents.isEmpty() && pinned.isEmpty() && folders.all { it.chats.isEmpty() && !it.folder.collapsed }
}

/** The day a chat was last written in, where the phone is; null when the server did not say. */
fun Chat.day(zone: ZoneId): LocalDate? = (updatedAt ?: createdAt)?.let { stamp ->
    runCatching { OffsetDateTime.parse(stamp).atZoneSameInstant(zone).toLocalDate() }.getOrNull()
}

/**
 * The folders as the tree they are, each after its parent and siblings in the server's
 * `position` order, with its depth. A folder whose parent is not in the list counts as a top
 * one, so nothing is lost.
 */
fun folderTree(folders: List<Folder>): List<Pair<Folder, Int>> {
    val ids = folders.map { it.id }.toSet()
    val children = folders.groupBy { it.parentId?.takeIf { p -> p in ids } }
    val out = mutableListOf<Pair<Folder, Int>>()
    val seen = mutableSetOf<String>()
    fun walk(parent: String?, depth: Int) {
        children[parent].orEmpty().sortedBy { it.position }.forEach { f ->
            if (seen.add(f.id)) {
                out += f to depth
                walk(f.id, depth + 1)
            }
        }
    }
    walk(null, 0)
    return out
}

/**
 * Split [chats] for the drawer, keeping those whose title holds [query] (any case; blank keeps
 * all). A chat without a date counts as earlier; one filed in a folder the list does not have
 * counts as unfiled. A collapsed folder hides its chats and the folders inside it; while
 * searching, folders without a match are left out and none is collapsed.
 */
fun groupChats(chats: List<Chat>, query: String, today: LocalDate, zone: ZoneId, folders: List<Folder> = emptyList()): ChatGroups {
    val wanted = query.trim()
    val searching = wanted.isNotEmpty()
    val shown = if (!searching) chats else chats.filter { it.title.contains(wanted, ignoreCase = true) }
    val (pinned, rest) = shown.partition { it.pinned }
    val ids = folders.map { it.id }.toSet()
    val (filed, unfiled) = rest.partition { it.folderId != null && it.folderId in ids }
    val byFolder = filed.groupBy { it.folderId }
    val groups = mutableListOf<FolderGroup>()
    var hiddenBelow: Int? = null
    for ((folder, depth) in folderTree(folders)) {
        if (hiddenBelow != null && depth > hiddenBelow) continue
        hiddenBelow = null
        val own = byFolder[folder.id].orEmpty()
        if (searching) {
            if (own.isNotEmpty()) groups += FolderGroup(folder, depth, own)
            continue
        }
        groups += FolderGroup(folder, depth, if (folder.collapsed) emptyList() else own)
        if (folder.collapsed) hiddenBelow = depth
    }
    val (agents, plain) = unfiled.partition { it.isAgent }
    val (now, before) = plain.partition { it.day(zone) == today }
    return ChatGroups(today = now, earlier = before, agents = agents, pinned = pinned, folders = groups)
}
