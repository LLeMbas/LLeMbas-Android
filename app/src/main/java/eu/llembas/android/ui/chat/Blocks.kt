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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import eu.llembas.android.api.Step
import eu.llembas.android.api.ToolCall
import eu.llembas.android.ui.MarkdownText
import eu.llembas.android.ui.theme.LocalPalette

private val Rounded = RoundedCornerShape(10.dp)

/** The person's own turn: their words as typed (never markdown), and what they attached. */
@Composable
fun UserBubble(message: Message, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = 48.dp)
            .background(palette.bubbleUser, Rounded)
            .border(1.dp, palette.border, Rounded)
            .padding(12.dp),
    ) {
        if (!message.text.isNullOrBlank()) Text(message.text, color = palette.ink)
        message.attachments.forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Icon(Icons.Filled.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp), tint = palette.inkMuted)
                Spacer(Modifier.width(4.dp))
                Text(a.name, style = MaterialTheme.typography.bodySmall, color = palette.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (message.queued) {
            Text(stringResource(R.string.queued), style = MaterialTheme.typography.labelSmall, color = palette.inkFaint)
        }
    }
}

/** A reply's steps, in the order they happened: the same table of contents the web page draws. */
@Composable
fun Steps(steps: List<Step>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        steps.forEach { step ->
            when (step.kind) {
                "thinking" -> Thinking(step)
                "text" -> MarkdownText(step.markdown.orEmpty(), modifier = Modifier.fillMaxWidth())
                "tools" -> step.calls.forEach { ToolCallBlock(it) }
                "steer" -> step.messages.forEach { Steered(it.text) }
            }
        }
    }
}

@Composable
private fun Thinking(step: Step) {
    val palette = LocalPalette.current
    var open by rememberSaveable(step.index, step.open) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().background(palette.bgSunken, Rounded).clickable { open = !open }.padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Psychology, contentDescription = null, modifier = Modifier.size(16.dp), tint = palette.inkFaint)
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(if (step.open) R.string.thinking else R.string.thought) + (step.label?.let { " $it" } ?: ""),
                style = MaterialTheme.typography.labelMedium,
                color = palette.inkMuted,
            )
        }
        AnimatedVisibility(open) {
            Text(step.text.orEmpty(), style = MaterialTheme.typography.bodySmall, color = palette.inkMuted, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun Steered(text: String) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(start = 24.dp)) {
        Text("↳ $text", style = MaterialTheme.typography.bodySmall, color = palette.inkMuted)
    }
}

private fun iconFor(kind: String): ImageVector = when (kind) {
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
fun ToolCallBlock(call: ToolCall, depth: Int = 0) {
    val palette = LocalPalette.current
    var open by rememberSaveable(call.id ?: call.block.title) { mutableStateOf(call.block.kind == "edit") }
    val failed = call.status == "error" || !call.error.isNullOrEmpty()
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
                call.children?.forEach { ToolCallBlock(it, depth + 1) }
            }
        }
    }
}

/** A finished or failed reply's foot: what it cost, and why it stopped if it did. */
@Composable
fun ReplyFoot(message: Message) {
    val palette = LocalPalette.current
    message.error?.let {
        Text(it, color = palette.danger, style = MaterialTheme.typography.bodySmall)
    }
    val m = message.metrics ?: return
    if (m.completionTokens == 0 && m.elapsedMs == 0L) return
    val parts = buildList {
        message.model?.let { add(it) }
        if (m.completionTokens > 0) add("${if (m.estimated) "~" else ""}${m.completionTokens} tok")
        if (m.tokPerSecond > 0) add("${"%.1f".format(m.tokPerSecond)} tok/s")
        if (m.percent > 0) add("${m.percent}%")
    }
    Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = palette.inkFaint, modifier = Modifier.widthIn(max = 600.dp))
}
