package eu.llembas.android.ui.chat

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.llembas.android.api.Activity
import eu.llembas.android.api.ActivityAgent
import eu.llembas.android.api.ActivityFrame
import eu.llembas.android.api.ActivitySummary
import eu.llembas.android.api.AgentSubagents
import eu.llembas.android.api.AgentTarget
import eu.llembas.android.api.AgentsSetting
import eu.llembas.android.api.ApiClient
import eu.llembas.android.api.ApiException
import eu.llembas.android.api.Attachment
import eu.llembas.android.api.Card
import eu.llembas.android.api.CardAnswer
import eu.llembas.android.api.Chat
import eu.llembas.android.api.ChatDetail
import eu.llembas.android.api.DeviceDirectory
import eu.llembas.android.api.LembasJson
import eu.llembas.android.api.LinkedDevice
import eu.llembas.android.api.LiveReply
import eu.llembas.android.api.Mention
import eu.llembas.android.api.Message
import eu.llembas.android.api.MessagePage
import eu.llembas.android.api.ModelInfo
import eu.llembas.android.api.StartModel
import eu.llembas.android.api.StreamClient
import eu.llembas.android.api.SubagentTree
import eu.llembas.android.api.Turn
import eu.llembas.android.api.named
import eu.llembas.android.lembas
import eu.llembas.android.ui.explain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

data class ChatState(
    val chat: Chat? = null,
    val messages: List<Message> = emptyList(),
    val more: Boolean = false,
    val live: LiveReply? = null,
    val models: List<ModelInfo> = emptyList(),
    /** For a new agent chat: the devices linked right now, which it can run on. */
    val devices: List<LinkedDevice> = emptyList(),
    /** For a new agent chat: the instance's address, which `lembas login` is given to link a computer. */
    val instanceBase: String = "",
    val pending: List<Attachment> = emptyList(),
    val uploading: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    /** A message that could not be sent, handed back to the composer once. */
    val unsent: String? = null,
    /** A card somebody answered elsewhere first: said once, then cleared. */
    val notice: String? = null,
    /** The activity panel's facts, once it has been opened; [activityAt] is when they were read. */
    val activity: Activity? = null,
    val activityAt: Long = 0,
    /** The strip's counts, from the events route (or the first read). */
    val summary: ActivitySummary? = null,
    /** Whether this chat is offered subagents: the activity's word for a chat that exists. */
    val teamOffered: Boolean = false,
    /** The instance's cap on a chat's subagents, from the models list; null from an older server. */
    val modelsCap: Int? = null,
    /** Whether the events route is followed; without it an open panel polls. */
    val activityLive: Boolean = false,
    /** A Compact asked for from the activity sheet and not answered yet. */
    val compacting: Boolean = false,
    /** What a chat not made yet will be made with: chosen in the top bar and the tune sheet. */
    val draft: Draft = Draft(),
    /** The instance makes pictures for this person (`/me` `services.images`). */
    val imagesOffered: Boolean = false,
    /** The subagent tree (spec 3.4.0) from this chat's last `tree` frame; null until one came. */
    val tree: SubagentTree? = null,
    /** For a new agent chat: whether it may have subagents, and whether its device runs them. */
    val agentSubagents: AgentSubagents? = null,
    /**
     * For a new agent chat: the model it starts on in the chosen device and folder when the
     * person picks none (spec 3.9.0) -- the folder's usual model, as the instance asked the
     * device. Shown, never sent and never kept: the instance does the same on its own.
     */
    val start: StartModel? = null,
    /** What `@` offers for the word being typed: the server's files, earlier attachments and pages, or an agent chat's device's files. */
    val mentions: List<Mention> = emptyList(),
    /** The message this app sent last, by its id: what the transcript shows whoever has scrolled where. */
    val lastSent: String? = null,
    /** The model picked for this open chat, by its ref: which of two models with one id the chat is on. */
    val picked: String? = null,
)

/**
 * The choices for a chat its first message makes. Nothing here is sent until then; an open
 * chat changes at once instead (`PATCH`), as the web page's controls do.
 */
