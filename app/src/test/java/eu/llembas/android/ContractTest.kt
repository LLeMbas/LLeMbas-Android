package eu.llembas.android

import eu.llembas.android.api.APP_API
import eu.llembas.android.api.AskFrame
import eu.llembas.android.api.Chat
import eu.llembas.android.api.ChatPage
import eu.llembas.android.api.DoneFrame
import eu.llembas.android.api.FileContent
import eu.llembas.android.api.FileListing
import eu.llembas.android.api.JobsFrame
import eu.llembas.android.api.LembasJson
import eu.llembas.android.api.LiveReply
import eu.llembas.android.api.Message
import eu.llembas.android.api.Metrics
import eu.llembas.android.api.Plan
import eu.llembas.android.api.StatusFrame
import eu.llembas.android.api.SteeredFrame
import eu.llembas.android.api.StepsFrame
import eu.llembas.android.api.Tail
import eu.llembas.android.api.ToolCall
import eu.llembas.android.ui.chat.approvalTexts
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server's contract, as pinned in `contract/` by `scripts/fetch-contract.py`: every shape
 * the server's serialisers wrote must parse into this app's models, and say what it said.
 */
class ContractTest {
    private fun text(name: String): String = requireNotNull(javaClass.classLoader!!.getResource("$name.json")) { "contract/$name.json is missing" }.readText()

    private fun <T> read(name: String, serializer: KSerializer<T>): T = LembasJson.decodeFromString(serializer, text(name))

    @Test
    fun theVersionIsOneThisAppReads() {
        val version = LembasJson.parseToJsonElement(text("version")).toString()
        assertEquals("""{"app_api":$APP_API}""", version)
    }

    @Test
    fun aShowCardFromTheTreeAndOneFromADevice() {
        // The server's own `show` cards (`tool_show.json`): what the app reads them by, and the
        // query that fetches the text from `GET /api/v2/chats/{chat}/show`.
        val calls = LembasJson.parseToJsonElement(text("tool_show")).jsonObject.getValue("calls")
        val (tree, device) = LembasJson.decodeFromJsonElement(ListSerializer(ToolCall.serializer()), calls)
        val show = tree.show!!
        assertEquals("show", tree.name)
        assertEquals("recipe.md", show.name)
        assertEquals("/home/frodo/recipe.md", show.path)
        assertEquals("markdown", show.viewKind)
        assertEquals(3, show.line)
        assertEquals(120L, show.size)
        assertEquals(listOf("node" to "node1", "version" to "ver1"), show.query)

        val kept = device.show!!
        assertEquals("device", kept.source)
        assertEquals("code", kept.viewKind)
        assertEquals("python", kept.language)
        // `line: null` reads as no line, and the tree's two ids are empty on a device's card.
        assertEquals(0, kept.line)
        assertEquals(listOf("shown" to "shown1"), kept.query)
    }

    @Test
    fun aDirectoryOfThePersonsFiles() {
        val listing = read("files_listing", FileListing.serializer())
        assertEquals("/home/frodo", listing.path)
        assertEquals("/home", listing.parent)
        assertEquals(listOf("recipes", "lembas.md"), listing.entries.map { it.name })
        assertTrue(listing.entries[0].isFolder)
        val file = listing.entries[1]
        assertFalse(file.isFolder)
        assertEquals(27L, file.size)
        assertEquals("text/markdown", file.mime)
    }

    @Test
    fun aFileAndAWithheldOne() {
        val file = read("file_content", FileContent.serializer())
        assertNull(file.withheld)
        assertEquals("markdown", file.viewKind)
        assertTrue(file.text.startsWith("# Lembas"))

        val image = read("file_content_withheld", FileContent.serializer())
        assertEquals("", image.text)
        assertEquals("not_text", image.withheld!!.reason)
        assertTrue(image.withheld!!.message.isNotBlank())
        // An image is no kind the viewer draws, so if its text ever came it would be plain text.
        assertEquals("text", image.viewKind)
    }

    @Test
    fun theTwoPointOhContractHasNoCanvas() {
        // LLeMbas 2.0 removed the canvas: no `canvas` frame, so no fixture for one. A stream
        // that still sends it (a 1.x server) is ignored frame by frame, as any unknown event is.
        assertNull(javaClass.classLoader!!.getResource("event_canvas.json"))
        assertEquals(LiveReply("m"), LiveReply("m").apply("canvas", """{"tabs": [], "active": ""}"""))
    }

    @Test
    fun aChatAndAnAgentChat() {
        val chat = read("chat", Chat.serializer())
        assertEquals("Waybread", chat.title)
        assertEquals("low", chat.effort)
        assertTrue(chat.unread)
        val agent = read("chat_agent", Chat.serializer())
        assertTrue(agent.isAgent)
        assertEquals("edit", agent.mode)
        assertEquals("/home/dev/project", agent.device?.directory)
    }

