package eu.llembas.android.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import eu.llembas.android.MainActivity
import eu.llembas.android.R
import eu.llembas.android.lembas
import eu.llembas.android.push.Notifications
import eu.llembas.android.ui.theme.LembasTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Share to LLeMbas": text, a link, a picture or a file from any app starts a new chat with it.
 * Files are uploaded first and bound to the first message, as the composer's are.
 */
class ShareActivity : ComponentActivity() {
    private val failure = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LembasTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val error = failure.value
                        if (error == null) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.share_sending))
                        } else {
                            Text(error, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { finish() }) { Text(stringResource(R.string.close)) }
                        }
                    }
                }
            }
        }
        lifecycleScope.launch { share(intent) }
    }

    private suspend fun share(intent: Intent) {
        try {
            val api = lembas.instances.client() ?: run {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
                return
            }
            val text = listOfNotNull(
                intent.getStringExtra(Intent.EXTRA_SUBJECT),
                intent.getStringExtra(Intent.EXTRA_TEXT),
            ).joinToString("\n\n").trim()
            val uris = when (intent.action) {
                Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))

                Intent.ACTION_SEND_MULTIPLE ->
                    IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()

                else -> emptyList()
            }
            val files = uris.map { uri ->
                val (name, bytes) = withContext(Dispatchers.IO) {
                    val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: "shared"
                    name to (contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0))
                }
                api.upload(name, contentResolver.getType(uri) ?: "application/octet-stream", bytes).id
            }
            if (text.isBlank() && files.isEmpty()) {
                finish()
                return
            }
            val made = api.createChat(text.ifBlank { getString(R.string.share_default_prompt) }, files)
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(Notifications.EXTRA_CHAT, made.chat.id),
            )
            finish()
        } catch (e: Exception) {
            failure.value = e.message ?: e.javaClass.simpleName
        }
    }
}
