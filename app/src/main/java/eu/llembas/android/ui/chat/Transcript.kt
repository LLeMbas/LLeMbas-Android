package eu.llembas.android.ui.chat

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.llembas.android.R
import eu.llembas.android.api.Attachment
import eu.llembas.android.api.Message
import eu.llembas.android.api.ShowCard
import eu.llembas.android.api.Step
import eu.llembas.android.api.ToolCall
import eu.llembas.android.api.ending
import eu.llembas.android.lembas
import eu.llembas.android.ui.MarkdownText
import eu.llembas.android.ui.Segment
import eu.llembas.android.ui.highlight
import eu.llembas.android.ui.progressLineOf
import eu.llembas.android.ui.splitFences
import eu.llembas.android.ui.theme.LocalPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Copies a text to the clipboard (and says so where Android does not). Provided by the chat. */
val LocalCopy = compositionLocalOf<(String) -> Unit> { {} }

private val Bubble = RoundedCornerShape(topStart = 20.dp, topEnd = 6.dp, bottomStart = 20.dp, bottomEnd = 20.dp)
private val Block = RoundedCornerShape(12.dp)
private val Pill = RoundedCornerShape(50)

/**
 * The person's turn: their words in a bubble on the right (never markdown), what they attached
 * above it as thumbnails. A long press offers Copy and, where the turn can still change, Edit.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserMessage(message: Message, canEdit: Boolean, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val copy = LocalCopy.current
    var menu by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().padding(start = 48.dp), horizontalAlignment = Alignment.End) {
        if (message.attachments.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                message.attachments.forEach { AttachmentTile(it, side = 96.dp) }
            }
        }
        if (!message.text.isNullOrBlank()) {
            Box {
                Text(
                    message.text,
                    color = palette.ink,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .clip(Bubble)
                        .background(palette.bubbleUser)
                        .combinedClickable(onClick = {}, onLongClick = { menu = true })
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                        text = { Text(stringResource(R.string.copy_message)) },
                        onClick = {
                            menu = false
                            copy(message.text)
                        },
                    )
                    if (canEdit) {
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                            text = { Text(stringResource(R.string.edit)) },
                            onClick = {
                                menu = false
                                onEdit()
                            },
                        )
                    }
                }
            }
        }
        if (message.queued) {
            // The server's queued progress ("Queued, 2 ahead · 12s") where it sends one.
            Text(
                progressLineOf(message.progress) ?: stringResource(R.string.queued),
                style = MaterialTheme.typography.labelSmall,
                color = palette.inkFaint,
                modifier = Modifier.padding(top = 4.dp, end = 4.dp),
            )
        }
    }
}

/**
 * The model's turn, full width and without a bubble: its steps (thinking and tools folded into
 * chips that open), its words as markdown, and under it Copy, Regenerate and what it cost. While
 * it is being written, a quiet pulse says so. A long press on the words offers the same, and
 * Select text.
 */
