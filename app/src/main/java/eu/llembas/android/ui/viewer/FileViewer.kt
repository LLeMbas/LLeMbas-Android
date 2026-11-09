@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package eu.llembas.android.ui.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.llembas.android.R
import eu.llembas.android.api.ApiClient
import eu.llembas.android.api.ApiException
import eu.llembas.android.api.ShowCard
import eu.llembas.android.lembas
import eu.llembas.android.ui.MarkdownText
import eu.llembas.android.ui.explain
import eu.llembas.android.ui.theme.LocalPalette
import java.io.IOException

/**
 * Markdown longer than this is shown as its lines instead. A TextView holding a whole megabyte
 * of rendered Markdown stalls the phone; the same text as a lazy list of lines does not.
 */
private const val MARKDOWN_MAX_CHARS = 200_000

/** Where a file is on its way into the viewer. */
private sealed interface Loaded {
    data object Loading : Loaded

    /** [kind]: what the server said the file is, when it is only known once it came. */
    data class Text(val text: String, val kind: String? = null) : Loaded

    /** [unavailable]: this server has no door for the app to read the file through. */
    data class Failed(val message: String, val unavailable: Boolean = false) : Loaded
}

/** What the viewer's title and body need to know before the text comes. */
private data class Viewed(val name: String, val path: String, val line: Int, val kind: String)

/**
 * What a `show` card opens: the file, full screen, read-only, as the chat sees it now.
 *
 * Everything in it came from a model and is untrusted, as on the web page, so the rules are the
 * same ones the transcript keeps: Markdown goes through [MarkdownText] (no images, no HTML, links
 * outside for http, https and mailto only); code and text are drawn as lines, never interpreted.
 * **HTML is shown as its source, never rendered** -- the app has no WebView, and a page a model
 * wrote does not get one here. The web UI renders it in its sealed frame (an opaque origin with
 * no network), and "Open in the browser" goes there.
 */
@Composable
fun FileViewer(chatId: String, show: ShowCard, onClose: () -> Unit) {
    Viewer(
        file = Viewed(show.name, show.path, show.line, show.viewKind),
        key = chatId to show,
        webUrl = { it.webViewer(chatId, show) },
        load = { api, _ -> Loaded.Text(api.shownText(chatId, show)) },
        onClose = onClose,
    )
}

/**
 * A file of the person's tree (the Files screen), in the same viewer and under the same rules:
 * the person's own files are no more trusted than a model's, since a model may have written
 * them. A file the server withholds (not text, over 1 MB) says why, and offers the web UI.
 */
@Composable
fun TreeFileViewer(path: String, onClose: () -> Unit) {
    Viewer(
        file = Viewed(path.substringAfterLast('/').ifBlank { path }, path, 0, "text"),
        key = path,
        webUrl = { it.webFile(path) },
        load = { api, context ->
            val file = api.fileContent(path)
            val withheld = file.withheld
            when {
                withheld == null -> Loaded.Text(file.text, file.viewKind)
                withheld.message.isNotBlank() -> Loaded.Failed(withheld.message)
                withheld.reason == "too_large" -> Loaded.Failed(context.getString(R.string.viewer_too_large))
                else -> Loaded.Failed(context.getString(R.string.viewer_not_text))
            }
        },
        onClose = onClose,
    )
}

