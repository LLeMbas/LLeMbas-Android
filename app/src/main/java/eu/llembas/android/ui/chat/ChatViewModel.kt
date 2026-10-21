package eu.llembas.android.ui.chat

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.llembas.android.api.ApiClient
import eu.llembas.android.api.ApiException
import eu.llembas.android.api.Attachment
import eu.llembas.android.api.Card
import eu.llembas.android.api.CardAnswer
import eu.llembas.android.api.Chat
import eu.llembas.android.api.LiveReply
import eu.llembas.android.api.Message
import eu.llembas.android.api.ModelInfo
import eu.llembas.android.api.StreamClient
import eu.llembas.android.api.Turn
import eu.llembas.android.lembas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ChatState(
    val chat: Chat? = null,
    val messages: List<Message> = emptyList(),
    val more: Boolean = false,
    val live: LiveReply? = null,
    val models: List<ModelInfo> = emptyList(),
    val pending: List<Attachment> = emptyList(),
    val uploading: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    /** A card somebody answered elsewhere first: said once, then cleared. */
    val notice: String? = null,
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
        _state.update { it.copy(error = e.message ?: e.javaClass.simpleName, loading = false, uploading = false) }
    }

    fun clearError() = _state.update { it.copy(error = null, notice = null) }

    fun open(chatId: String?) {
        viewModelScope.launch {
            try {
                val client = api()
                _state.update { it.copy(loading = chatId != null) }
                val models = runCatching { client.models(chatId) }.getOrDefault(emptyList())
                if (chatId == null) {
                    _state.update { it.copy(models = models, loading = false) }
                    return@launch
                }
                val detail = client.chat(chatId)
                _state.update {
                    it.copy(chat = detail.chat, messages = detail.messages, more = detail.more, models = models, loading = false)
                }
                detail.live?.let { follow(it) }
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
            } catch (e: Exception) {
                fail(e)
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

    fun send(text: String, model: String? = null, mode: String? = null, effort: String? = null, temporary: Boolean = false) {
        val files = _state.value.pending.map { it.id }
        if (text.isBlank() && files.isEmpty()) return
        viewModelScope.launch {
            try {
                val client = api()
                val chat = _state.value.chat
                _state.update { it.copy(pending = emptyList()) }
                if (chat == null) {
                    val made = client.createChat(text, files, model = model, temporary = temporary, effort = effort, mode = mode)
                    _state.update { it.copy(chat = made.chat, messages = listOfNotNull(made.message, made.reply)) }
                    made.reply?.let { follow(it.id) }
                } else {
                    took(client.sendMessage(chat.id, text, files))
                }
            } catch (e: Exception) {
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
                }
            } catch (e: Exception) {
                fail(e)
            }
            _state.update { it.copy(uploading = false) }
        }
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
