@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package eu.llembas.android.assist

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import eu.llembas.android.R
import eu.llembas.android.ui.chat.Steps

/**
 * The assistant's sheet: a question box, the microphone, "Include screen", and the answer as it
 * is written. Nothing from the account -- no chat list, no history -- is on it, which is what
 * lets it open over the lock screen (sending from there asks to unlock first: [locked]).
 */
@Composable
fun AssistPanel(controller: AssistController, locked: Boolean, onUnlockAndAsk: (String) -> Unit, onOpenApp: () -> Unit, onNeedMic: () -> Unit) {
    val state by controller.state.collectAsState()
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!state.signedIn) {
                Text(stringResource(R.string.assist_signed_out))
                TextButton(onClick = onOpenApp) { Text(stringResource(R.string.assist_open_app)) }
                return@Column
            }
            state.asked?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.reply?.let { reply ->
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Steps(reply.finished?.steps ?: reply.shown)
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.screenAvailable && !locked) {
                FilterChip(
                    selected = state.includeScreen,
                    onClick = { controller.includeScreen(!state.includeScreen) },
                    label = { Text(stringResource(R.string.assist_include_screen)) },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(if (state.listening) R.string.listening else R.string.assist_hint)) },
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
                IconButton(onClick = {
                    when {
                        locked -> onUnlockAndAsk(text)

                        state.listening -> controller.heard()

                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED -> controller.listen()

                        else -> onNeedMic()
                    }
                }) {
                    Icon(if (state.listening) Icons.Filled.Stop else Icons.Filled.Mic, contentDescription = stringResource(R.string.dictate))
                }
                IconButton(enabled = text.isNotBlank() && !state.busy, onClick = {
                    if (locked) onUnlockAndAsk(text) else controller.ask(text)
                    text = ""
                }) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send))
                }
            }
            if (state.chatId != null) {
                TextButton(onClick = onOpenApp) { Text(stringResource(R.string.assist_open_in_app)) }
            }
        }
    }
}