    @Test
    fun theMessages() {
        val user = read("message_user", Message.serializer())
        assertTrue(user.isUser)
        assertEquals("How is lembas made?", user.text)

        val reply = read("message_assistant", Message.serializer())
        assertEquals(listOf("thinking", "text", "tools", "steer", "text"), reply.steps.map { it.kind })
        assertTrue(reply.steps[1].markdown!!.startsWith("Here is how it is made"))
        val call = reply.steps[2].calls.single()
        assertEquals("shell", call.block.kind)
        assertEquals("bake --slow", call.block.title)
        assertEquals(listOf("exit 0"), call.block.chips)
        assertEquals("and add honey", reply.steps[3].messages.single().text)
        assertEquals(40, reply.metrics!!.completionTokens)
    }

    @Test
    fun aDeviceApprovalCorrectsItsDetail() {
        // A device's approval: the heading in `title`, the command in `detail` -- the text an
        // `editable` item corrects -- no "Something else", and the subagent asking in `job`.
        val approval = read("event_ask", AskFrame.serializer()).cards.single { it.kind == "approval" }
        val item = approval.items.single()
        assertEquals("Run a command", item.title)
        assertEquals("rm -rf build", item.detail)
        assertTrue(item.editable)
        assertFalse(item.freeText)
        assertEquals("explore (job1)", item.job)
        assertTrue(item.reason.isNotBlank())
        // Untouched, the same, blank: nothing sent. Corrected: the item's key with the new command.
        assertEquals(emptyMap<String, String>(), approvalTexts(approval.items, emptyMap()))
        assertEquals(emptyMap<String, String>(), approvalTexts(approval.items, mapOf(item.key to " rm -rf build ")))
        assertEquals(emptyMap<String, String>(), approvalTexts(approval.items, mapOf(item.key to "  ")))
        assertEquals(mapOf(item.key to "rm -rf build/tmp"), approvalTexts(approval.items, mapOf(item.key to "rm -rf build/tmp\n")))
        // An item that is not editable never sends a correction.
        assertEquals(emptyMap<String, String>(), approvalTexts(listOf(item.copy(editable = false)), mapOf(item.key to "ls")))
    }

    @Test
    fun aListedChatLeavesTheSubagentFactsToTheChat() {
        // The list does not work the facts out: null means "read the chat", never "not offered".
        val page = read("chats_listed", ChatPage.serializer())
        val chat = page.chats.single()
        assertEquals("Waybread", chat.title)
        assertNull(chat.subagents)
        assertEquals(4, chat.agents?.max)
        assertFalse(page.more)
        // The agent chat's own facts: not offered (its subagents run on its device), the cap said.
        val agent = read("chat_agent", Chat.serializer())
        assertEquals(false, agent.subagents?.offered)
        assertEquals(32, agent.subagents?.cap)
    }

    @Test
    fun everyFrameParses() {
        assertTrue(read("event_steps", StepsFrame.serializer()).steps.isNotEmpty())
        assertNotNull(read("event_tail", Tail.serializer()).label)
        assertEquals(listOf("msg9"), read("event_steered", SteeredFrame.serializer()).ids)
        assertEquals("Bake lembas", read("event_plan", Plan.serializer()).title)
        assertTrue(read("event_status", StatusFrame.serializer()).text.isNotEmpty())
        val cards = read("event_ask", AskFrame.serializer()).cards
        assertEquals(listOf("question", "approval", "plan"), cards.map { it.kind })
        assertEquals(listOf("Honey", "Plain"), cards[0].items[0].options.map { it.label })
        assertTrue(cards[1].items[0].editable)
        assertNotNull(cards[2].items[0].plan)
        assertTrue(read("event_ask_empty", AskFrame.serializer()).cards.isEmpty())
        assertEquals("explore", read("event_jobs", JobsFrame.serializer()).jobs.single().agent)
        assertEquals(2, read("event_metrics", Metrics.serializer()).percent)
        assertEquals("Waybread", read("event_done", DoneFrame.serializer()).chat.title)
    }

    @Test
    fun aWholeStreamBuildsAReply() {
        var live = LiveReply("msg2")
        for (event in listOf("steps", "tail", "steered", "plan", "status", "ask", "jobs", "metrics", "done", "close")) {
            live = live.apply(event, text("event_$event"))
        }
        assertTrue(live.closed)
        assertNotNull(live.finished)
        assertEquals("Waybread", live.title)
        assertNull(live.next)
        // A finished reply asks nothing: `done` clears the cards.
        assertTrue(live.cards.isEmpty())
        assertEquals(setOf("msg9"), live.steered)
        assertEquals("Bake lembas", live.plan?.title)
    }
}