data class Draft(
    val model: String? = null,
    val effort: String? = null,
    val agents: AgentsSetting? = null,
    val plan: Boolean = false,
    val agentMode: String = "edit",
    val device: String? = null,
    val directory: String = "",
    /** The next message asks for a picture (`force_tool: image_generate`); cleared once sent. */
    val image: Boolean = false,
)

/** The one tool a message may force (the server's `FORCEABLE_TOOLS`): make a picture. */
const val IMAGE_TOOL = "image_generate"

/**
 * The model in force: the chat's, else the one chosen for the new chat, else the folder's usual
 * one (a new agent chat's, where the person still has it), else the default. A model the
 * person picked is never set aside by the folder's.
 */
val ChatState.modelId: String?
    get() = chatModel ?: draft.model ?: folderModel ?: models.firstOrNull { it.default }?.key ?: models.firstOrNull()?.key

/**
 * An open chat's model. The chat says its model's id, which two connections may share; the one
 * picked here ([ChatState.picked], a ref) says which of them, for as long as the chat is still
 * on a model of that id.
 */
val ChatState.chatModel: String?
    get() {
        val served = chat?.model?.ifBlank { null } ?: return null
        return picked?.takeIf { models.named(it)?.id == served } ?: served
    }

/** The folder's usual model for a chat not made yet, when it is one of the models offered here. */
val ChatState.folderModel: String?
    get() = start?.takeIf { chat == null }?.let { models.named(it.key) ?: models.named(it.model) }?.key

val ChatState.modelInfo: ModelInfo? get() = models.named(modelId)

/** The thinking level in force, by the model's levels; null for a model with no choice. */
val ChatState.level: String?
    get() = modelInfo?.takeIf { it.levelControl != "none" }?.level(if (chat == null) draft.effort else chat.effort?.ifBlank { null })

/**
 * Whether the Subagents setting is shown (plan rule 4): the chat's own word for a chat that
 * exists (`subagents.offered`, else the activity's), the model's for a new one.
 */
fun ChatState.offersTeam(agent: Boolean): Boolean = when {
    chat != null -> chat.subagents?.offered ?: teamOffered

    // A new agent chat: the server's word for agent chats (spec 3.4.0 §14), never a guess.
    agent -> agentSubagents?.offered == true

    else -> modelInfo?.mayHaveTeam == true
}

/**
 * Whether subagents need a model the person names in the chat: the chat's own serves one
 * request at a time. The setting then says so; there is no default to fall back on.
 */
val ChatState.subagentsNamedOnly: Boolean
    get() = (if (chat != null) chat.subagents?.namedOnly else modelInfo?.subagents?.namedOnly) ?: false

/** The most subagents a chat may have here: the chat's cap, else the instance's, else the spec's. */
val ChatState.subagentsCap: Int
    get() = (chat?.subagents?.cap ?: modelsCap ?: AgentsSetting.CAP).coerceAtLeast(1)

/** The team's size in force. */
val ChatState.agents: AgentsSetting get() = chat?.agents ?: draft.agents ?: AgentsSetting()

/** Whether a reply is being written right now. */
val ChatState.running: Boolean get() = live != null && live.finished == null

/** The linked device a new agent chat runs on: the chosen one, else the first. */
val ChatState.device: LinkedDevice? get() = devices.firstOrNull { it.id == draft.device } ?: devices.firstOrNull()

/**
 * An older [page], read before the message [before], put above the transcript. Only while
 * [before] is still the first row -- a transcript read again meanwhile has moved, and the page
 * no longer belongs above it -- and never a row twice: the list's rows are keyed by id, and a
 * key drawn twice ends the app.
 */
fun ChatState.withOlder(before: String, page: MessagePage): ChatState {
    if (messages.firstOrNull()?.id != before) return this
    val have = messages.mapTo(HashSet()) { it.id }
    return copy(messages = page.messages.distinctBy { it.id }.filterNot { it.id in have } + messages, more = page.more)
}

/**
 * The stream of the reply [messageId] closed without a `done`. What was written stays in view:
 * the transcript as the server has it now ([reread]), or -- where that could not be read -- the
 * row with the steps the stream had shown, closed, and how it ended where the stream said so.
 * Nothing is live after it. A reply another stream is being followed for is left alone.
 */
