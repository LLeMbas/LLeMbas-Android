package eu.llembas.android

import eu.llembas.android.api.Chat
import eu.llembas.android.ui.home.groupChats
import org.junit.Assert.assertEquals
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
}