@Composable
private fun Viewer(file: Viewed, key: Any, webUrl: (ApiClient) -> String?, load: suspend (ApiClient, Context) -> Loaded, onClose: () -> Unit) {
    val context = LocalContext.current
    val palette = LocalPalette.current
    var loaded by remember(key) { mutableStateOf<Loaded>(Loaded.Loading) }
    var web by remember(key) { mutableStateOf<String?>(null) }

    LaunchedEffect(key) {
        val api = context.lembas.instances.client()
        if (api == null) {
            loaded = Loaded.Failed(context.getString(R.string.assist_signed_out))
            return@LaunchedEffect
        }
        web = webUrl(api)
        loaded = try {
            load(api, context)
        } catch (e: ApiException) {
            when {
                e.status == 413 -> Loaded.Failed(context.getString(R.string.viewer_too_large))

                e.status == 415 -> Loaded.Failed(e.message?.ifBlank { null } ?: context.getString(R.string.viewer_not_text))

                // No such route at all (FastAPI's own "Not Found", or the wrong method): a
                // server without the app's door to the file. Its own 404 has a sentence.
                e.status == 405 || (e.status == 404 && e.message == "Not Found") ->
                    Loaded.Failed(context.getString(R.string.viewer_unavailable), unavailable = true)

                else -> Loaded.Failed(e.message ?: context.getString(R.string.viewer_unavailable))
            }
        } catch (e: IOException) {
            Loaded.Failed(context.explain(e))
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
                        }
                    },
                    title = {
                        Column {
                            Text(file.name.ifBlank { file.path }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                file.path + if (file.line > 0) ":${file.line}" else "",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = palette.inkFaint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    actions = {
                        (loaded as? Loaded.Text)?.let { done ->
                            IconButton(onClick = { copy(context, file.name, done.text) }) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.viewer_copy))
                            }
                        }
                        web?.let { url ->
                            IconButton(onClick = { openOutside(context, url) }) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = stringResource(R.string.viewer_open_browser))
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (val now = loaded) {
                    Loaded.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                    is Loaded.Failed -> Column(
                        Modifier.align(Alignment.Center).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(now.message, color = palette.inkMuted, textAlign = TextAlign.Center)
                        web?.let { url ->
                            OutlinedButton(onClick = { openOutside(context, url) }, modifier = Modifier.padding(top = 16.dp)) {
                                Text(stringResource(R.string.viewer_open_browser))
                            }
                        }
                    }

                    is Loaded.Text -> Body(now.kind ?: file.kind, file.line, now.text)
                }
            }
        }
    }
}

@Composable
private fun Body(kind: String, line: Int, text: String) {
    val palette = LocalPalette.current
    if (kind == "markdown" && text.length <= MARKDOWN_MAX_CHARS) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            MarkdownText(text, modifier = Modifier.fillMaxWidth())
        }
        return
    }
    val note = when (kind) {
        "html" -> R.string.viewer_html_source
        "markdown" -> R.string.viewer_long_markdown
        else -> null
    }
    Column(Modifier.fillMaxSize()) {
        note?.let {
            Text(
                stringResource(it),
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkMuted,
                modifier = Modifier.fillMaxWidth().background(palette.bgSunken).padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        Lines(text, line)
    }
}

/**
 * A file as numbered lines, opened at the line the card named. The number column's width is
 * measured, not guessed: it is the width of the longest number in this file, so every line's
 * text starts at the same x whatever its number, and a wrapped line continues under its own
 * text rather than under the numbers.
 */
@Composable
private fun Lines(text: String, line: Int) {
    val palette = LocalPalette.current
    val rows = remember(text) {
        val split = text.split('\n')
        if (split.size > 1 && split.last().isEmpty()) split.dropLast(1) else split
    }
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp, color = palette.ink)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val gutter = remember(rows.size, density) {
        with(density) { measurer.measure("9".repeat(rows.size.toString().length), style).size.width.toDp() }
    }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (line - 1).coerceIn(0, (rows.size - 1).coerceAtLeast(0)))
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize().background(palette.codeBg),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        itemsIndexed(rows) { i, row ->
            val here = line > 0 && i == line - 1
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (here) Modifier.background(palette.accentSoft) else Modifier)
                    .padding(horizontal = 12.dp),
            ) {
                Text((i + 1).toString(), style = style.copy(color = palette.inkFaint), textAlign = TextAlign.End, modifier = Modifier.width(gutter))
                Spacer(Modifier.width(12.dp))
                Text(row.ifEmpty { " " }, style = style, modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun copy(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
}

/** The web UI's viewer, in the browser (a Custom Tab), where the person's own session is. */
private fun openOutside(context: Context, url: String) {
    val uri = Uri.parse(url)
    if (uri.scheme?.lowercase() !in setOf("http", "https")) return
    runCatching { CustomTabsIntent.Builder().build().launchUrl(context, uri) }
}