fun ChatState.closedUnfinished(messageId: String, reread: ChatDetail?): ChatState {
    val was = live?.takeIf { it.messageId == messageId }
    if (live != null && was == null) return this
    if (reread != null) return copy(chat = reread.chat, messages = reread.messages, more = reread.more, live = null)
    if (was == null || was.shown.isEmpty()) return copy(live = null)
    val kept = was.shown.map { it.copy(open = false) }
    return copy(
        live = null,
        messages = messages.map { if (it.id == messageId) it.copy(steps = kept, progress = it.progress ?: was.end) else it },
    )
}

/**
 * What the chat screen asks of its model. Every action has a do-nothing default, so the
 * screenshot tests draw the screen with fake data and no server behind it.
 */
interface ChatActions {
    fun send(text: String) {}
    fun stop() {}
    fun regenerate(message: Message) {}
    fun edit(message: Message, text: String) {}
    fun continueChat() {}
    fun executePlan() {}
    fun answer(card: Card, answer: CardAnswer) {}
    fun change(key: String, value: String?) {}

    /** A new agent chat with no computer linked: read the linked devices again. */
    fun recheckDevices() {}

    /** A new agent chat's device, asked for a directory (`""`: its places), for the folder picker. */
    suspend fun directories(path: String): DeviceDirectory = DeviceDirectory()
    fun setAgents(agents: AgentsSetting) {}
    fun updateDraft(change: (Draft) -> Draft) {}
    fun attach(uris: List<Uri>) {}

    /** The `@` word being typed ([query]; null: none) -- what it offers comes to the state's `mentions`. */
    fun suggest(query: String?) {}

    /** Attach what an `@` entry names: an earlier attachment, a file of the tree, or a page. */
    fun mention(item: Mention) {}
    fun unattach(attachment: Attachment) {}
    fun photoTarget(): Uri? = null
    fun rename(title: String) {}
    fun delete(then: () -> Unit) {}
    fun older() {}
    fun clearUnsent() {}
}

/**
 * One chat: its transcript, the reply being written, and every control. A new chat has no id
 * until its first message makes it, as on the web page -- there is no empty chat.
 */
