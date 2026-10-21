package eu.llembas.android.assist

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import eu.llembas.android.MainActivity
import eu.llembas.android.api.LiveReply
import eu.llembas.android.api.StreamClient
import eu.llembas.android.lembas
import eu.llembas.android.push.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

data class AssistUiState(
    val signedIn: Boolean = true,
    val listening: Boolean = false,
    val busy: Boolean = false,
    val asked: String? = null,
    val chatId: String? = null,
    val reply: LiveReply? = null,
    val error: String? = null,
    /** Whether the screen goes with the question. Off until the person turns it on, every time. */
    val includeScreen: Boolean = false,
    val screenAvailable: Boolean = false,
)

/**
 * The assistant's one job: take a question -- typed or spoken -- start a chat with it on the
 * instance, and stream the answer back into the sheet. Shared by the voice-interaction session
 * (power button, assist gesture) and [AssistActivity] (the tile, the shortcut, the lock screen
 * path), so both behave the same.
 *
 * The chat is an ordinary one: it is in the list on the phone and on the web afterwards. The
 * Settings switch "assistant chats are temporary" makes them temporary instead.
 */
class AssistController(private val context: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(AssistUiState())
    val state = _state.asStateFlow()
    private val recorder = Recorder(scope)
    private val speaker = Speaker(context)
    private var following: Job? = null
    private var screenText: String? = null
    private var screenshot: Bitmap? = null

    init {
        scope.launch { _state.update { it.copy(signedIn = context.lembas.instances.current() != null) } }
    }

    fun offerScreen(text: String?, image: Bitmap?) {
        if (!text.isNullOrBlank()) screenText = text.take(MAX_SCREEN_CHARS)
        if (image != null) screenshot = image
        _state.update { it.copy(screenAvailable = screenText != null || screenshot != null) }
    }

    fun includeScreen(on: Boolean) = _state.update { it.copy(includeScreen = on) }

    @android.annotation.SuppressLint("MissingPermission")
    fun listen() {
        speaker.stop()
        recorder.start()
        _state.update { it.copy(listening = true, error = null) }
    }

    /** Stop listening, write down what was said, and ask it. */
    fun heard() {
        scope.launch {
            val wav = recorder.stop()
            _state.update { it.copy(listening = false) }
            if (wav.isEmpty()) return@launch
            try {
                _state.update { it.copy(busy = true) }
                val text = context.lembas.instances.client()?.transcribe(wav).orEmpty()
                _state.update { it.copy(busy = false) }
                if (text.isNotBlank()) ask(text, spoken = true)
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e.message) }
            }
        }
    }

    fun ask(question: String, spoken: Boolean = false) {
        if (question.isBlank()) return
        following?.cancel()
        following = scope.launch {
            _state.update { it.copy(busy = true, asked = question, reply = null, error = null) }
            try {
                val api = context.lembas.instances.client() ?: error("Not signed in.")
                val prefs = context.lembas.instances.prefs.first()
                val withScreen = _state.value.includeScreen
                val attachments = buildList {
                    if (withScreen) {
                        screenshot?.let { shot -> add(api.upload("screen.png", "image/png", png(shot)).id) }
                    }
                }
                val text = if (withScreen && screenText != null) "$question\n\n---\nOn my screen:\n\n$screenText" else question
                val made = api.createChat(text, attachments, temporary = prefs.assistantTemporary)
                _state.update { it.copy(chatId = made.chat.id) }
                val reply = made.reply ?: return@launch
                var live = LiveReply(reply.id)
                StreamClient(api).follow(made.chat.id, reply.id).collect { frame ->
                    live = live.apply(frame.event, frame.data)
                    _state.update { it.copy(reply = live, busy = live.finished == null) }
                }
                val finished = live.finished
                if (spoken && prefs.readAloudSpoken && finished != null) {
                    val words = finished.steps.filter { it.kind == "text" }.joinToString("\n\n") { it.markdown.orEmpty() }
                    if (words.isNotBlank()) speaker.play(api.speech(plain(words)))
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** Carry on in the app, at this chat. The caller starts it: a session and an activity
     *  start activities differently. */
    fun appIntent(): Intent = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .apply { _state.value.chatId?.let { putExtra(Notifications.EXTRA_CHAT, it) } }

    fun close() {
        following?.cancel()
        speaker.stop()
        scope.launch { recorder.stop() }
    }

    private suspend fun png(bitmap: Bitmap): ByteArray = withContext(Dispatchers.Default) {
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }

    companion object {
        const val MAX_SCREEN_CHARS = 8_000

        /** Markdown as something a voice can read: no fences, marks or link targets. */
        fun plain(markdown: String): String = markdown
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("[*_`#>|~]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
