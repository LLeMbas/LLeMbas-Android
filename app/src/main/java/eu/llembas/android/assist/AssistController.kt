package eu.llembas.android.assist

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import eu.llembas.android.MainActivity
import eu.llembas.android.R
import eu.llembas.android.api.ApiException
import eu.llembas.android.api.Card
import eu.llembas.android.api.CardAnswer
import eu.llembas.android.api.LiveReply
import eu.llembas.android.api.ModelInfo
import eu.llembas.android.api.Step
import eu.llembas.android.api.StreamClient
import eu.llembas.android.lembas
import eu.llembas.android.notify.NotificationReader
import eu.llembas.android.push.Notifications
import eu.llembas.android.ui.explain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream

/** One question asked in the sheet and its answer: written now ([reply]), or read back ([earlier]). */
data class AssistTurn(val question: String, val reply: LiveReply? = null, val earlier: List<Step> = emptyList()) {
    val steps: List<Step> get() = reply?.finished?.steps ?: reply?.shown ?: earlier
    val writing: Boolean get() = reply != null && reply.finished == null && !reply.closed
}

/** Whether the instance answers: being asked, yes, or no (and why). */
enum class Connection { Checking, Online, Offline }

/** The last chat the assistant made, offered to be continued. */
data class LastAssist(val chatId: String, val title: String, val at: Long)

data class AssistUiState(
    val signedIn: Boolean = true,
    val instanceName: String = "",
    val connection: Connection = Connection.Checking,
    val models: List<ModelInfo> = emptyList(),
    val modelId: String? = null,
    val listening: Boolean = false,
    /** The words heard so far, while listening and while the last of them is written down. */
    val heard: String = "",
    /** How loud the microphone hears it, 0..1. */
    val level: Float = 0f,
    val busy: Boolean = false,
    val turns: List<AssistTurn> = emptyList(),
    val chatId: String? = null,
    val error: String? = null,
    /** Whether the screen goes with the question. Off until the person turns it on, every time. */
    val includeScreen: Boolean = false,
    val screenText: Boolean = false,
    val screenshot: Bitmap? = null,
    val last: LastAssist? = null,
    /** Whether a spoken question is answered aloud too. */
    val voiceReply: Boolean = true,
    /** Whether the instance can read aloud (its text-to-speech, for this account). */
    val canSpeak: Boolean = false,
    val speaking: Boolean = false,
    /** Whether anything can turn speech into words: the instance's speech-to-text, or the phone's. */
    val canListen: Boolean = true,
    /**
     * A spoken conversation: after each spoken answer is read aloud, the sheet listens again --
     * until the person says stop, taps End, types, or says nothing.
     */
    val conversation: Boolean = false,
    /** Whether the person let the assistant read the notifications when asked (Settings and Android both). */
    val canReadNotifications: Boolean = false,
) {
    val screenAvailable: Boolean get() = screenText || screenshot != null
    val modelInfo: ModelInfo? get() = models.firstOrNull { it.id == modelId }
    val empty: Boolean get() = turns.isEmpty()
}

/**
 * What the sheet can do. Every method does nothing by default, so the screenshot tests can draw
 * the sheet with no controller behind it.
 */
interface AssistActions {
    fun listen() {}
    fun heard() {}
    fun type() {}
    fun ask(question: String) {}
    fun includeScreen(on: Boolean) {}
    fun quick(prompt: String) {}
    fun continueLast() {}
    fun newChat() {}
    fun pickModel(id: String) {}
    fun voiceReply(on: Boolean) {}
    fun readAloud() {}
    fun stopReading() {}
    fun endConversation() {}
    fun summariseNotifications() {}
    fun answerCard(card: Card, answer: CardAnswer) {}
}

/**
 * The assistant: take a question -- spoken or typed -- start a chat with it on the instance (or
 * carry on the one this sheet is in), and stream the answer back into the sheet. Shared by the
 * voice-interaction session (power button, assist gesture) and [AssistActivity] (the tile, the
 * shortcut, the lock-screen path), so both behave the same.
 *
 * A chat it makes is an ordinary one: in the list on the phone and on the web afterwards, and
 * "Open in app" continues it there. Settings' "assistant chats are temporary" makes them
 * temporary instead.
 *
 * Speech in is Settings › Voice input: the instance's speech-to-text (the words are written down
 * a moment behind, from the recording so far, and a pause in speech sends it), or the phone's
 * own recogniser (the words as they are said). Speech out is the instance's text-to-speech.
 */