class ChatViewModel(app: Application) :
    AndroidViewModel(app),
    ChatActions {
    private val _state = MutableStateFlow(ChatState())
    val state = _state.asStateFlow()
    private var following: Job? = null

    /** Whether this screen makes an agent chat (until its first message makes it). */
    private var agentChat = false
    private var api: ApiClient? = null

    private suspend fun api(): ApiClient = api ?: getApplication<Application>().lembas.instances.client()?.also { api = it }
        ?: throw ApiException(401, "Not signed in.")

    private fun fail(e: Throwable) {
        // A follow that was replaced by a newer one is cancelled, not failed: nothing to say.
        if (e is CancellationException) return
        _state.update { it.copy(error = getApplication<Application>().explain(e), loading = false, uploading = uploads > 0) }
    }

    fun clearError() = _state.update { it.copy(error = null, notice = null) }

    override fun clearUnsent() = _state.update { it.copy(unsent = null) }

    override fun updateDraft(change: (Draft) -> Draft) {
        val before = _state.value.let { it.device?.id to it.draft.directory }
        _state.update { it.copy(draft = change(it.draft)) }
        val after = _state.value.let { it.device?.id to it.draft.directory }
        // Another computer or another folder: its Subagents fact and its usual model are read again.
        if (agentChat && _state.value.chat == null && after != before) readAgentFacts()
    }

    /**
     * A new agent chat's facts for the device and folder chosen now: whether it may have
     * subagents, and the model it starts on there. An older server says neither; an answer for
     * a choice that has changed meanwhile is dropped.
     */
    private fun readAgentFacts() {
        val device = _state.value.device?.id
        val directory = _state.value.draft.directory
        // What was read for another device or folder is not this one's.
        _state.update { it.copy(start = null) }
        viewModelScope.launch {
            val list = runCatching { api().agentModelList(device, directory) }.getOrNull()
            _state.update {
                if (it.device?.id == device &&
                    it.draft.directory == directory
                ) {
                    it.copy(agentSubagents = list?.agentSubagents, start = list?.start)
                } else {
                    it
                }
            }
        }
    }

    override suspend fun directories(path: String): DeviceDirectory {
        // No computer linked: there is nothing to list, and the typed path is what remains.
        val device = _state.value.device ?: return DeviceDirectory(code = "device_not_linked")
        return api().deviceDirectories(device.id, path)
    }

    override fun rename(title: String) = change("title", title.trim())

    /** Open a chat, or (no id) get a new one ready: an agent chat also needs the linked devices. */
    fun open(chatId: String?, agent: Boolean = false) {
        agentChat = agent
        viewModelScope.launch {
            try {
                val client = api()
                _state.update { it.copy(loading = chatId != null || agent, error = null, tree = null) }
                val list = runCatching { client.modelList(chatId) }.getOrNull()
                val models = list?.models.orEmpty()
                _state.update { it.copy(modelsCap = list?.subagentsCap) }
                if (!agent) runCatching { client.me() }.onSuccess { me -> _state.update { it.copy(imagesOffered = me.hasImages) } }
                if (chatId == null) {
                    val devices = if (agent) client.devices() else emptyList()
                    val base = if (agent) getApplication<Application>().lembas.instances.current()?.base.orEmpty() else ""
                    _state.update { it.copy(models = models, devices = devices, instanceBase = base, loading = false) }
                    if (agent) readAgentFacts()
                    return@launch
                }
                val detail = client.chat(chatId)
                _state.update {
                    it.copy(chat = detail.chat, messages = detail.messages, more = detail.more, models = models, loading = false)
                }
                detail.live?.let { follow(it) }
                // Whether a team is offered and what runs in the background: the cheap read,
                // without the context count. A server without the panel has no answer.
                runCatching { client.activity(chatId, context = false) }.onSuccess { a ->
                    _state.update { it.copy(teamOffered = a.subagentsOffered ?: a.teamOffered, summary = a.summary) }
                }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    override fun recheckDevices() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            try {
                val devices = api().devices()
                _state.update { it.copy(devices = devices, loading = false) }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    private var olderRead: Job? = null

    /** The page before the first row. One read at a time: a second tap while one is under way is not a second page. */
    override fun older() {
        if (olderRead?.isActive == true) return
        val chat = _state.value.chat ?: return
        val first = _state.value.messages.firstOrNull() ?: return
        olderRead = viewModelScope.launch {
            runCatching { api().messages(chat.id, first.id) }
                .onSuccess { page -> _state.update { it.withOlder(first.id, page) } }
                .onFailure(::fail)
        }
    }

    /** Follow a reply to its end, then whatever the queue started after it. */
    private fun follow(messageId: String) {
        val chat = _state.value.chat ?: return
        following?.cancel()
        following = viewModelScope.launch {
            _state.update { it.copy(live = LiveReply(messageId)) }
            // Set at `done`, which is the end of the reply: from then on the stream is followed
            // only for the name a model may still give the chat (the `title` frame).
            var ended: LiveReply? = null
            try {
                StreamClient(api()).follow(chat.id, messageId).collect { frame ->
                    val before = ended
                    if (before != null) {
                        val named = before.apply(frame.event, frame.data)
                        ended = named
                        if (named.title != before.title) _state.update { s -> s.copy(chat = s.chat?.let { c -> named.title?.let { c.copy(title = it) } ?: c }) }
                        return@collect
                    }
                    val live = (_state.value.live ?: LiveReply(messageId)).apply(frame.event, frame.data)
                    val finished = live.finished
                    if (finished == null) {
                        // The tree outlives the reply: kept for the activity sheet once it ends.
                        _state.update { s -> s.copy(live = live, tree = live.tree ?: s.tree) }
                        return@collect
                    }
                    ended = live
                    _state.update { s ->
                        s.copy(
                            live = null,
                            tree = live.tree ?: s.tree,
                            messages = s.messages.map { if (it.id == finished.id) finished else it },
                            chat = s.chat?.let { c -> live.title?.let { c.copy(title = it) } ?: c },
                        )
                    }
                    // The queue handed a waiting prompt to a fresh reply: the transcript has moved
                    // on the server, so it is read again rather than patched -- and that reply is
                    // followed instead of this stream's tail.
                    live.next?.let { next ->
                        refresh()
                        follow(next)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // After `done` only the name was still to come: nothing to say, nothing to redo.
                if (ended != null) return@launch
                fail(e)
                // The reply goes on on the server; what it has written so far is read back
                // rather than left half-drawn.
                _state.update { it.copy(live = null) }
                refresh()
                return@launch
            }
            // The stream closed without a `done`: the row is gone on the server, or it ended some
            // way the stream did not tell. The transcript is read again, so what was written is
            // still there; where it cannot be read, what the stream had shown stays.
            if (ended == null) {
                val reread = try {
                    api().chat(chat.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                _state.update { it.closedUnfinished(messageId, reread) }
                // Another reply is being written there (a queued turn): that one is followed.
                reread?.live?.takeIf { it != messageId }?.let { follow(it) }
            }
        }
    }

    private suspend fun refresh() {
        val chat = _state.value.chat ?: return
        runCatching { api().chat(chat.id) }.onSuccess { d ->
            _state.update { it.copy(chat = d.chat, messages = d.messages, more = d.more) }
        }
    }

    private fun took(turn: Turn) {
        _state.update { s ->
            s.copy(
                messages = s.messages.filterNot { m -> m.id == turn.message?.id || m.id == turn.reply?.id } + listOfNotNull(turn.message, turn.reply),
                lastSent = turn.message?.id ?: s.lastSent,
            )
        }
        turn.reply?.let { follow(it.id) }
    }

    /**
     * Send [text] (and what is attached). A chat that does not exist yet is made by it, with the
     * draft's choices -- each only where the model offers it.
     */
    override fun send(text: String) {
        val now = _state.value
        val draft = now.draft
        val newAgent = agentChat && now.chat == null
        val model = draft.model
        val mode = when {
            newAgent -> draft.agentMode
            draft.plan -> "plan"
            else -> null
        }
        val effort = draft.effort?.takeIf { picked -> now.modelInfo?.offered?.contains(picked) == true }
        val agent = if (newAgent) now.device?.let { AgentTarget(it.id, draft.directory) } else null
        val agents = draft.agents
        val temporary = false
        val attached = _state.value.pending
        val files = attached.map { it.id }
        if (text.isBlank() && files.isEmpty()) return
        // A picture, asked for from + (not in an agent chat, which runs on a computer).
        val picture = draft.image && now.imagesOffered && !agentChat
        viewModelScope.launch {
            try {
                val client = api()
                val chat = _state.value.chat
                _state.update { it.copy(pending = emptyList(), draft = it.draft.copy(image = false)) }
                if (chat == null) {
                    val made = client.createChat(
                        text,
                        files,
                        model = model,
                        temporary = temporary,
                        effort = effort,
                        mode = mode,
                        agent = agent,
                        agents = agents,
                        forceTool = if (picture) IMAGE_TOOL else null,
                    )
                    _state.update { it.copy(chat = made.chat, messages = listOfNotNull(made.message, made.reply), lastSent = made.message?.id) }
                    made.reply?.let { follow(it.id) }
                } else {
                    took(client.sendMessage(chat.id, text, files, forceTool = if (picture) IMAGE_TOOL else null))
                }
            } catch (e: Exception) {
                // Nothing was sent: the words and the attachments go back to the composer.
                _state.update { it.copy(pending = attached + it.pending, unsent = text, draft = it.draft.copy(image = picture || it.draft.image)) }
                fail(e)
            }
        }
    }

    override fun stop() {
        val chat = _state.value.chat ?: return
        val live = _state.value.live ?: return
        viewModelScope.launch { runCatching { api().stop(chat.id, live.messageId) }.onFailure(::fail) }
    }

    override fun regenerate(message: Message) = control { api, chat ->
        val turn = api.regenerate(chat.id, message.id)
        _state.update { s -> s.copy(messages = s.messages.map { if (it.id == message.id) turn.reply ?: it else it }) }
        turn.reply?.let { follow(it.id) }
    }

    override fun edit(message: Message, text: String) = control { api, chat ->
        val turn = api.edit(chat.id, message.id, text)
        // Everything after the edited turn is gone on the server; read it back.
        refresh()
        turn.reply?.let { follow(it.id) }
    }

    override fun continueChat() = control { api, chat -> took(api.continueChat(chat.id)) }

    override fun executePlan() = control { api, chat ->
        took(api.executePlan(chat.id))
        refresh()
    }

    override fun answer(card: Card, answer: CardAnswer) = control { api, chat ->
        try {
            api.answer(chat.id, card.id, answer)
        } catch (e: ApiException) {
            // First answer wins -- on the phone, the page or the device. A 409 is "somebody
            // already answered", and the stream's next `ask` frame clears the card.
            if (e.status != 409) throw e
            _state.update { it.copy(notice = e.message) }
        }
        _state.update { s -> s.copy(live = s.live?.let { l -> l.copy(cards = l.cards.filterNot { it.id == card.id }) }) }
    }

    override fun change(key: String, value: String?) = control { api, chat ->
        val updated = api.updateChat(chat.id, JsonObject(mapOf(key to JsonPrimitive(value))))
        _state.update { it.copy(chat = updated, picked = if (key == "model") value else it.picked) }
        // Another model may serve one request at a time, or more: whether a team is offered
        // follows it.
        if (key == "model") {
            runCatching { api.activity(chat.id, context = false) }.onSuccess { r ->
                _state.update { it.copy(teamOffered = r.subagentsOffered ?: r.teamOffered) }
            }
        }
    }

    /** The team's size, both ends; the server refuses a pair outside its bounds in its words. */
    override fun setAgents(agents: AgentsSetting) = control { api, chat ->
        val updated = api.setAgents(chat.id, agents)
        _state.update { it.copy(chat = updated) }
    }

    // --- The activity panel ------------------------------------------------------------------

    /**
     * Follow the chat's activity while the screen is in front: the events route says when
     * anything changed, with the strip's counts; an open panel then reads the whole of it
     * again. Runs until cancelled. A server without the route (or one that refuses it) leaves
     * the strip to the first read and an open panel to polling.
     */
    suspend fun watchActivity() {
        val chat = _state.value.chat ?: return
        val client = runCatching { api() }.getOrNull() ?: return
        if (_state.value.summary == null) {
            // A chat its first message just made: whether a team is offered, read once.
            runCatching { client.activity(chat.id, context = false) }.onSuccess { a ->
                _state.update { it.copy(teamOffered = a.subagentsOffered ?: a.teamOffered, summary = a.summary) }
            }
        }
        try {
            StreamClient(client).watch { client.activityEvents(chat.id) }.collect { frame ->
                if (frame.event != "activity") return@collect
                val read = runCatching { LembasJson.decodeFromString(ActivityFrame.serializer(), frame.data) }.getOrNull() ?: return@collect
                val changed = read.version != _state.value.activity?.version
                _state.update { it.copy(summary = read.summary, activityLive = true) }
                if (changed && panelOpen) loadActivity()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Nothing to say: the strip and the panel do without the route.
        } finally {
            _state.update { it.copy(activityLive = false) }
        }
    }

    private var panelOpen = false

    fun panel(open: Boolean) {
        panelOpen = open
        if (open) loadActivity()
    }

    /** Read the panel's facts; [context] false keeps the last count (the costly part). */
    fun loadActivity(context: Boolean = true) {
        val chat = _state.value.chat ?: return
        viewModelScope.launch {
            try {
                val read = api().activity(chat.id, context)
                _state.update { s ->
                    s.copy(
                        activity = if (context) read else read.copy(context = s.activity?.context),
                        activityAt = SystemClock.elapsedRealtime(),
                        summary = read.summary,
                        teamOffered = read.subagentsOffered ?: read.teamOffered,
                    )
                }
                // The tree (spec 3.4.0), for a sheet opened when no reply streams it; an older
                // server answers 404 and the Tasks section stands for it.
                runCatching { api().tree(chat.id) }.getOrNull()?.tree?.let { tree -> _state.update { it.copy(tree = tree) } }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /** Stop a background agent (and, for a member, those under it), then read the panel again. */
    fun stopAgent(agent: ActivityAgent) = control { api, chat ->
        api.stopAgent(chat.id, agent.kind, agent.id)
        loadActivity(context = false)
    }

    /** Compact the chat now; the transcript and the sheet are read again for what it changed. */
    fun compact() {
        val chat = _state.value.chat ?: return
        if (_state.value.compacting) return
        viewModelScope.launch {
            _state.update { it.copy(compacting = true) }
            try {
                api().compact(chat.id)
                refresh()
                loadActivity()
            } catch (e: Exception) {
                fail(e)
            } finally {
                _state.update { it.copy(compacting = false) }
            }
        }
    }

    override fun delete(then: () -> Unit) = control { api, chat ->
        api.deleteChat(chat.id)
        then()
    }

    /** How many uploads and attachings are under way: `uploading` holds until the last has ended. */
    private var uploads = 0

    private fun uploadBegins() {
        uploads++
        _state.update { it.copy(uploading = true) }
    }

    private fun uploadEnds() {
        uploads = (uploads - 1).coerceAtLeast(0)
        _state.update { it.copy(uploading = uploads > 0) }
    }

    override fun attach(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            uploadBegins()
            try {
                val resolver = getApplication<Application>().contentResolver
                for (uri in uris) {
                    val (name, bytes) = withContext(Dispatchers.IO) {
                        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                            if (c.moveToFirst()) c.getString(0) else null
                        } ?: "file"
                        name to (resolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0))
                    }
                    val mime = resolver.getType(uri) ?: "application/octet-stream"
                    val stored = api().upload(name, mime, bytes, _state.value.chat?.id)
                    _state.update { it.copy(pending = it.pending + stored) }
                    // A photo the camera took for this message is the server's now.
                    if (uri.authority == photoAuthority) runCatching { resolver.delete(uri, null, null) }
                }
            } catch (e: Exception) {
                fail(e)
            }
            uploadEnds()
        }
    }

    private var suggesting: Job? = null

    override fun suggest(query: String?) {
        suggesting?.cancel()
        if (query == null) {
            if (_state.value.mentions.isNotEmpty()) _state.update { it.copy(mentions = emptyList()) }
            return
        }
        suggesting = viewModelScope.launch {
            // Asked once the typing pauses, not at every letter.
            delay(MENTION_PAUSE_MS)
            val where = _state.value.let { Triple(it.chat?.id, it.device?.id, it.draft.directory) }
            val found = try {
                offered(query)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // An older server, or a device that cannot answer: nothing is offered.
                emptyList()
            }
            // Another computer or folder was chosen meanwhile: these were its files, not this one's.
            _state.update { if (Triple(it.chat?.id, it.device?.id, it.draft.directory) == where) it.copy(mentions = found) else it }
        }
    }

    /** An agent chat's `@` is its device's files (the device reads them); a chat's is the server's. */
    private suspend fun offered(query: String): List<Mention> {
        val now = _state.value
        val chat = now.chat
        if (chat?.kind == "agent" || (chat == null && agentChat)) {
            val device = chat?.device?.tokenId ?: now.device?.id ?: return emptyList()
            return api().deviceFiles(device, query, chatId = chat?.id, cwd = now.draft.directory).files.mapNotNull { it.asMention() }.take(MENTION_LIMIT)
        }
        return api().mentions(query, chat?.id).items.take(MENTION_LIMIT)
    }

    override fun mention(item: Mention) {
        _state.update { it.copy(mentions = emptyList()) }
        viewModelScope.launch {
            uploadBegins()
            try {
                val stored = api().attachFrom(item, _state.value.chat?.id)
                _state.update { it.copy(pending = it.pending + stored) }
            } catch (e: Exception) {
                fail(e)
            }
            uploadEnds()
        }
    }

    private val photoAuthority get() = "${getApplication<Application>().packageName}.files"

    /**
     * Where the camera writes a photo for the composer: a file in the app's cache, shared with
     * the camera app through the FileProvider for exactly this one capture.
     */
    override fun photoTarget(): Uri {
        val app = getApplication<Application>()
        val dir = File(app.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(app, photoAuthority, file)
    }

    override fun unattach(attachment: Attachment) {
        _state.update { s -> s.copy(pending = s.pending.filterNot { it.id == attachment.id }) }
    }

    private fun control(block: suspend (ApiClient, Chat) -> Unit) {
        val chat = _state.value.chat ?: return
        viewModelScope.launch {
            try {
                block(api(), chat)
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    private companion object {
        const val MENTION_PAUSE_MS = 250L
    }
}
