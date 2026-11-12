package eu.llembas.android.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.llembas.android.R
import eu.llembas.android.api.Message
import eu.llembas.android.api.ShowCard
import eu.llembas.android.api.Step
import eu.llembas.android.api.ToolCall
import eu.llembas.android.api.callDuration
import eu.llembas.android.api.state
import eu.llembas.android.ui.MarkdownText
import eu.llembas.android.ui.theme.LocalPalette

private val Rounded = RoundedCornerShape(12.dp)

/** A reply's steps, as the transcript draws them ([StepsCompact]); the assistant's sheet uses it. */
@Composable
fun Steps(steps: List<Step>, modifier: Modifier = Modifier, onShow: ((ShowCard) -> Unit)? = null) = StepsCompact(steps, modifier, onShow)

internal fun iconFor(kind: String): ImageVector = when (kind) {
    "shell" -> Icons.Filled.Terminal
    "edit" -> Icons.Filled.Edit
    "read" -> Icons.AutoMirrored.Filled.Article
    "search" -> Icons.Filled.Search
    "web" -> Icons.Filled.Language
    "task" -> Icons.Filled.Groups
    else -> Icons.Filled.Build
}

/**
 * One tool call, the way its kind is read (`services/tool_blocks.py`): a command shows the
 * command, an edit its change, a read the file. What came back is behind a tap, in its own
 * scroller, so a megabyte of output never becomes the page.
 */
@Composable
fun ToolCallBlock(call: ToolCall, depth: Int = 0, onShow: ((ShowCard) -> Unit)? = null) {
    val palette = LocalPalette.current
    var open by rememberSaveable(call.id ?: call.block.title) { mutableStateOf(call.block.kind == "edit") }
    val failed = call.status == "error" || !call.error.isNullOrEmpty()
    val shown = call.show
    if (shown != null && !failed) {
        ShowCardRow(shown, depth, onShow)
        return
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = (depth * 16).dp)
            .border(1.dp, if (failed) palette.danger else palette.border, Rounded)
            .clickable { open = !open }
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(iconFor(call.block.kind), contentDescription = null, modifier = Modifier.size(16.dp), tint = palette.inkMuted)
            Spacer(Modifier.width(6.dp))
            Text(
                call.block.title.ifBlank { call.name },
                fontFamily = if (call.block.kind == "shell") FontFamily.Monospace else null,
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (open) 6 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            call.block.chips.forEach { chip ->
                Text(chip, style = MaterialTheme.typography.labelSmall, color = palette.inkFaint, modifier = Modifier.padding(start = 6.dp))
            }
            // How long a finished call took (display.md §2); a running or a denied one shows none.
            callDuration(call.state(), call.ms)?.let { took ->
                Text(took, style = MaterialTheme.typography.labelSmall, color = palette.inkFaint, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
            }
        }
        call.purpose?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = palette.inkFaint)
        }
        AnimatedVisibility(open) {
            val output = listOfNotNull(call.diff, call.text, call.error).joinToString("\n").trim()
            Column {
                if (output.isNotEmpty()) {
                    Text(
                        output,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = if (failed) palette.danger else palette.ink,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .background(palette.codeBg, Rounded)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                            .padding(8.dp),
                    )
                }
                if (call.truncated) {
                    Text(stringResource(R.string.output_cut), style = MaterialTheme.typography.labelSmall, color = palette.inkFaint)
                }
                call.children?.forEach { ToolCallBlock(it, depth + 1, onShow) }
            }
        }
    }
}

/**
 * A `show` card: the file's name and path, and Open. Nothing of its contents -- the words here
 * are a path a model chose, drawn as text like everything else. The web page opens a card that
 * arrives while it is open by itself, beside the conversation, when the screen is wide enough;
 * a phone's screen is not, so here it waits for a tap.
 */
@Composable
private fun ShowCardRow(show: ShowCard, depth: Int, onShow: ((ShowCard) -> Unit)?) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = (depth * 16).dp)
            .background(palette.surfaceRaised, Rounded)
            .border(1.dp, palette.border, Rounded)
            .then(if (onShow != null) Modifier.clickable { onShow(show) } else Modifier)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Visibility, contentDescription = stringResource(R.string.shown), modifier = Modifier.size(18.dp), tint = palette.leaf)
        Spacer(Modifier.width(10.dp))
        // The one part that gives: a long path shortens, the button keeps its size.
        Column(Modifier.weight(1f)) {
            Text(show.name.ifBlank { show.path }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                show.path + if (show.line > 0) ":${show.line}" else "",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = palette.inkFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onShow != null) {
            TextButton(onClick = { onShow(show) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.viewer_open))
            }
        }
    }
}
