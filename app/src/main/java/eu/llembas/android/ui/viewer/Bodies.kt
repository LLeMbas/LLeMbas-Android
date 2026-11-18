package eu.llembas.android.ui.viewer

import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.llembas.android.R
import eu.llembas.android.api.Notebook
import eu.llembas.android.api.Table
import eu.llembas.android.ui.theme.LocalPalette
import java.util.Locale

// What the viewer draws for each kind of file, each from its state alone (the screenshot tests
// draw these). Everything a file holds is drawn as text or as pixels the app decoded itself:
// nothing here interprets a file -- no HTML, no script, no WebView.

/** What the details page can do; nothing by default, for the screenshot tests. */
interface FileActions {
    fun download() {}

    fun openWith() {}

    fun share() {}
}

/**
 * A file there is nothing to draw of (an archive, a 3D model, a binary, or one a viewer could
 * not show): what it is, [note] saying why it is not shown, and the three ways out. The
 * buttons stack: their words are of unknown length in nine languages.
 */
@Composable
fun DetailsBody(name: String, type: String, size: Long, note: String?, actions: FileActions) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, tint = palette.inkFaint, modifier = Modifier.size(56.dp))
        Text(name, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Column(Modifier.fillMaxWidth().border(1.dp, palette.border, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Fact(stringResource(R.string.files_type), type)
            Fact(stringResource(R.string.files_size), Formatter.formatShortFileSize(context, size))
        }
        note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.inkMuted, textAlign = TextAlign.Center) }
        OutlinedButton(onClick = actions::download, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.download)) }
        OutlinedButton(onClick = actions::openWith, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.files_open_with)) }
        OutlinedButton(onClick = actions::share, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.report_share)) }
    }
}

/** A label and its value on one line: the label keeps its width, the value takes the rest and wraps. */
@Composable
private fun Fact(label: String, value: String) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = palette.inkMuted, modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** The width every column of a table has: fixed, so a row's cells line up whatever they hold. */
private val COLUMN = 148.dp

/**
 * A CSV or TSV file as a grid: every cell one line in a column of one width, the first row as
 * the heading, the whole grid scrolling sideways as one and down as a list. Cells are text.
 */
