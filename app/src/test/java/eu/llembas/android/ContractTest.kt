package eu.llembas.android

import eu.llembas.android.api.APP_API
import eu.llembas.android.api.AgentSubagents
import eu.llembas.android.api.AskFrame
import eu.llembas.android.api.AttachmentEnvelope
import eu.llembas.android.api.CardItem
import eu.llembas.android.api.CardSubagent
import eu.llembas.android.api.Chat
import eu.llembas.android.api.ChatPage
import eu.llembas.android.api.DoneFrame
import eu.llembas.android.api.FileContent
import eu.llembas.android.api.FileListing
import eu.llembas.android.api.FolderEnvelope
import eu.llembas.android.api.FolderList
import eu.llembas.android.api.JobsFrame
import eu.llembas.android.api.LembasJson
import eu.llembas.android.api.LiveReply
import eu.llembas.android.api.Me
import eu.llembas.android.api.MemoryEnvelope
import eu.llembas.android.api.MemoryList
import eu.llembas.android.api.Mentions
import eu.llembas.android.api.Message
import eu.llembas.android.api.Metrics
import eu.llembas.android.api.ModelList
import eu.llembas.android.api.Plan
import eu.llembas.android.api.ProgressWords
import eu.llembas.android.api.SearchResults
import eu.llembas.android.api.StatusFrame
import eu.llembas.android.api.SteeredFrame
import eu.llembas.android.api.StepsFrame
import eu.llembas.android.api.SubagentTranscript
import eu.llembas.android.api.Tail
import eu.llembas.android.api.TitleFrame
import eu.llembas.android.api.ToolCall
import eu.llembas.android.api.TreeFrame
import eu.llembas.android.api.TreeView
import eu.llembas.android.api.callDuration
import eu.llembas.android.api.ending
import eu.llembas.android.api.progressText
import eu.llembas.android.api.state
import eu.llembas.android.api.took
import eu.llembas.android.ui.chat.ChatState
import eu.llembas.android.ui.chat.approvalTexts
import eu.llembas.android.ui.chat.offersAlways
import eu.llembas.android.ui.chat.offersTeam
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The server's contract, as pinned in `contract/` by `scripts/fetch-contract.py`: every shape
 * the server's serialisers wrote must parse into this app's models, and say what it said.
 */
class ContractTest {
    private fun text(name: String): String = requireNotNull(javaClass.classLoader!!.getResource("$name.json")) { "contract/$name.json is missing" }.readText()

    private fun <T> read(name: String, serializer: KSerializer<T>): T = LembasJson.decodeFromString(serializer, text(name))

    @Test
    fun theFoldersAndOne() {
        val folders = read("folders", FolderList.serializer()).folders
        assertEquals(listOf("fold1", "fold2"), folders.map { it.id })
        assertEquals("fold1", folders[1].parentId)
        assertTrue(folders[1].collapsed)
        assertEquals("Waybread", read("folder", FolderEnvelope.serializer()).folder.name)
    }

    @Test
    fun theMemoriesAndOne() {
        val list = read("memories", MemoryList.serializer())
        val one = list.memories.single()
        assertEquals("Prefers lembas warm.", one.content)
        assertFalse(one.byModel)
        assertEquals("default", one.dataGroup.id)
        assertEquals(listOf("Default"), list.groups.map { it.name })
        assertEquals("mem1", read("memory", MemoryEnvelope.serializer()).memory.id)
    }

    @Test
    fun theSearchFindsAChatAFileAndAMemory() {
        val found = read("search", SearchResults.serializer())
        assertEquals(listOf("chats", "files", "memories"), found.offered)
        val chat = found.hits("chats").single()
        assertEquals("chat1", chat.id)
        assertEquals("lembas", chat.snippet.substring(chat.marks[0][0], chat.marks[0][1]))
        assertEquals("/home/frodo/bread.md", found.hits("files").single().path)
        assertEquals("mem1", found.hits("memories").single().id)
        assertTrue(found.results.first().more)
    }

    @Test
    fun aScheduleThatRunsOnAComputer() {
        val schedule = LembasJson.parseToJsonElement(text("schedule_on_device")).jsonObject
        // Parsed by the app's model without a field for it yet: the rest must still read.
        assertEquals("sched3", read("schedule_on_device", eu.llembas.android.api.ScheduleEnvelope.serializer()).schedule.id)
        assertTrue("runs_on" in schedule.getValue("schedule").jsonObject)
    }

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
        assertNull(user.progress)

        // A prompt waiting behind a running reply says so, and how many are ahead of it.
        val queued = read("message_queued", Message.serializer())
        assertTrue(queued.queued)
        assertEquals("queued", queued.progress!!.phase)
        assertEquals(2, queued.progress!!.ahead)

