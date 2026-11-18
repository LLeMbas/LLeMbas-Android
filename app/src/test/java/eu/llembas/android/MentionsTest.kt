package eu.llembas.android

import eu.llembas.android.api.ApiClient
import eu.llembas.android.api.DeviceFiles
import eu.llembas.android.api.LembasJson
import eu.llembas.android.ui.chat.MENTION_DEVICE
import eu.llembas.android.ui.chat.asMention
import eu.llembas.android.ui.chat.mentionQuery
import eu.llembas.android.ui.chat.withMention
import eu.llembas.android.ui.chat.withoutMention
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The composer's `@`: which word asks, what a pick leaves in the text, and a device's files. */
class MentionsTest {
    private fun text(name: String): String = requireNotNull(javaClass.classLoader!!.getResource("$name.json")) { "contract/$name.json is missing" }.readText()

    @Test
    fun theWordTheTextEndsWithAsks() {
        assertEquals("", mentionQuery("@"))
        assertEquals("bre", mentionQuery("Read @bre"))
        assertEquals("src/loa", mentionQuery("Look at\n@src/loa"))
        assertEquals("https://example.org/waybread", mentionQuery("Sum up @https://example.org/waybread"))
        // No `@`, an address, or a word already finished: nothing is asked.
        assertNull(mentionQuery("Read the recipe"))
        assertNull(mentionQuery("Write to frodo@shire.test"))
        assertNull(mentionQuery("Read @bread.md and"))
        assertNull(mentionQuery(""))
        // The last `@` is the one that counts.
        assertEquals("oven", mentionQuery("Compare @bread.md with @oven"))
    }

    @Test
    fun aPickLeavesTheTextWithoutTheWordOrWithThePath() {
        // Attached: the word goes, the rest stays as typed.
        assertEquals("Read ", withoutMention("Read @bre"))
        assertEquals("", withoutMention("@"))
        // A device's file: the word becomes its path, ready for the next word.
        assertEquals("Look at @src/loader ", withMention("Look at @src/loa", "src/loader"))
        // Nothing being asked: nothing changes.
        assertEquals("Read the recipe", withoutMention("Read the recipe"))
        assertEquals("a@b.test", withMention("a@b.test", "README.md"))
    }

    @Test
    fun aDevicesFilesAreOfferedByPath() = runTest {
        val files = LembasJson.decodeFromString(DeviceFiles.serializer(), text("device_files"))
        val (readme, loader) = files.files.map { it.asMention() }
        assertEquals(MENTION_DEVICE, readme.kind)
        assertEquals("README.md", readme.path)
        assertEquals("loader", loader.name)
        // A directory says so in its detail.
        assertEquals("src/loader/", loader.detail)

        val server = MockWebServer().apply { start() }
        try {
            val api = ApiClient(server.url("/").toString().trimEnd('/'), "lmb_test")
            repeat(2) { server.enqueue(MockResponse().setBody(text("device_files"))) }
            // Before the chat exists: the folder chosen for it. Once it does: the chat.
            api.deviceFiles("token1", "read", cwd = "/home/user/project")
            assertEquals("/api/v2/devices/token1/files?q=read&cwd=%2Fhome%2Fuser%2Fproject", server.takeRequest().path)
            api.deviceFiles("token1", "read", chatId = "chat1", cwd = "/home/user/project")
            assertEquals("/api/v2/devices/token1/files?q=read&chat=chat1", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }
}
