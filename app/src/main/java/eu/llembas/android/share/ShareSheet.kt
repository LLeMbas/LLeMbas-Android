package eu.llembas.android.share

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.llembas.android.R
import eu.llembas.android.assist.LastAssist
import eu.llembas.android.ui.theme.LocalPalette

/** One shared file, as the sheet names it. */
data class SharedFile(val name: String, val image: Boolean)

/** What was shared, where it may go, and how sending it went. */
data class ShareState(
    val signedIn: Boolean = true,
    val text: String = "",
    val files: List<SharedFile> = emptyList(),
    /** The last chat the assistant made, offered as the other place to send it. */
    val last: LastAssist? = null,
    val sending: Boolean = false,
    val error: String? = null,
)

/** What the share sheet can do; nothing by default, for the screenshot tests. */
interface ShareActions {
    /** Send it with [ask] (empty: the default request) to a new chat, or [toLast] to the last assistant chat. */
    fun send(ask: String, toLast: Boolean) {}

    fun cancel() {}
}

/**
 * The share sheet's contents, from [state] alone: what came in (the text, the files by name),
 * what to do with it (a line of the person's own, optional), where it goes -- a new chat, or the
 * last chat the assistant made -- and Send. Rows of unknown height stack; the two choices are
 * whole-row tap targets of at least 56 dp.
 */
@Composable
fun ColumnScope.ShareSheetContent(state: ShareState, actions: ShareActions, handle: Modifier = Modifier) {
    val palette = LocalPalette.current
    var ask by remember { mutableStateOf("") }
    var toLast by remember { mutableStateOf(false) }
    val last = state.last

    Column(handle.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 36.dp, height = 4.dp).background(palette.inkFaint, RoundedCornerShape(2.dp)))
        }
        Text(
            stringResource(R.string.share_label),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        if (state.sending) {
            val bar = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(2.dp)
            if (LocalInspectionMode.current) LinearProgressIndicator(progress = { 0.4f }, modifier = bar) else LinearProgressIndicator(bar)
        }
    }

    if (!state.signedIn) {
        Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.assist_signed_out), style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // What came in.
        if (state.text.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = palette.surfaceRaised,
                border = BorderStroke(1.dp, palette.border),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    state.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.inkMuted,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        state.files.forEach { file ->
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (file.image) Icons.Filled.Image else Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }

        // What to do with it.
        TextField(
            value = ask,
            onValueChange = { ask = it },
            placeholder = { Text(stringResource(R.string.share_ask_hint)) },
            shape = RoundedCornerShape(20.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = palette.surfaceRaised,
                unfocusedContainerColor = palette.surfaceRaised,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            maxLines = 4,
            enabled = !state.sending,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        )

        // Where it goes.
        if (last != null) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = palette.surfaceRaised,
                border = BorderStroke(1.dp, palette.border),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    Destination(Icons.Filled.EditNote, stringResource(R.string.new_chat), null, selected = !toLast, enabled = !state.sending) { toLast = false }
                    Destination(Icons.Filled.History, stringResource(R.string.assist_continue), last.title, selected = toLast, enabled = !state.sending) {
                        toLast =
                            true
                    }
                }
            }
        }
        state.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.danger) }
    }

    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = actions::cancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = { actions.send(ask, toLast && last != null) },
            enabled = !state.sending && (state.text.isNotBlank() || state.files.isNotEmpty()),
            colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.accentInk),
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text(stringResource(if (state.sending) R.string.share_sending else R.string.send)) }
    }
}

@Composable
private fun Destination(icon: ImageVector, title: String, detail: String?, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = palette.inkMuted, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            detail?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        RadioButton(selected = selected, onClick = null, enabled = enabled)
    }
}

/**
 * The message a share sends: the person's own words (or the default request) first, then what was
 * shared, so the model reads the request before the material.
 */
fun shareMessage(ask: String, shared: String, fallback: String): String {
    val request = ask.trim().ifBlank { fallback }
    return if (shared.isBlank()) request else "$request\n\n---\n\n${shared.trim()}"
}