class AssistController(private val context: Context, private val scope: CoroutineScope) : AssistActions {
    private val _state = MutableStateFlow(AssistUiState())
    val state = _state.asStateFlow()
    private val recorder = Recorder(scope)
    private val phone = PhoneRecognizer(context)
    private val speaker = Speaker(context)
    private val route = HeadsetRoute(context)
    private var following: Job? = null
    private var hearing: Job? = null
    private var usingPhone = false
    private var screenText: String? = null
    private var stt: Boolean? = null
    private val ready: Job

    init {
        ready = scope.launch { load() }
        scope.launch { speaker.playing.collect { on -> _state.update { it.copy(speaking = on) } } }
    }

    /** The instance, its models and what it offers this account; the choices kept in Settings. */
    private suspend fun load() {
        val store = context.lembas.instances
        val instance = store.current()
        val prefs = store.prefs.first()
        _state.update {
            it.copy(
                signedIn = instance != null,
                instanceName = instance?.name?.ifBlank { instance.base }.orEmpty(),
                voiceReply = prefs.readAloudSpoken,
                canReadNotifications = prefs.notificationSummaries && NotificationReader.granted(context),
                last = prefs.lastAssistChat.takeIf { id -> id.isNotBlank() && !prefs.assistantTemporary }
                    ?.let { id -> LastAssist(id, prefs.lastAssistTitle, prefs.lastAssistAt) },
            )
        }
        val api = instance?.let { store.clientFor(it) } ?: return
        try {
            val me = api.me()
            val models = api.models()
            stt = me.speechToText
            val chosen = prefs.assistantModel.takeIf { id -> models.any { it.id == id } }
                ?: me.defaultModel?.takeIf { id -> models.any { it.id == id } }
                ?: models.firstOrNull { it.default }?.id
                ?: models.firstOrNull()?.id
            _state.update {
                it.copy(
                    connection = Connection.Online,
                    models = models,
                    modelId = it.modelId ?: chosen,
                    canSpeak = me.hasTextToSpeech,
                    canListen = me.hasSpeechToText || PhoneRecognizer.available(context),
                )
            }
        } catch (e: Exception) {
            _state.update { it.copy(connection = Connection.Offline, error = context.explain(e)) }
        }
    }

    /**
     * The sheet came up. It starts listening at once -- the microphone first, as an assistant
     * opens -- unless Settings says not to, the phone is locked (nothing is sent from the lock
     * screen), or the microphone is not allowed yet (that needs an activity to ask). [listen]
     * is a way in that means to speak -- a headset's voice button -- and listens regardless.
     */
    fun opened(locked: Boolean, listen: Boolean = false) {
        scope.launch {
            ready.join()
            val prefs = context.lembas.instances.prefs.first()
            val s = _state.value
            if (s.signedIn && !locked && (listen || prefs.assistantListens) && s.canListen && !s.listening && micAllowed()) {
                if (listen || s.empty) listen()
            }
        }
    }

    /** Open at the last assistant chat, once it is known which that is. */
    fun continueWhenReady() {
        scope.launch {
            ready.join()
            if (_state.value.last != null) continueLast() else opened(locked = false)
        }
    }

