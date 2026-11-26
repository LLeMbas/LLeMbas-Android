package eu.llembas.android.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import eu.llembas.android.AppLanguage
import eu.llembas.android.MainActivity
import eu.llembas.android.R
import eu.llembas.android.api.ApiException
import eu.llembas.android.api.FileKinds
import eu.llembas.android.api.named
import eu.llembas.android.assist.AssistSheetFrame
import eu.llembas.android.assist.LastAssist
import eu.llembas.android.auth.Prefs
import eu.llembas.android.lembas
import eu.llembas.android.push.Notifications
import eu.llembas.android.sendFrom
import eu.llembas.android.ui.explain
import eu.llembas.android.ui.files.FolderPicker
import eu.llembas.android.ui.files.LocalFile
import eu.llembas.android.ui.files.describe
import eu.llembas.android.ui.files.filesSay
import eu.llembas.android.ui.files.uploadFrom
import eu.llembas.android.ui.theme.LembasTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Ask LLeMbas" in any app's share menu: text, a link, a picture or files. A sheet over the app
 * it came from says what came in, takes a line of what to do with it, and sends it to a new chat
 * or to the last chat the assistant made; files are uploaded first and bound to that message, as
 * the composer's are. The chat then opens in the app. A new chat made here becomes the
 * assistant's last chat, so the next share can follow it.
 *
 * The instance is asked nothing until the person has chosen what to do, and no request starts
 * while the phone is locked or the sheet is not in front ([eu.llembas.android.InstanceGate]).
 */