        val reply = read("message_assistant", Message.serializer())
        assertEquals(listOf("thinking", "text", "tools", "steer", "text"), reply.steps.map { it.kind })
        assertTrue(reply.steps[1].markdown!!.startsWith("Here is how it is made"))
        val call = reply.steps[2].calls.single()
        assertEquals("shell", call.block.kind)
        assertEquals("bake --slow", call.block.title)
        // Spec 3.7.0: an exit code is a fact only when it is not 0; the call says its state, how
        // long it took and what its tool is called.
        assertTrue(call.block.chips.isEmpty())
        assertEquals("done", call.block.state)
        assertEquals("done", call.state())
        assertEquals(2412L, call.ms)
        assertEquals("2.4s", call.block.duration)
        assertEquals("2.4s", call.took())
        assertEquals("2.4s", callDuration("done", call.ms))
        assertEquals("Bash", call.block.label)
        assertNull(reply.end)
        assertNull(reply.ending())
        assertEquals("and add honey", reply.steps[3].messages.single().text)
        assertEquals(40, reply.metrics!!.completionTokens)
    }

    @Test
    fun aDeviceApprovalCorrectsItsDetail() {
        // A device's approval: the heading in `title`, the command in `detail` -- the text an
        // `editable` item corrects -- no "Something else", and the subagent asking in `job`.
        val approval = read("event_ask", AskFrame.serializer()).cards.first { it.kind == "approval" }
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
        // The agent chat's own facts (3.4.0): offered -- the setting goes to its device -- the cap said.
        val agent = read("chat_agent", Chat.serializer())
        assertEquals(true, agent.subagents?.offered)
        assertEquals(32, agent.subagents?.cap)
    }

    @Test
    fun aTreeSubagentAsksThePerson() {
        // A device's subagent asks for what nobody in its tree may allow: the card names it.
        val cards = read("event_ask", AskFrame.serializer()).cards
        val item = cards.single { c -> c.items.any { it.subagent != null } }.items.single()
        assertEquals(CardSubagent(id = "s1", name = "parser", position = "reviews the parser"), item.subagent)
        assertEquals("git push", item.detail)
        assertEquals("", item.job)
        assertNull(cards.first().items.single().subagent)
    }

    @Test
    fun theTreeFrame() {
        val tree = read("event_tree", TreeFrame.serializer()).tree!!
        assertEquals(1, tree.openSlots)
        assertEquals(listOf("lead", "parser"), tree.agents.map { it.name })
        assertEquals("Review src/parse.ts.", tree.agents[1].task)
        assertEquals(6, tree.agents[1].contextPercent)
        assertEquals("done", tree.ended.single().state)
        assertNull(tree.ended.single().progress)
        // The stream carries it; the reply keeps it.
        assertEquals(tree, LiveReply("m").apply("tree", text("event_tree")).tree)
    }

    @Test
    fun theTreeReadAndASubagentsConversation() {
        val view = read("tree", TreeView.serializer())
        assertTrue(view.live)
        assertNotNull(view.tree)
        assertEquals(listOf("message", "report"), view.room.map { if (it.report) "report" else "message" })
        val read = read("subagent_transcript", SubagentTranscript.serializer())
        assertEquals("s2", read.id)
        assertEquals("working", read.state)
        assertTrue(read.turns.first().machine)
        assertEquals("src/api.py", read.turns[1].tools.single().title)
        assertTrue(read.turns[1].running)
    }

    @Test
    fun meSaysTheLanguageAndTheServices() {
        // `/api/v2/me` (me.json): the account's language the app follows by default, and the
        // instance's speech services the assistant uses.
        val me = read("me", Me.serializer())
        assertEquals("sk", me.accountLanguage)
        assertEquals("qwen", me.defaultModel)
        assertTrue(me.hasSpeechToText)
        assertEquals(true, me.speechToText)
        assertFalse(me.hasTextToSpeech)
        assertEquals("Frodo", me.account.name)
        // Not said: no language (a kept answer stands), and speech-to-text unknown, not "no".
        val silent = me.copy(language = "", services = emptyMap())
        assertNull(silent.accountLanguage)
        assertNull(silent.speechToText)
        assertFalse(silent.hasSpeechToText)
    }

    @Test
    fun aNewAgentChatsSubagentsFact() {
        // `/models?kind=agent&device=` (models_agent.json): offered, and the device runs them.
        val list = read("models_agent", ModelList.serializer())
        assertEquals(AgentSubagents(offered = true, deviceRuns = true), list.agentSubagents)
        assertTrue(list.models.all { it.subagents?.offered == true })
        assertTrue(ChatState(models = list.models, agentSubagents = list.agentSubagents).offersTeam(agent = true))
        // Without kind=agent the field is not there.
        assertNull(read("models", ModelList.serializer()).agentSubagents)
    }

    @Test
    fun everyFrameParses() {
        assertTrue(read("event_steps", StepsFrame.serializer()).steps.isNotEmpty())
        assertNotNull(read("event_tail", Tail.serializer()).label)
        assertEquals(listOf("msg9"), read("event_steered", SteeredFrame.serializer()).ids)
        assertEquals("Bake lembas", read("event_plan", Plan.serializer()).title)
        assertTrue(read("event_status", StatusFrame.serializer()).text.isNotEmpty())
        val cards = read("event_ask", AskFrame.serializer()).cards
        assertEquals(listOf("question", "approval", "plan", "approval"), cards.map { it.kind })
        assertEquals(listOf("Honey", "Plain"), cards[0].items[0].options.map { it.label })
        assertTrue(cards[1].items[0].editable)
        assertNotNull(cards[2].items[0].plan)
        assertTrue(read("event_ask_empty", AskFrame.serializer()).cards.isEmpty())
        assertEquals("explore", read("event_jobs", JobsFrame.serializer()).jobs.single().agent)
        assertEquals(2, read("event_metrics", Metrics.serializer()).percent)
        assertEquals("Waybread", read("event_done", DoneFrame.serializer()).chat.title)
    }

    @Test
    fun aStoredReplySaysHowItEnded() {
        val words = ProgressWords { key, args ->
            mapOf("stopped_reason" to "Stopped: %s", "stopped_reason_person" to "by you", "failed" to "Failed: %s")[key]?.format(*args)
        }
        val stopped = read("message_stopped", Message.serializer())
        assertEquals("person", stopped.end!!.reason)
        // The app's own words, from the phase and the reason, say what the server's line says.
        assertEquals(stopped.end!!.text, progressText(stopped.ending()!!, words, 0, Locale.ROOT))
        val failed = read("message_failed", Message.serializer())
        assertEquals("failed", failed.ending()!!.phase)
        assertEquals(failed.end!!.text, progressText(failed.ending()!!, words, 0, Locale.ROOT))
    }

    @Test
    fun alwaysAllowIsOfferedOnlyWhereTheRequestOffersIt() {
        val cards = read("event_ask", AskFrame.serializer()).cards
        val approvals = cards.filter { it.kind == "approval" }
        assertEquals(listOf(true, true), approvals.map { offersAlways(it) })
        val once = approvals[0].copy(items = approvals[0].items.map { it.copy(always = false) })
        assertFalse(offersAlways(once))
        // An older server says nothing of it, and offered it always.
        assertTrue(LembasJson.decodeFromString(CardItem.serializer(), """{"key":"0","kind":"approval"}""").always)
    }

    @Test
    fun whatAtOffersAndWhatAttachingItGives() {
        val items = read("mentions", Mentions.serializer()).items
        assertEquals(listOf("file", "attachment"), items.map { it.kind })
        assertEquals("/home/frodo/bread.md", items[0].path)
        assertEquals("att1", items[1].id)
        assertEquals("https://example.org/waybread", read("mentions_url", Mentions.serializer()).items.single().url)
        assertEquals("bread.md", read("attachment_from", AttachmentEnvelope.serializer()).attachment.name)
    }

    @Test
    fun aRunningCallComesWithTheTail() {
        // The tail's `calls` are not drawn yet (the steps are); the frame must still parse.
        assertEquals("Done. *Wrap", read("event_tail", Tail.serializer()).text)
    }

    @Test
    fun doneIsTheEndAndTheChatsNameMayFollow() {
        // A new chat's first reply: saved and done at once, under the name made from the prompt.
        val pending = read("event_done_title_pending", DoneFrame.serializer())
        assertTrue(pending.chat.titlePending)
        assertEquals("How is lembas made?", pending.chat.title)
        assertFalse(read("event_done", DoneFrame.serializer()).chat.titlePending)
        var live = LiveReply("msg2").apply("done", text("event_done_title_pending"))
        assertNotNull(live.finished)
        assertEquals("How is lembas made?", live.title)
        assertFalse(live.closed)
        // Then, if a model names it before the stream closes, one `title` frame: only the name changes.
        val finished = live.finished
        live = live.apply("title", text("event_title"))
        assertEquals("Waybread", live.title)
        assertEquals(finished, live.finished)
        assertEquals("chat1", read("event_title", TitleFrame.serializer()).chat.id)
        assertTrue(live.apply("close", text("event_close")).closed)
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
