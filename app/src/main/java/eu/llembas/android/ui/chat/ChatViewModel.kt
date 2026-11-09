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
import eu.llembas.android.api.AgentTarget
import eu.llembas.android.api.AgentsSetting
import eu.llembas.android.api.ApiClient
import eu.llembas.android.api.ApiException
import eu.llembas.android.api.Attachment
import eu.llembas.android.api.Card
import eu.llembas.android.api.CardAnswer
import eu.llembas.android.api.Chat
import eu.llembas.android.api.LembasJson
import eu.llembas.android.api.LinkedDevice
import eu.llembas.android.api.LiveReply
import eu.llembas.android.api.Message
import eu.llembas.android.api.ModelInfo
import eu.llembas.android.api.StreamClient
import eu.llembas.android.api.Turn
import eu.llembas.android.lembas
import eu.llembas.android.ui.explain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    /** Whether this chat may have a team: the server's word for a chat that exists. */
    val teamOffered: Boolean = false,
    /** Whether the events route is followed; without it an open panel polls. */
    val activityLive: Boolean = false,
)

/**
 * One chat: its transcript, the reply being written, and every control. A new chat has no id
 * until its first message makes it, as on the web page -- there is no empty chat.
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(ChatState())
    val state = _state.asStateFlow()
    private var following: Job? = null
    private var api: ApiClient? = null

    private suspend fun api(): ApiClient = api ?: getApplication<Application>().lembas.instances.client()?.also { api = it }
        ?: throw ApiException(401, "Not signed in.")

    private fun fail(e: Throwable) {
        // A follow that was replaced by a newer one is cancelled, not failed: nothing to say.
        if (e is CancellationException) return
        _state.update { it.copy(error = getApplication<Application>().explain(e), loading = false, uploading = false) }
    }

    fun clearError() = _state.update { it.copy(error = null, notice = null) }

    fun clearUnsent() = _state.update { it.copy(unsent = null) }

    fun rename(title: String) = change("title", title.trim())

    /** Open a chat, or (no id) get a new one ready: an agent chat also needs the linked devices. */
    fun open(chatId: String?, agent: Boolean = false) {
        viewModelScope.launch {
            try {
                val client = api()
                _state.update { it.copy(loading = chatId != null || agent, error = null) }
                val models = runCatching { client.models(chatId) }.getOrDefault(emptyList())
                if (chatId == null) {
                    val devices = if (agent) client.devices() else emptyList()
                    _state.update { it.copy(models = models, devices = devices, loading = false) }
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
                    _state.update { it.copy(teamOffered = a.teamOffered, summary = a.summary) }
                }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun older() {
        val chat = _state.value.chat ?: return
        val first = _state.value.messages.firstOrNull() ?: return
        viewModelScope.launch {
            runCatching { api().messages(chat.id, first.id) }
                .onSuccess { page -> _state.update { it.copy(messages = page.messages + it.messages, more = page.more) } }
                .onFailure(::fail)
        }
    }

    /** Follow a reply to its end, then whatever the queue started after it. */
    private fun follow(messageId: String) {
        val chat = _state.value.chat ?: return
        following?.cancel()
        following = viewModelScope.launch {
            _state.update { it.copy(live = LiveReply(messageId)) }
            try {
                StreamClient(api()).follow(chat.id, messageId).collect { frame ->
                    _state.update { s -> s.copy(live = (s.live ?: LiveReply(messageId)).apply(frame.event, frame.data)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
                // The reply goes on on the server; what it has written so far is read back
                // rather than left half-drawn.
                _state.update { it.copy(live = null) }
                refresh()
                return@launch
            }
            val ended = _state.value.live
            _state.update { s ->
                val finished = ended?.finished
                s.copy(
                    live = null,
                    messages = if (finished != null) s.messages.map { if (it.id == finished.id) finished else it } else s.messages,
                    chat = s.chat?.let { c -> ended?.title?.let { c.copy(title = it) } ?: c },
                )
            }
            // The queue handed a waiting prompt to a fresh reply: the transcript has moved on
            // the server, so it is read again rather than patched.
            ended?.next?.let { next ->
                refresh()
                follow(next)
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
            s.copy(messages = s.messages.filterNot { m -> m.id == turn.message?.id || m.id == turn.reply?.id } + listOfNotNull(turn.message, turn.reply))
        }
        turn.reply?.let { follow(it.id) }
    }

    fun send(
        text: String,
        model: String? = null,
        mode: String? = null,
        effort: String? = null,
        temporary: Boolean = false,
        agent: AgentTarget? = null,
        agents: AgentsSetting? = null,
    ) {
        val attached = _state.value.pending
        val files = attached.map { it.id }
        if (text.isBlank() && files.isEmpty()) return
        viewModelScope.launch {
            try {
                val client = api()
                val chat = _state.value.chat
                _state.update { it.copy(pending = emptyList()) }
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
                    )
                    _state.update { it.copy(chat = made.chat, messages = listOfNotNull(made.message, made.reply)) }
                    made.reply?.let { follow(it.id) }
                } else {
                    took(client.sendMessage(chat.id, text, files))
                }
            } catch (e: Exception) {
                // Nothing was sent: the words and the attachments go back to the composer.
                _state.update { it.copy(pending = attached + it.pending, unsent = text) }
                fail(e)
            }
        }
    }

    fun stop() {
        val chat = _state.value.chat ?: return
        val live = _state.value.live ?: return
        viewModelScope.launch { runCatching { api().stop(chat.id, live.messageId) }.onFailure(::fail) }
    }

    fun regenerate(message: Message) = control { api, chat ->
        val turn = api.regenerate(chat.id, message.id)
        _state.update { s -> s.copy(messages = s.messages.map { if (it.id == message.id) turn.reply ?: it else it }) }
        turn.reply?.let { follow(it.id) }
    }

    fun edit(message: Message, text: String) = control { api, chat ->
        val turn = api.edit(chat.id, message.id, text)
        // Everything after the edited turn is gone on the server; read it back.
        refresh()
        turn.reply?.let { follow(it.id) }
    }

    fun continueChat() = control { api, chat -> took(api.continueChat(chat.id)) }

    fun executePlan() = control { api, chat ->
        took(api.executePlan(chat.id))
        refresh()
    }

    fun answer(card: Card, answer: CardAnswer) = control { api, chat ->
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

    fun change(key: String, value: String?) = control { api, chat ->
        val updated = api.updateChat(chat.id, JsonObject(mapOf(key to JsonPrimitive(value))))
        _state.update { it.copy(chat = updated) }
        // Another model may serve one request at a time, or more: whether a team is offered
        // follows it.
        if (key == "model") {
            runCatching { api.activity(chat.id, context = false) }.onSuccess { r -> _state.update { it.copy(teamOffered = r.teamOffered) } }
        }
    }

    /** The team's size, both ends; the server refuses a pair outside its bounds in its words. */
    fun setAgents(agents: AgentsSetting) = control { api, chat ->
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
                _state.update { it.copy(teamOffered = a.teamOffered, summary = a.summary) }
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
                        teamOffered = read.teamOffered,
                    )
                }
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

    fun delete(then: () -> Unit) = control { api, chat ->
        api.deleteChat(chat.id)
        then()
    }

    fun attach(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(uploading = true) }
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
            _state.update { it.copy(uploading = false) }
        }
    }

    private val photoAuthority get() = "${getApplication<Application>().packageName}.files"

    /**
     * Where the camera writes a photo for the composer: a file in the app's cache, shared with
     * the camera app through the FileProvider for exactly this one capture.
     */
    fun photoTarget(): Uri {
        val app = getApplication<Application>()
        val dir = File(app.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(app, photoAuthority, file)
    }

    fun unattach(attachment: Attachment) {
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
}