@Composable
fun TableBody(table: Table) {
    val palette = LocalPalette.current
    val sideways = rememberScrollState()
    val style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = palette.ink)
    Column(Modifier.fillMaxSize()) {
        if (table.cut) Note(stringResource(R.string.table_cut))
        Box(Modifier.fillMaxSize().horizontalScroll(sideways)) {
            LazyColumn(Modifier.width(COLUMN * table.columns.coerceAtLeast(1))) {
                itemsIndexed(table.rows) { i, row ->
                    Row(Modifier.background(if (i == 0) palette.bgSunken else palette.bg)) {
                        for (c in 0 until table.columns.coerceAtLeast(1)) {
                            Text(
                                row.getOrElse(c) { "" },
                                style = if (i == 0) style.copy(fontWeight = FontWeight.SemiBold) else style,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(COLUMN).border(0.5.dp, palette.border).padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A quiet line above a body, saying what is not shown. */
@Composable
internal fun Note(text: String) {
    val palette = LocalPalette.current
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = palette.inkMuted,
        modifier = Modifier.fillMaxWidth().background(palette.bgSunken).padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/** A notebook, read-only: each cell's source as text, and under it the plain text it printed. */
@Composable
fun NotebookBody(notebook: Notebook) {
    val palette = LocalPalette.current
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp, color = palette.ink)
    if (notebook.cells.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.notebook_empty), color = palette.inkMuted, modifier = Modifier.padding(32.dp))
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(notebook.cells) { _, cell ->
            Column(Modifier.fillMaxWidth()) {
                Text(
                    if (cell.count != null) "${cell.kind} [${cell.count}]" else cell.kind,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.inkFaint,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                val code = cell.kind == "code"
                Text(
                    cell.source.ifEmpty { " " },
                    style = if (code) mono else MaterialTheme.typography.bodyMedium.copy(color = palette.ink),
                    // Code sits on the code's ground; a cell of prose is prose on the page.
                    modifier = Modifier.fillMaxWidth().then(if (code) Modifier.background(palette.codeBg, RoundedCornerShape(8.dp)).padding(10.dp) else Modifier),
                )
                if (cell.output.isNotEmpty()) {
                    Text(cell.output, style = mono.copy(color = palette.inkMuted), modifier = Modifier.fillMaxWidth().padding(start = 10.dp, top = 6.dp))
                }
                if (cell.cut) Text(stringResource(R.string.notebook_cell_cut), style = MaterialTheme.typography.labelSmall, color = palette.inkFaint)
            }
        }
        if (notebook.more > 0) {
            item {
                Text(stringResource(R.string.notebook_more, notebook.more.toString()), style = MaterialTheme.typography.bodySmall, color = palette.inkMuted)
            }
        }
    }
}

/** How far a picture zoomed to [scale] may be moved inside a view of [size]: never past its own edge. */
fun clampPan(offset: Offset, scale: Float, size: IntSize): Offset {
    val x = size.width * (scale - 1f) / 2f
    val y = size.height * (scale - 1f) / 2f
    return Offset(offset.x.coerceIn(-x, x), offset.y.coerceIn(-y, y))
}

const val ZOOM_MAX = 8f

/** A picture, whole: two fingers zoom and move it, a double tap zooms in and back. */
@Composable
fun ZoomableImage(bitmap: ImageBitmap, description: String) {
    val palette = LocalPalette.current
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .background(palette.bgSunken)
            .clipToBounds()
            .onSizeChanged { size = it }
            .pointerInput(bitmap) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, ZOOM_MAX)
                    offset = clampPan(offset + pan, scale, size)
                }
            }
            .pointerInput(bitmap) {
                detectTapGestures(
                    onDoubleTap = {
                        scale = if (scale > 1f) 1f else 3f
                        offset = Offset.Zero
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap,
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        )
    }
}

/** What a player shows: whether it can play yet, is playing, where it is and how long the file is. */
data class PlayerUi(val ready: Boolean = false, val playing: Boolean = false, val positionMs: Int = 0, val durationMs: Int = 0)

/** `m:ss`, or `h:mm:ss` from an hour on: a time read off a player, the same in every language. */
fun playTime(ms: Int): String {
    val all = (ms.coerceAtLeast(0) / 1000)
    val h = all / 3600
    val m = all % 3600 / 60
    val s = all % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}

/**
 * A player's controls: the place in the file as a slider the whole width, the two times under
 * its two ends, and Play or Pause in the middle (56 dp). Nothing here depends on a text's length.
 */
@Composable
fun PlayerControls(ui: PlayerUi, onToggle: () -> Unit, onSeek: (Int) -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    var dragging by remember { mutableStateOf<Float?>(null) }
    val length = ui.durationMs.coerceAtLeast(1).toFloat()
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Slider(
            value = (dragging ?: ui.positionMs.toFloat()).coerceIn(0f, length),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSeek(it.toInt()) }
                dragging = null
            },
            valueRange = 0f..length,
            enabled = ui.ready && ui.durationMs > 0,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(playTime((dragging ?: ui.positionMs.toFloat()).toInt()), style = MaterialTheme.typography.labelMedium, color = palette.inkMuted)
            Spacer(Modifier.weight(1f))
            Text(playTime(ui.durationMs), style = MaterialTheme.typography.labelMedium, color = palette.inkMuted)
        }
        Spacer(Modifier.height(4.dp))
        FilledIconButton(
            onClick = onToggle,
            enabled = ui.ready,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = palette.accent, contentColor = palette.accentInk),
            modifier = Modifier.size(56.dp),
        ) {
            Icon(
                if (ui.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(if (ui.playing) R.string.pause else R.string.play),
            )
        }
    }
}

/**
 * A text file being edited: the whole text in one field, in the code's type. The field is the
 * screen; saving and leaving are the bar's.
 */
@Composable
fun EditorBody(text: String, onChange: (String) -> Unit, enabled: Boolean = true) {
    val palette = LocalPalette.current
    BasicTextField(
        value = text,
        onValueChange = onChange,
        enabled = enabled,
        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp, color = palette.ink),
        cursorBrush = SolidColor(palette.accent),
        modifier = Modifier.fillMaxSize().background(palette.codeBg).padding(12.dp),
    )
}
