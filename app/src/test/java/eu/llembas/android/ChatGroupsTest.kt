package eu.llembas.android

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import eu.llembas.android.api.Chat
import eu.llembas.android.api.Folder
import eu.llembas.android.ui.home.folderTree
import eu.llembas.android.ui.home.groupChats
import eu.llembas.android.ui.home.markedSnippet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ChatGroupsTest {
    private val zone = ZoneId.of("Europe/Bratislava")
    private val today = LocalDate.of(2026, 3, 10)

    private fun chat(id: String, title: String, updated: String?, kind: String = "chat") = Chat(id = id, title = title, kind = kind, updatedAt = updated)

    @Test
    fun todayEarlierAndAgentsKeepTheirOrder() {
        val chats = listOf(
            chat("a", "Bread", "2026-03-10T08:00:00+00:00"),
            chat("b", "Repo", "2026-03-10T07:00:00+00:00", kind = "agent"),
            chat("c", "Honey", "2026-03-09T12:00:00+00:00"),
            chat("d", "Old", null),
        )
        val groups = groupChats(chats, "", today, zone)
        assertEquals(listOf("a"), groups.today.map { it.id })
        assertEquals(listOf("c", "d"), groups.earlier.map { it.id })
        assertEquals(listOf("b"), groups.agents.map { it.id })
    }

    @Test
    fun theDayIsThePhones() {
        // 23:30 UTC on the 9th is already the 10th in Central Europe.
        val groups = groupChats(listOf(chat("a", "Late", "2026-03-09T23:30:00+00:00")), "", today, zone)
        assertEquals(listOf("a"), groups.today.map { it.id })
    }

    @Test
    fun searchMatchesTheTitleInAnyCase() {
        val chats = listOf(chat("a", "Lembas bread", null), chat("b", "Ropes", null))
        assertEquals(listOf("a"), groupChats(chats, "  BREAD ", today, zone).earlier.map { it.id })
        assertTrue(groupChats(chats, "nothing", today, zone).isEmpty)
    }

    @Test
    fun pinnedFirstWhereverFiledAndFoldersAsATree() {
        val folders = listOf(
            Folder("inner", "Recipes", parentId = "top", position = 0),
            Folder("top", "Waybread", position = 1),
            Folder("first", "Letters", position = 0),
            // Its parent is not in the list: shown at the top rather than lost.
            Folder("stray", "Stray", parentId = "gone", position = 2),
        )
        assertEquals(listOf("first" to 0, "top" to 0, "inner" to 1, "stray" to 0), folderTree(folders).map { it.first.id to it.second })

        val chats = listOf(
            Chat(id = "p", title = "Pinned and filed", folderId = "inner", pinned = true),
            Chat(id = "r", title = "Rye", folderId = "inner"),
            Chat(id = "w", title = "Wrapping", folderId = "top"),
            Chat(id = "u", title = "Unfiled", updatedAt = "2026-03-10T08:00:00+00:00"),
            Chat(id = "x", title = "In a folder this list does not have", folderId = "elsewhere"),
        )
        val groups = groupChats(chats, "", today, zone, folders)
        assertEquals(listOf("p"), groups.pinned.map { it.id })
        assertEquals(
            listOf("first" to emptyList(), "top" to listOf("w"), "inner" to listOf("r"), "stray" to emptyList<String>()),
            groups.folders.map {
                it.folder.id to
                    it.chats.map { c -> c.id }
            },
        )
        assertEquals(listOf("u"), groups.today.map { it.id })
        assertEquals(listOf("x"), groups.earlier.map { it.id })
    }

    @Test
    fun aCollapsedFolderHidesItsChatsAndTheFoldersInside() {
        val folders = listOf(Folder("top", "Waybread", collapsed = true), Folder("inner", "Recipes", parentId = "top"), Folder("next", "Letters", position = 1))
        val chats = listOf(Chat(id = "r", title = "Rye", folderId = "inner"), Chat(id = "w", title = "Wrapping", folderId = "top"))
        val groups = groupChats(chats, "", today, zone, folders)
        assertEquals(listOf("top" to emptyList(), "next" to emptyList<String>()), groups.folders.map { it.folder.id to it.chats.map { c -> c.id } })
        assertFalse(groups.isEmpty)
        // Searching opens everything, and leaves out folders without a match.
        val found = groupChats(chats, "rye", today, zone, folders)
        assertEquals(listOf("inner" to listOf("r")), found.folders.map { it.folder.id to it.chats.map { c -> c.id } })
    }

    @Test
    fun aSnippetsMarksAndOnesThatDoNotFit() {
        val bold = SpanStyle(fontWeight = FontWeight.Bold)
        val marked = markedSnippet("how long does lembas keep", listOf(listOf(14, 20), listOf(22, 99), listOf(5), listOf(9, 3)), bold)
        assertEquals("how long does lembas keep", marked.text)
        assertEquals(listOf(14 to 20, 22 to 25), marked.spanStyles.map { it.start to it.end })
    }
}