    fun micAllowed(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun offerScreen(text: String?, image: Bitmap?) {
        if (!text.isNullOrBlank()) screenText = text.take(MAX_SCREEN_CHARS)
        _state.update { it.copy(screenText = screenText != null, screenshot = image ?: it.screenshot) }
    }

    override fun includeScreen(on: Boolean) = _state.update { it.copy(includeScreen = on) }

    // --- Speech in ---------------------------------------------------------------------------

    /** Which recogniser: the one Settings names, else whichever this phone and instance have. */
    private suspend fun phoneEngine(): Boolean {
        val wanted = context.lembas.instances.prefs.first().voiceInput
        return usePhoneRecognizer(wanted, PhoneRecognizer.available(context), stt)
    }

    @android.annotation.SuppressLint("MissingPermission")
    override fun listen() {
        if (_state.value.listening || !micAllowed()) return
        speaker.stop()
        hearing?.cancel()
        _state.update { it.copy(listening = true, heard = "", level = 0f, error = null) }
        hearing = scope.launch {
            usingPhone = phoneEngine()
            // A headset's microphone when one is there: the conversation can go on in the ear.
            val headset = route.microphone()
            if (usingPhone) {
                phone.start(
                    onPartial = { words -> _state.update { it.copy(heard = words) } },
                    onLevel = { l -> _state.update { it.copy(level = l) } },
                    onResult = { words ->
                        _state.update { it.copy(listening = false, level = 0f, heard = "") }
                        if (words.isNotBlank()) askOrStop(words) else endConversation()
                    },
                    onFail = { code ->
                        val quiet = code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        _state.update {
                            it.copy(
                                listening = false,
                                level = 0f,
                                conversation = false,
                                error = if (quiet) null else context.getString(R.string.assist_recogniser_failed),
                            )
                        }
                    },
                )
            } else {
                recorder.preferred = headset
                recorder.start()
                launch { recorder.level.collect { l -> _state.update { it.copy(level = l) } } }
                launch { writeDownAsSaid() }
                // Nothing said is nothing sent: a speech-to-text model asked to write down
                // silence tends to invent a sentence.
                if (untilQuiet()) heard() else type()
            }
        }
    }

    /**
     * The instance's speech-to-text takes a whole recording, so words "as they are said" are the
     * recording so far, written down again every moment -- one request at a time, only when half
     * a second more has been said, and not past the first minute.
     */
    private suspend fun writeDownAsSaid() {
        val api = context.lembas.instances.client() ?: return
        var sent = 0f
        while (scope.isActive) {
            delay(PARTIAL_EVERY_MS)
            val seconds = recorder.seconds
            if (seconds - sent < 0.5f || seconds > 60f) continue
            sent = seconds
            val words = runCatching { api.transcribe(recorder.snapshot()) }.getOrNull()
            if (!words.isNullOrBlank() && _state.value.listening) _state.update { it.copy(heard = words) }
        }
    }

    /** Wait for a pause after speech (true) -- or for nothing said at all, which gives up (false). */
    private suspend fun untilQuiet(): Boolean {
        var spoke = false
        var quietFor = 0L
        var waited = 0L
        // The words written down so far are the second ear, for a quiet microphone: words the
        // instance heard mean speech, and the same words for a while mean it ended.
        var words = ""
        var sameWordsFor = 0L
        while (true) {
            delay(TICK_MS)
            waited += TICK_MS
            val level = recorder.level.value
            if (level >= SPEAKING) {
                spoke = true
                quietFor = 0
            } else if (level < QUIET) {
                quietFor += TICK_MS
            }
            val heard = _state.value.heard
            if (heard.isNotBlank()) spoke = true
            if (heard != words) {
                words = heard
                sameWordsFor = 0
            } else if (heard.isNotBlank()) {
                sameWordsFor += TICK_MS
            }
            if (spoke && quietFor >= PAUSE_MS) return true
            if (sameWordsFor >= SAME_WORDS_MS) return true
            if (!spoke && waited >= NOTHING_SAID_MS) return false
            if (waited >= LONGEST_MS) return spoke
        }
    }

    /** Stop listening, write down what was said, and ask it. */
    override fun heard() {
        if (!_state.value.listening) return
        if (usingPhone) {
            phone.finish()
            return
        }
        hearing?.cancel()
        hearing = null
        scope.launch {
            val wav = recorder.stop()
            _state.update { it.copy(listening = false, level = 0f) }
            if (wav.size <= 44 + Recorder.RATE / 2) {
                _state.update { it.copy(heard = "") }
                return@launch
            }
            try {
                _state.update { it.copy(busy = true) }
                val text = context.lembas.instances.client()?.transcribe(wav).orEmpty()
                _state.update { it.copy(busy = false, heard = "") }
                if (text.isNotBlank()) askOrStop(text) else endConversation()
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, heard = "", error = context.explain(e)) }
            }
        }
    }

    /**
     * Stop listening and keep nothing: the person would rather type -- or said nothing at all.
     * Either ends a spoken conversation.
     */
    override fun type() {
        hearing?.cancel()
        hearing = null
        phone.stop()
        scope.launch { recorder.stop() }
        _state.update { it.copy(listening = false, heard = "", level = 0f, conversation = false) }
    }

    /** What was said: in a conversation, "stop" (or the like) ends it; anything else is asked. */
    private fun askOrStop(words: String) {
        val phrases = context.getString(R.string.assist_stop_words).split("|")
        if (_state.value.conversation && isStopPhrase(words, phrases)) endConversation() else ask(words, spoken = true)
    }

    override fun endConversation() {
        _state.update { it.copy(conversation = false) }
    }

    // --- Asking ------------------------------------------------------------------------------

    override fun ask(question: String) = ask(question, spoken = false)

    override fun quick(prompt: String) {
        _state.update { it.copy(includeScreen = true) }
        ask(prompt, spoken = false)
    }

    /** The sheet's "Summarise my notifications": the notifications up now go with the question. */
    override fun summariseNotifications() = ask(context.getString(R.string.assist_notifications_prompt), spoken = false, notifications = true)

    fun ask(question: String, spoken: Boolean, notifications: Boolean = false) {
        if (question.isBlank()) return
        if (_state.value.listening) type()
        following?.cancel()
        following = scope.launch {
            val turn = AssistTurn(question)
            _state.update { it.copy(busy = true, turns = it.turns + turn, error = null) }
            // A spoken question, answered aloud, opens a conversation (Settings › Assistant).
            val talking = spoken && context.lembas.instances.prefs.first().assistantConversation &&
                _state.value.voiceReply && _state.value.canSpeak
            _state.update { it.copy(conversation = talking) }
            try {
                val store = context.lembas.instances
                val api = store.client() ?: throw ApiException(401, "Not signed in.")
                val prefs = store.prefs.first()
                val now = _state.value
                val withScreen = now.includeScreen
                val attachments = buildList {
                    if (withScreen) now.screenshot?.let { shot -> add(api.upload("screen.png", "image/png", png(shot), now.chatId).id) }
                }
                // The notifications, when asked for by the chip or by name ("my notifications") --
                // read now, only while unlocked (NotificationReader.read), and for this question only.
                val phrases = context.getString(R.string.assist_notifications_phrases).split("|")
                val notices = if (now.canReadNotifications && (notifications || NotificationReader.asksAbout(question, phrases))) {
                    NotificationReader.asText(NotificationReader.read(context)).ifBlank { NO_NOTIFICATIONS }
                } else {
                    null
                }
                val text = buildString {
                    append(question)
                    if (withScreen && screenText != null) append("\n\n---\nOn my screen:\n\n$screenText")
                    if (notices != null) append("\n\n---\nMy notifications:\n\n$notices")
                }
                // The screen goes with one question, not every one after it.
                _state.update { it.copy(includeScreen = false) }
                val chatId = now.chatId
                val replyId: String
                val inChat: String
                if (chatId != null) {
                    val sent = api.sendMessage(chatId, text, attachments)
                    inChat = chatId
                    replyId = sent.reply?.id ?: return@launch
                } else {
                    val made = api.createChat(text, attachments, model = now.modelId, temporary = prefs.assistantTemporary)
                    inChat = made.chat.id
                    replyId = made.reply?.id ?: return@launch
                    _state.update { it.copy(chatId = inChat) }
                    if (!prefs.assistantTemporary) {
                        store.savePrefs {
                            it.copy(lastAssistChat = inChat, lastAssistTitle = question.take(120), lastAssistAt = System.currentTimeMillis())
                        }
                    }
                }
                var live = LiveReply(replyId)
                StreamClient(api).follow(inChat, replyId).collect { frame ->
                    live = live.apply(frame.event, frame.data)
                    _state.update { s -> s.copy(turns = s.turns.dropLast(1) + turn.copy(reply = live), busy = live.finished == null) }
                }
                val heardAll = if (spoken && _state.value.voiceReply && _state.value.canSpeak && live.finished != null && live.cards.isEmpty()) {
                    speak(spokenWords(live.finished?.steps.orEmpty()))
                } else {
                    false
                }
                // The answer was heard to the end: listen for the next thing said. Stopped, a
                // card waiting, or nothing to read: the conversation ends there.
                if (_state.value.conversation && heardAll && micAllowed()) listen() else endConversation()
            } catch (e: Exception) {
                _state.update { it.copy(error = context.explain(e), conversation = false) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** Carry on the last chat the assistant made: its last exchange shown, the next question sent to it. */
    override fun continueLast() {
        val last = _state.value.last ?: return
        scope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val api = context.lembas.instances.client() ?: return@launch
                val detail = api.chat(last.chatId)
                val question = detail.messages.lastOrNull { it.isUser }
                val answer = detail.messages.lastOrNull { !it.isUser }
                val turns = if (question != null) listOf(AssistTurn(question.text.orEmpty(), earlier = answer?.steps.orEmpty())) else emptyList()
                _state.update { it.copy(chatId = last.chatId, turns = turns, modelId = detail.chat.model.ifBlank { null } ?: it.modelId, last = null) }
            } catch (e: Exception) {
                // A chat deleted since is simply not offered again.
                if (e is ApiException && e.status == 404) {
                    context.lembas.instances.savePrefs { it.copy(lastAssistChat = "", lastAssistTitle = "", lastAssistAt = 0) }
                    _state.update { it.copy(last = null) }
                } else {
                    _state.update { it.copy(error = context.explain(e)) }
                }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** A phone action's card, answered in the sheet: what the phone did (spec 3.6.0). */
    override fun answerCard(card: Card, answer: CardAnswer) {
        val chat = _state.value.chatId ?: return
        scope.launch {
            try {
                context.lembas.instances.client()?.answer(chat, card.id, answer)
            } catch (e: ApiException) {
                // First answer wins: a 409 is somebody answering elsewhere already.
                if (e.status != 409) _state.update { it.copy(error = context.explain(e)) }
            } catch (e: Exception) {
                _state.update { it.copy(error = context.explain(e)) }
            }
        }
    }

    override fun newChat() {
        following?.cancel()
        speaker.stop()
        _state.update { it.copy(chatId = null, turns = emptyList(), error = null, busy = false, conversation = false) }
    }

    /** The model: for the chat this sheet is in, changed at once; for a new one, kept for next time. */
    override fun pickModel(id: String) {
        _state.update { it.copy(modelId = id) }
        scope.launch {
            context.lembas.instances.savePrefs { it.copy(assistantModel = id) }
            val chat = _state.value.chatId ?: return@launch
            runCatching { context.lembas.instances.client()?.updateChat(chat, JsonObject(mapOf("model" to JsonPrimitive(id)))) }
                .onFailure { e -> _state.update { it.copy(error = context.explain(e)) } }
        }
    }

    // --- Speech out --------------------------------------------------------------------------

    override fun voiceReply(on: Boolean) {
        if (!on) speaker.stop()
        _state.update { it.copy(voiceReply = on) }
        scope.launch { context.lembas.instances.savePrefs { it.copy(readAloudSpoken = on) } }
    }

    override fun readAloud() {
        val steps = _state.value.turns.lastOrNull()?.steps ?: return
        scope.launch { speak(spokenWords(steps)) }
    }

    /** Stop the voice -- and, in a conversation, the listening that would follow it. */
    override fun stopReading() = speaker.stop()

    /** Read [words] aloud; true when they were heard to the end. */
    private suspend fun speak(words: String): Boolean {
        if (words.isBlank()) return false
        return try {
            val api = context.lembas.instances.client() ?: return false
            speaker.playToEnd(api.speech(words))
        } catch (e: Exception) {
            _state.update { it.copy(error = context.explain(e)) }
            false
        }
    }

    /** Carry on in the app, at this chat. The caller starts it: a session and an activity
     *  start activities differently. */
    fun appIntent(): Intent = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .apply { _state.value.chatId?.let { putExtra(Notifications.EXTRA_CHAT, it) } }

    fun close() {
        following?.cancel()
        hearing?.cancel()
        speaker.stop()
        phone.stop()
        route.release()
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
        private const val NO_NOTIFICATIONS = "(none)"

        // Listening, for the instance's speech-to-text (the phone's recogniser finds the end of
        // speech itself). Levels are the RMS of 16-bit samples, 0..1: a room is under 0.01,
        // a voice near the phone 0.02 and up. Not tuned on many phones: the stop button always works.
        private const val TICK_MS = 100L
        private const val SPEAKING = 0.02f
        private const val QUIET = 0.01f
        private const val PAUSE_MS = 1_800L
        private const val SAME_WORDS_MS = 4_500L
        private const val NOTHING_SAID_MS = 8_000L
        private const val LONGEST_MS = 120_000L
        private const val PARTIAL_EVERY_MS = 1_200L

        /** A reply's words, for a voice: its text steps, without the markup. */
        fun spokenWords(steps: List<Step>): String = plain(steps.filter { it.kind == "text" }.joinToString("\n\n") { it.markdown.orEmpty() })

        /** Markdown as something a voice can read: no fences, marks or link targets. */
        fun plain(markdown: String): String = markdown
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("[*_`#>|~]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
