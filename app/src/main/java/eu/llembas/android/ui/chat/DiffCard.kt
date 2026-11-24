package eu.llembas.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import eu.llembas.android.R
import eu.llembas.android.api.DiffLine
import eu.llembas.android.api.DiffSign
import eu.llembas.android.api.DiffView
import eu.llembas.android.api.FileDiff
import eu.llembas.android.api.view
import eu.llembas.android.api.visibly
import eu.llembas.android.ui.theme.LocalPalette
import eu.llembas.android.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

// An edit's diff on its approval card: per file its path, how many lines come and go, and the
// changed lines as text -- a sign before each and the theme's colour behind it, in one width of
// letter. The lines scroll sideways in a box of their own, so the card never does. Both texts
// are a model's and a file's words: one plain Text draws them, and nothing in them is rendered.

/** The most of one line that is drawn: a line of a megabyte is still one line of the card. */
internal const val DIFF_LINE_MOST = 400

private val DiffShape = RoundedCornerShape(12.dp)

/**
 * A line as the card writes it: its sign, then its text cut at [DIFF_LINE_MOST], with whatever
 * a person could not see in it -- a direction override, a zero-width space, a line separator --
 * written as its code point ([visibly]).
 */
internal fun diffRow(line: DiffLine): String {
    val sign = when (line.sign) {
        DiffSign.Added -> "+ "
        DiffSign.Removed -> "- "
        DiffSign.Same -> "  "
        DiffSign.Gap -> return "⋯"
    }
    val long = line.text.length > DIFF_LINE_MOST
    return sign + visibly(if (long) line.text.take(DIFF_LINE_MOST) else line.text) + if (long) "…" else ""
}

private fun diffText(lines: List<DiffLine>, palette: Palette): AnnotatedString = buildAnnotatedString {
    lines.forEachIndexed { at, line ->
        val style = when (line.sign) {
            DiffSign.Added -> SpanStyle(color = palette.success, background = palette.successSoft)
            DiffSign.Removed -> SpanStyle(color = palette.danger, background = palette.dangerSoft)
            DiffSign.Same -> SpanStyle(color = palette.ink)
            DiffSign.Gap -> SpanStyle(color = palette.inkFaint)
        }
        withStyle(style) { append(diffRow(line)) }
        if (at < lines.lastIndex) append('\n')
    }
}

/**
 * The diffs of an item, worked out: null until they are. Off the main thread -- two texts of a
 * megabyte are not compared inside a frame -- and one file after another, so fifty files hold
 * one comparison's memory at a time. A screenshot has no later frame: there it is done at once.
 */
@Composable
fun diffViews(diffs: List<FileDiff>): List<DiffView>? {
    if (diffs.isEmpty()) return emptyList()
    if (LocalInspectionMode.current) return remember(diffs) { diffs.map { it.view() } }
    val views by produceState<List<DiffView>?>(null, diffs) {
        value = withContext(Dispatchers.Default) {
            diffs.map {
                ensureActive()
                it.view()
            }
        }
    }
    return views
}

/** The diffs of one item of an approval card, a block per file; [views] as [diffViews] gave them. */
@Composable
fun CardDiffs(diffs: List<FileDiff>, views: List<DiffView>?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        diffs.forEachIndexed { at, diff -> key(at, diff.path) { FileDiffBlock(diff, views?.getOrNull(at)) } }
    }
}

/**
 * One file's change. The path gives (it wraps) and the two counts keep their width. The lines
 * are folded to the first few; "Show all" opens them, up to what a card lays out. Until [view]
 * is worked out the block is its path alone.
 */
@Composable
private fun FileDiffBlock(diff: FileDiff, view: DiffView?) {
    val palette = LocalPalette.current
    var open by rememberSaveable(diff.path) { mutableStateOf(false) }
    val path = remember(diff.path) { visibly(diff.path) }
    Column(Modifier.fillMaxWidth().clip(DiffShape).background(palette.codeBg).border(1.dp, palette.codeBorder, DiffShape)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = palette.ink)
                val state = when {
                    diff.deleted -> stringResource(R.string.diff_deleted)
                    diff.made -> stringResource(R.string.files_new_file)
                    else -> null
                }
                if (state != null) Text(state, style = MaterialTheme.typography.labelSmall, color = palette.inkMuted)
            }
            if (view != null) {
                Spacer(Modifier.width(12.dp))
                val counts = stringResource(R.string.diff_counts, view.added.toString(), view.removed.toString())
                Row(Modifier.clearAndSetSemantics { contentDescription = counts }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("+${view.added}", style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = palette.success)
                    Text("−${view.removed}", style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = palette.danger)
                }
            }
        }
        if (view != null) {
            // Said once, where every line would otherwise go and come with the same words.
            view.endings?.let { (from, to) -> DiffNote(stringResource(R.string.diff_endings, from, to)) }
            val lines = view.shown(open)
            if (lines.isNotEmpty()) {
                val text = remember(lines, palette) { diffText(lines, palette) }
                Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        softWrap = false,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                    )
                }
            }
            if (open && view.beyond > 0) DiffNote(stringResource(R.string.diff_more, view.beyond.toString()))
            if (view.cut) DiffNote(stringResource(R.string.diff_cut))
            if (view.folds) {
                TextButton(onClick = { open = !open }, modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 4.dp)) {
                    Text(if (open) stringResource(R.string.diff_show_less) else stringResource(R.string.diff_show_all, view.lines.size.toString()))
                }
            }
        }
    }
}

/** A sentence of the block's own, under its lines. */
@Composable
private fun DiffNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = LocalPalette.current.inkMuted,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
    )
}
