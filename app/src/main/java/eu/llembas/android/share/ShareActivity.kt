package eu.llembas.android.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import eu.llembas.android.AppLanguage
import eu.llembas.android.MainActivity
import eu.llembas.android.R
import eu.llembas.android.api.ApiException
import eu.llembas.android.assist.AssistSheetFrame
import eu.llembas.android.assist.LastAssist
import eu.llembas.android.auth.Prefs
import eu.llembas.android.lembas
import eu.llembas.android.push.Notifications
import eu.llembas.android.ui.explain
import eu.llembas.android.ui.theme.LembasTheme
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
 */
class ShareActivity :
    ComponentActivity(),
    ShareActions {
    private val state = MutableStateFlow(ShareState())
    private var uris: List<Uri> = emptyList()

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        val text = listOfNotNull(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n\n")
            .trim()
        state.update { it.copy(text = text, files = uris.map { uri -> SharedFile(nameOf(uri), (contentResolver.getType(uri) ?: "").startsWith("image/")) }) }
        lifecycleScope.launch {
            val store = lembas.instances
            val prefs = store.prefs.first()
            state.update {
                it.copy(
                    signedIn = store.current() != null,
                    last = prefs.lastAssistChat.takeIf { id -> id.isNotBlank() && !prefs.assistantTemporary }
                        ?.let { id -> LastAssist(id, prefs.lastAssistTitle, prefs.lastAssistAt) },
                )
            }
        }
        setContent {
            val prefs by lembas.instances.prefs.collectAsState(initial = Prefs())
            val shown by state.collectAsState()
            LembasTheme(prefs.theme) {
                AssistSheetFrame(onDismiss = ::cancel) { handle, _ -> ShareSheetContent(shown, this@ShareActivity, handle) }
            }
        }
    }

    override fun cancel() = finish()

    override fun send(ask: String, toLast: Boolean) {
        if (state.value.sending) return
        state.update { it.copy(sending = true, error = null) }
        lifecycleScope.launch {
            try {
                val store = lembas.instances
                val api = store.client() ?: run {
                    startActivity(Intent(this@ShareActivity, MainActivity::class.java))
                    finish()
                    return@launch
                }
                val now = state.value
                val into = if (toLast) now.last?.chatId else null
                val files = uris.map { uri ->
                    val bytes = withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0) }
                    api.upload(nameOf(uri), contentResolver.getType(uri) ?: "application/octet-stream", bytes, into).id
                }
                val message = shareMessage(ask, now.text, getString(R.string.share_default_prompt))
                val chatId = if (into != null) {
                    api.sendMessage(into, message, files)
                    into
                } else {
                    val prefs = store.prefs.first()
                    // The assistant's model while the instance still has it; a model that is
                    // gone is not asked for (the instance's default answers instead).
                    val chosen = prefs.assistantModel
                    val gone = chosen.isNotBlank() && runCatching { api.models() }.getOrNull()?.none { it.id == chosen } == true
                    val made = api.createChat(message, files, model = chosen.takeIf { it.isNotBlank() && !gone })
                    store.savePrefs {
                        it.copy(
                            lastAssistChat = made.chat.id,
                            lastAssistTitle = ask.ifBlank { now.text.ifBlank { now.files.firstOrNull()?.name.orEmpty() } }.take(120),
                            lastAssistAt = System.currentTimeMillis(),
                        )
                    }
                    made.chat.id
                }
                startActivity(
                    Intent(this@ShareActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(Notifications.EXTRA_CHAT, chatId),
                )
                finish()
            } catch (e: Exception) {
                // The last chat deleted since: it is not offered again.
                if (e is ApiException && e.status == 404 && toLast) {
                    lembas.instances.savePrefs { it.copy(lastAssistChat = "", lastAssistTitle = "", lastAssistAt = 0) }
                    state.update { it.copy(last = null) }
                }
                state.update { it.copy(sending = false, error = explain(e)) }
            }
        }
    }

    private fun nameOf(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment ?: "shared"
}