@Composable
fun AssistantMessage(
    message: Message,
    steps: List<Step>,
    writing: Boolean,
    canRegenerate: Boolean,
    onRegenerate: () -> Unit,
    onShow: ((ShowCard) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val copy = LocalCopy.current
    val readAloud = LocalReadAloud.current
    var menu by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    val words = replyText(message.copy(steps = steps))
    Column(modifier.fillMaxWidth()) {
        Box {
            StepsCompact(steps, onShow = onShow, onLongPress = { menu = true })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                    text = { Text(stringResource(R.string.copy_message)) },
                    onClick = {
                        menu = false
                        copy(words)
                    },
                )
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Filled.SelectAll, contentDescription = null) },
                    text = { Text(stringResource(R.string.select_text)) },
                    onClick = {
                        menu = false
                        selecting = true
                    },
                )
                if (canRegenerate) {
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                        text = { Text(stringResource(R.string.regenerate)) },
                        onClick = {
                            menu = false
                            onRegenerate()
                        },
                    )
                }
            }
        }
        if (writing) {
            Writing(Modifier.padding(top = if (steps.isEmpty()) 0.dp else 8.dp), withLine = steps.isEmpty())
        } else {
            // How a reply that did not finish ended (display.md §4): the progress text alone --
            // "Stopped: by you", "Failed: …" -- staying at the end of the reply; an error in
            // the error colour, a stop muted. Nothing else says it a second time.
            message.ending()?.let { end ->
                Text(
                    progressLineOf(end, withElapsed = false) ?: message.error.orEmpty(),
                    color = if (end.phase == "failed") palette.danger else palette.inkMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            // The actions and the foot share one row: the icons keep their 48 dp, the foot gives.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                if (words.isNotBlank()) {
                    IconButton(onClick = { copy(words) }) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = stringResource(R.string.copy_message),
                            tint = palette.inkFaint,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                if (readAloud != null && words.isNotBlank()) {
                    val reading = readAloud.reading == message.id
                    IconButton(onClick = { readAloud.toggle(message.id, words) }) {
                        Icon(
                            if (reading) Icons.Filled.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = stringResource(if (reading) R.string.assist_stop_reading else R.string.assist_read_aloud),
                            tint = if (reading) palette.accent else palette.inkFaint,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                if (canRegenerate) {
                    IconButton(onClick = onRegenerate) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.regenerate),
                            tint = palette.inkFaint,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                footLine(message)?.let { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.inkFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                }
            }
        }
    }
    if (selecting) {
        AlertDialog(
            onDismissRequest = { selecting = false },
            confirmButton = { TextButton(onClick = { selecting = false }) { Text(stringResource(R.string.close)) } },
            text = {
                SelectionContainer(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    Text(words, style = MaterialTheme.typography.bodyMedium)
                }
            },
        )
    }
}

/** A reply's own words, as the person would copy them: its text steps, in order. */
fun replyText(message: Message): String =
    message.steps.filter { it.kind == "text" }.mapNotNull { it.markdown }.joinToString("\n\n").ifBlank { message.text.orEmpty() }

/** What a finished reply cost, in one quiet line: model, tokens, speed. Null when nothing was counted. */
private fun footLine(message: Message): String? {
    val m = message.metrics ?: return message.model
    if (m.completionTokens == 0 && m.elapsedMs == 0L) return message.model
    return buildList {
        message.model?.let { add(it) }
        if (m.completionTokens > 0) add("${if (m.estimated) "~" else ""}${m.completionTokens} tok")
        if (m.tokPerSecond > 0) add("${"%.1f".format(m.tokPerSecond)} tok/s")
    }.joinToString(" · ").ifBlank { null }
}

/** Three dots that breathe, and (before the first word) the loading line beside them. */
@Composable
fun Writing(modifier: Modifier = Modifier, withLine: Boolean = false) {
    val palette = LocalPalette.current
    // Still in a preview or a screenshot, where an animation that never ends never lets the
    // picture be taken.
    val still = LocalInspectionMode.current
    val pulse = rememberInfiniteTransition(label = "writing")
    Row(modifier.heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val alpha = if (still) {
                listOf(1f, 0.6f, 0.3f)[i]
            } else {
                pulse.animateFloat(
                    initialValue = 0.25f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(600, delayMillis = i * 150), RepeatMode.Reverse),
                    label = "dot$i",
                ).value
            }
            Box(Modifier.padding(end = 4.dp).size(8.dp).alpha(alpha).clip(CircleShape).background(palette.accent))
        }
        if (withLine) {
            Text(
                stringResource(R.string.loading_line),
                color = palette.inkFaint,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * A reply's steps in the order they happened. Prose is markdown; thinking and each round of
 * tools are one chip that opens; a `show` card stays in view, because it is something put in
 * front of the person.
 */
@Composable
fun StepsCompact(steps: List<Step>, modifier: Modifier = Modifier, onShow: ((ShowCard) -> Unit)? = null, onLongPress: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        steps.forEach { step ->
            when (step.kind) {
                "thinking" -> ThinkingChip(step)
                "text" -> RichMarkdown(step.markdown.orEmpty(), onLongPress = onLongPress)
                "tools" -> ToolsChip(step, onShow)
                "steer" -> step.messages.forEach { Steered(it.text) }
            }
        }
    }
}

/** Markdown with its fenced code taken out into [CodeBlock]s, each with its own Copy. */
@Composable
fun RichMarkdown(markdown: String, modifier: Modifier = Modifier, onLongPress: (() -> Unit)? = null) {
    val segments = remember(markdown) { splitFences(markdown) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        segments.forEach { segment ->
            when (segment) {
                is Segment.Prose -> MarkdownText(segment.markdown, modifier = Modifier.fillMaxWidth(), onLongPress = onLongPress)
                is Segment.Code -> CodeBlock(segment.language, segment.code)
            }
        }
    }
}

/** A code block: its language and Copy above, the code below in its own sideways scroller. */
@Composable
fun CodeBlock(language: String, code: String, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val copy = LocalCopy.current
    Column(modifier.fillMaxWidth().clip(Block).background(palette.codeBg).border(1.dp, palette.codeBorder, Block)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                language.ifBlank { stringResource(R.string.code) },
                style = MaterialTheme.typography.labelMedium,
                color = palette.inkFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { copy(code) }) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.copy_code),
                    tint = palette.inkMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        HorizontalDivider(color = palette.codeBorder)
        // The fence's language in the theme's colours (ui/Highlight.kt); plain when it names none it reads.
        val shown = remember(code, language, palette) { highlight(code, language, palette) }
        Text(
            shown,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = palette.ink,
            softWrap = false,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(14.dp),
        )
    }
}

/** A chip that opens: the icon, one line, and a chevron; [content] below when open. */
@Composable
private fun FoldChip(
    open: Boolean,
    onToggle: () -> Unit,
    leading: @Composable () -> Unit,
    label: String,
    monospace: Boolean = false,
    tint: androidx.compose.ui.graphics.Color? = null,
    content: @Composable () -> Unit,
) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth()) {
        Surface(onClick = onToggle, shape = Pill, color = palette.surfaceRaised, border = androidx.compose.foundation.BorderStroke(1.dp, palette.border)) {
            Row(Modifier.padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) { leading() }
                Spacer(Modifier.width(8.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = if (monospace) FontFamily.Monospace else null,
                    color = tint ?: palette.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                    if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = palette.inkFaint,
                    modifier = Modifier.padding(start = 4.dp).size(18.dp),
                )
            }
        }
        AnimatedVisibility(open) { Box(Modifier.padding(top = 8.dp)) { content() } }
    }
}