class ShareActivity :
    ComponentActivity(),
    ShareActions {
    private val state = MutableStateFlow(ShareState())

    /** The files that came in and may be sent on ([SharedSource]), as the provider describes each. */
    private var files: List<LocalFile> = emptyList()
    private val choosing = MutableStateFlow(false)

    /** How far "Save to Files" got into which folder: a second try takes it up at the one that failed. */
    private var kept = Kept()

    /** The attachments already uploaded for which chat (null: a new one), so a second try does not upload them again. */
    private var attached: Pair<String?, List<String>> = null to emptyList()
    private var homeAsked = false

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val given = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        // The addresses are the other app's word: one that names something of this app's own is never opened.
        val taken = given.filter { SharedSource.acceptable(this, it) }
        val text = listOfNotNull(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n\n")
            .trim()
        state.update { it.copy(text = text, refused = taken.size < given.size, reading = taken.isNotEmpty()) }
        lifecycleScope.launch {
            // What each is called and how large, from its provider, before anything can be sent. The instance is asked nothing yet.
            files = withContext(Dispatchers.IO) { taken.map { contentResolver.describe(it) } }
            state.update { it.copy(files = files.map { f -> SharedFile(f.name, f.mime.startsWith("image/"), f.size) }, reading = false) }
        }
        lifecycleScope.launch {
            val store = lembas.instances
            val instance = store.default()
            val prefs = store.prefs.first()
            val own = prefs.forInstance(instance?.id)
            state.update {
                it.copy(
                    signedIn = instance != null,
                    last = own.lastAssistChat.takeIf { id -> id.isNotBlank() && !prefs.assistantTemporary }
                        ?.let { id -> LastAssist(id, own.lastAssistTitle, own.lastAssistAt) },
                )
            }
        }
        setContent {
            val prefs by lembas.instances.prefs.collectAsState(initial = Prefs())
            val shown by state.collectAsState()
            val picking by choosing.collectAsState()
            LembasTheme(prefs.theme) {
                AssistSheetFrame(onDismiss = ::cancel) { handle, _ -> ShareSheetContent(shown, this@ShareActivity, handle) }
                if (picking) {
                    FolderPicker(
                        stringResource(R.string.files_choose_folder),
                        stringResource(R.string.files_save_here),
                        shown.folder ?: "/",
                        onPick = { folder ->
                            choosing.value = false
                            state.update { it.copy(folder = folder) }
                        },
                        onDismiss = { choosing.value = false },
                    )
                }
            }
        }
    }

    override fun cancel() = finish()

    override fun chooseFolder() {
        choosing.value = true
    }

    /** The gate, before a request: nothing is sent while the phone is locked or the sheet is not in front. */
    private suspend fun turn() = lembas.gate.pass { waiting -> state.update { it.copy(waiting = waiting) } }

    /**
     * "Save to Files" was chosen: only now is the instance asked where the person's files
     * open, once. Until it answers there is no folder and nothing can be saved; the folder
     * picker works either way.
     */
    override fun toFiles() {
        if (homeAsked || state.value.folder != null) return
        homeAsked = true
        lifecycleScope.launch {
            try {
                turn()
                val home = lembas.instances.defaultClient()?.files("/")?.path ?: return@launch
                state.update { it.copy(folder = it.folder ?: home) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                homeAsked = false
                state.update { it.copy(error = filesSay(e)) }
            }
        }
    }

    /**
     * Keep what was shared in the person's files: each file streamed into the folder as it is,
     * the text (when there is any) as a note beside them. No chat is made and no model is asked.
     * What the instance refuses -- a file over its limit, a folder that may not be written --
     * is said in its own sentence, and the sheet stays; Save again goes on from the file that
     * failed, into the same folder, and does not send the ones before it twice.
     */
    override fun saveToFiles() {
        val now = state.value
        val folder = now.folder ?: return
        if (now.sending) return
        state.update { it.copy(sending = true, error = null) }
        lifecycleScope.launch {
            try {
                val api = lembas.instances.defaultClient() ?: run {
                    startActivity(Intent(this@ShareActivity, MainActivity::class.java))
                    finish()
                    return@launch
                }
                // The files, then the text as a note (null): one queue, so a second try knows where it was.
                val items: List<LocalFile?> = if (now.text.isNotBlank()) files + listOf<LocalFile?>(null) else files
                kept = kept.into(folder)
                sendFrom(items, kept.done, { turn() }, { kept = kept.copy(done = it) }) { file ->
                    if (file != null) {
                        api.uploadFrom(this@ShareActivity, file, folder)
                    } else {
                        // The note is new each time: never over one that is there.
                        api.saveFile(FileKinds.child(folder, sharedNoteName(java.time.LocalDateTime.now())), now.text + "\n", create = true)
                    }
                }
                Toast.makeText(this@ShareActivity, R.string.files_uploaded, Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update { it.copy(sending = false, error = filesSay(e)) }
            }
        }
    }

    override fun send(ask: String, toLast: Boolean) {
        if (state.value.sending) return
        state.update { it.copy(sending = true, error = null) }
        lifecycleScope.launch {
            try {
                val store = lembas.instances
                val instance = store.default() ?: run {
                    startActivity(Intent(this@ShareActivity, MainActivity::class.java))
                    finish()
                    return@launch
                }
                val api = store.clientFor(instance)
                val now = state.value
                val into = if (toLast) now.last?.chatId else null
                // What a first try already uploaded for this chat is not uploaded again.
                if (attached.first != into) attached = into to emptyList()
                val ids = attached.second.toMutableList()
                sendFrom(files, ids.size, { turn() }, { attached = into to ids.toList() }) { file ->
                    val bytes = withContext(Dispatchers.IO) { SharedSource.open(this@ShareActivity, file.uri).use { it.readBytes() } }
                    ids += api.upload(file.name, file.mime, bytes, into).id
                }
                val sent = ids.toList()
                val message = shareMessage(ask, now.text, getString(R.string.share_default_prompt))
                turn()
                val chatId = if (into != null) {
                    api.sendMessage(into, message, sent)
                    into
                } else {
                    val prefs = store.prefs.first()
                    // The assistant's model while the instance still has it; a model that is
                    // gone is not asked for (the instance's default answers instead).
                    val chosen = prefs.forInstance(instance.id).assistantModel
                    val gone = chosen.isNotBlank() && runCatching { api.models() }.getOrNull()?.let { it.named(chosen) == null } == true
                    turn()
                    val made = api.createChat(message, sent, model = chosen.takeIf { it.isNotBlank() && !gone })
                    store.savePrefs { p ->
                        p.withInstance(instance.id) {
                            it.copy(
                                lastAssistChat = made.chat.id,
                                lastAssistTitle = ask.ifBlank { now.text.ifBlank { now.files.firstOrNull()?.name.orEmpty() } }.take(120),
                                lastAssistAt = System.currentTimeMillis(),
                            )
                        }
                    }
                    made.chat.id
                }
                startActivity(
                    Intent(this@ShareActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(Notifications.EXTRA_CHAT, chatId)
                        .putExtra(Notifications.EXTRA_INSTANCE, instance.id),
                )
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The last chat deleted since: it is not offered again.
                if (e is ApiException && e.status == 404 && toLast) {
                    val id = lembas.instances.default()?.id
                    lembas.instances.savePrefs { p ->
                        p.withInstance(id) { it.copy(lastAssistChat = "", lastAssistTitle = "", lastAssistAt = 0) }
                    }
                    state.update { it.copy(last = null) }
                }
                state.update { it.copy(sending = false, error = if (e is SharedRefused) filesSay(e) else explain(e)) }
            }
        }
    }
}