@Composable
private fun ThinkingChip(step: Step) {
    val palette = LocalPalette.current
    var open by rememberSaveable(step.index, step.open) { mutableStateOf(false) }
    val label = if (step.open) stringResource(R.string.thinking) + "…" else stringResource(R.string.thought) + (step.label?.let { " $it" } ?: "")
    FoldChip(
        open = open,
        onToggle = { open = !open },
        leading = { Icon(Icons.Filled.Psychology, contentDescription = null, tint = palette.inkFaint, modifier = Modifier.size(18.dp)) },
        label = label,
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 24.dp)) {
            Box(Modifier.width(2.dp).heightIn(min = 24.dp).background(palette.border))
            Text(
                step.text.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkMuted,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

/**
 * One round of tools as one chip: a single call by its title ("bake --slow"), several by their
 * count. Open, each call is its block (command, change, output). A `show` card and a picture
 * the instance made are drawn apart, always in view.
 */
@Composable
private fun ToolsChip(step: Step, onShow: ((ShowCard) -> Unit)?) {
    val palette = LocalPalette.current
    val (shows, calls) = step.calls.partition { it.show != null && it.status != "error" && it.error.isNullOrEmpty() }
    if (calls.isNotEmpty()) {
        var open by rememberSaveable(step.index, calls.firstOrNull()?.id) { mutableStateOf(false) }
        val failed = calls.any { it.status == "error" || !it.error.isNullOrEmpty() }
        val running = calls.any { it.status == "running" || it.status == "pending" }
        val single = calls.singleOrNull()
        val label = single?.let { it.block.title.ifBlank { it.name } } ?: stringResource(R.string.tools_used, calls.size.toString())
        FoldChip(
            open = open,
            onToggle = { open = !open },
            leading = {
                if (running) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                } else {
                    Icon(
                        iconFor(single?.block?.kind ?: "tool"),
                        contentDescription = null,
                        tint = if (failed) palette.danger else palette.inkFaint,
                        modifier = Modifier.size(18.dp),
                    )
                }
            },
            label = label,
            monospace = single?.block?.kind == "shell",
            tint = if (failed) palette.danger else null,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { calls.forEach { ToolCallBlock(it, onShow = onShow) } }
        }
    }
    shows.forEach { call -> ToolCallBlock(call, onShow = onShow) }
    // A picture the instance made stays in view, like a `show` card: it is the answer.
    step.calls.mapNotNull { call -> call.image?.takeIf { it.id.isNotBlank() && call.status != "error" && call.error.isNullOrEmpty() } }
        .forEach { GeneratedPicture(it) }
}

@Composable
private fun Steered(text: String) {
    val palette = LocalPalette.current
    Text("↳ $text", style = MaterialTheme.typography.bodySmall, color = palette.inkMuted, modifier = Modifier.padding(start = 24.dp))
}

// --- Attachments ---------------------------------------------------------------------------

/**
 * Attached pictures, fetched once with the token and kept small in memory; a picture that could
 * not be read is not asked for again until the app starts anew.
 */
private object Thumbnails {
    private const val SIDE = 320
    private val cache = LruCache<String, ImageBitmap>(32)
    private val failed = mutableSetOf<String>()

    fun cached(id: String): ImageBitmap? = cache.get(id)

    suspend fun load(context: Context, id: String): ImageBitmap? {
        cache.get(id)?.let { return it }
        if (id in failed) return null
        val api = context.lembas.instances.client() ?: return null
        val bytes = runCatching { api.attachmentBytes(id) }.getOrNull()
        val image = bytes?.let { decode(it) }
        if (image == null) failed += id else cache.put(id, image)
        return image
    }

    private suspend fun decode(bytes: ByteArray): ImageBitmap? = withContext(Dispatchers.Default) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIDE) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }
}

/**
 * An attachment as a square tile of [side]: a picture as its thumbnail, anything else as its
 * kind's icon and its name. The tile's size is fixed, so a row of them lines up whatever the
 * names.
 */
@Composable
fun AttachmentTile(attachment: Attachment, side: Dp, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    Box(modifier.size(side).clip(Block).background(palette.surfaceRaised).border(1.dp, palette.border, Block), contentAlignment = Alignment.Center) {
        if (attachment.image) {
            var image by remember(attachment.id) { mutableStateOf(Thumbnails.cached(attachment.id)) }
            val still = LocalInspectionMode.current
            LaunchedEffect(attachment.id) { if (image == null && !still) image = Thumbnails.load(context, attachment.id) }
            val shown = image
            if (shown != null) {
                Image(shown, contentDescription = attachment.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Filled.Image, contentDescription = attachment.name, tint = palette.inkFaint)
            }
        } else {
            Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Description, contentDescription = null, tint = palette.inkMuted)
                Spacer(Modifier.height(4.dp))
                Text(
                    attachment.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.inkMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = side - 16.dp),
                )
            }
        }
    }
}
