@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package eu.llembas.android.ui.viewer

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.llembas.android.R
import eu.llembas.android.api.ApiException
import eu.llembas.android.api.FileContent
import eu.llembas.android.api.FileEntry
import eu.llembas.android.api.FileKinds
import eu.llembas.android.api.FileView
import eu.llembas.android.api.Notebook
import eu.llembas.android.api.Raw
import eu.llembas.android.api.SaveConflict
import eu.llembas.android.api.Table
import eu.llembas.android.api.parseNotebook
import eu.llembas.android.api.parseTable
import eu.llembas.android.api.tableDelimiter
import eu.llembas.android.lembas
import eu.llembas.android.ui.explain
import eu.llembas.android.ui.files.CreateFile
import eu.llembas.android.ui.files.SaidDialog
import eu.llembas.android.ui.files.TransferHost
import eu.llembas.android.ui.files.TransferViewModel
import eu.llembas.android.ui.files.VersionsDialog
import eu.llembas.android.ui.files.filesSay
import eu.llembas.android.ui.files.noSuchRoute
import eu.llembas.android.ui.files.rememberSaver
import eu.llembas.android.ui.theme.LocalPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a viewer says of a failure: the server's or the network's sentence; null for "too large for this phone". */
internal fun viewerSays(context: Context, e: Throwable): String? {
    val tooLarge = e is ApiException && e.status == 413 && e.message.isNullOrBlank()
    return if (tooLarge) null else context.explain(e)
}

/**
 * The text being edited, kept apart from the screen: a megabyte does not fit what Android saves
 * of a screen when the phone is turned, and unsaved words must not go with it. [draft] is null
 * while nothing is edited; [touched] once a key was pressed.
 */
class EditViewModel : ViewModel() {
    var draft by mutableStateOf<String?>(null)
        private set
    var touched by mutableStateOf(false)
        private set

    /** The `sha` the text had when it was read: a save says it, so somebody else's change is not written over. */
    var base: String = ""
        private set

    fun start(text: String, sha: String) {
        draft = text
        base = sha
        touched = false
    }

    fun type(text: String) {
        draft = text
        touched = true
    }

    fun stop() {
        draft = null
        touched = false
    }
}

/** A file of the tree, opened: on its way, not to be had, or what the server says of it. */
private sealed interface Opened {
    data object Loading : Opened

    data class Failed(val message: String) : Opened

    data class Ready(val file: FileContent) : Opened
}

/**
 * A file of the person's tree, full screen, drawn by what the server says it is (`file_kind`):
 * text kinds as text (and edited as text), a picture, a PDF's pages, sound and video streamed, a
 * table's grid, a notebook's cells, and for the rest what it is with Download, Open with and
 * Share. The person's own files are no more trusted than a model's, since a model may have
 * written them: **nothing is rendered as HTML and nothing a file holds is run** -- an HTML page
 * and an SVG are their source. [onChanged]: the file was saved or a version restored, so a
 * listing behind this is stale.
 */
@Composable
fun TreeFileViewer(path: String, onClose: () -> Unit, startEditing: Boolean = false, onChanged: () -> Unit = {}) {
    val context = LocalContext.current
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val edit: EditViewModel = viewModel(key = "edit:$path")
    // Its own, not the Files page's: two hosts of one would each open the other app.
    val transfers: TransferViewModel = viewModel(key = "viewer-transfers")
    var opened by remember(path) { mutableStateOf<Opened>(Opened.Loading) }
    var reads by remember(path) { mutableIntStateOf(0) }
    var web by remember(path) { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var versions by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var conflict by remember { mutableStateOf<String?>(null) }
    var said by remember { mutableStateOf<String?>(null) }
    var wantsEdit by remember(path) { mutableStateOf(startEditing) }
    // A table can be read as the text it is, as well as laid out.
    var tableAsText by remember(path) { mutableStateOf(false) }
    val saveVersion = rememberSaver(transfers)
    val name = FileKinds.name(path)

    LaunchedEffect(path, reads) {
        val api = context.lembas.instances.client()
        if (api == null) {
            opened = Opened.Failed(context.getString(R.string.assist_signed_out))
            return@LaunchedEffect
        }
        web = api.webFile(path)
        opened = try {
            val file = api.fileContent(path)
            // A new file opens ready to be written in; so does one whose text was read again after a conflict.
            if (wantsEdit && FileKinds.editable(file.fileKind, file.writable, file.withheld != null)) edit.start(file.text, file.sha)
            wantsEdit = false
            Opened.Ready(file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Opened.Failed(context.filesSay(e))
        }
    }

    val file = (opened as? Opened.Ready)?.file
    val draft = edit.draft
    val saveTo = rememberLauncherForActivityResult(CreateFile()) { to -> if (to != null) transfers.download(listOf(path), zip = false, to = to) }
    val actions = remember(path, file?.mime) {
        object : FileActions {
            override fun download() = saveTo.launch(CreateFile.Ask(name, file?.mime.orEmpty()))

            override fun openWith() = transfers.openWith(path, name, file?.mime.orEmpty())

            override fun share() = transfers.share(listOf(FileEntry(name = name, path = path, mime = file?.mime.orEmpty())))
        }
    }

    fun save(force: Boolean) {
        val text = edit.draft ?: return
        saving = true
        scope.launch {
            try {
                val api = context.lembas.instances.client() ?: throw ApiException(401, context.getString(R.string.assist_signed_out))
                val saved = api.saveFile(path, text, base = edit.base, force = force)
                edit.stop()
                opened = Opened.Ready(saved)
                onChanged()
            } catch (e: SaveConflict) {
                // Nothing was written. The server's sentence, with the two ways on.
                conflict = e.message?.ifBlank { null } ?: context.getString(R.string.files_conflict)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                said = context.filesSay(e)
            } finally {
                saving = false
            }
        }
    }

    // Leaving with words that were not saved asks first: Back, the cross, and a tap outside all come here.
    fun close() {
        when {
            draft != null && edit.touched -> leaving = true
            draft != null -> edit.stop()
            else -> onClose()
        }
    }

    Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = { close() }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close)) } },
                    title = {
                        Column {
                            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                path,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = palette.inkFaint,
                                maxLines = 1,
                                overflow = TextOverflow.StartEllipsis,
                            )
                        }
                    },
                    actions = {
                        if (draft != null) {
                            IconButton(onClick = { save(force = false) }, enabled = !saving && edit.touched) {
                                Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.save))
                            }
                        } else if (file != null) {
                            if (FileKinds.editable(file.fileKind, file.writable, file.withheld != null)) {
                                IconButton(onClick = {
                                    edit.start(file.text, file.sha)
                                }) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.edit)) }
                            }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more)) }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    val then: (() -> Unit) -> (() -> Unit) = { act ->
                                        {
                                            menu = false
                                            act()
                                        }
                                    }
                                    val text = file.text.takeIf { file.withheld == null && it.isNotEmpty() }
                                    if (text != null) MenuEntry(R.string.viewer_copy, then { copy(context, name, text) })
                                    if (text != null && FileKinds.viewer(file.fileKind) == FileView.Table) {
                                        MenuEntry(if (tableAsText) R.string.table_as_table else R.string.table_as_text, then { tableAsText = !tableAsText })
                                    }
                                    if (file.fileKind != null) {
                                        MenuEntry(R.string.download, then { actions.download() })
                                        MenuEntry(R.string.files_open_with, then { actions.openWith() })
                                        MenuEntry(R.string.report_share, then { actions.share() })
                                        MenuEntry(R.string.versions, then { versions = true })
                                    }
                                    web?.let { url -> MenuEntry(R.string.viewer_open_browser, then { openOutside(context, url) }) }
                                }
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                Box(Modifier.fillMaxSize()) {
                    when (val now = opened) {
                        Opened.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        is Opened.Failed -> Text(now.message, color = palette.inkMuted, modifier = Modifier.align(Alignment.Center).padding(32.dp))
                        is Opened.Ready -> if (draft != null) EditorBody(draft, edit::type, enabled = !saving) else Shown(path, now.file, actions, tableAsText)
                    }
                }
            }
        }
    }

    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            text = { Text(stringResource(R.string.files_unsaved)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        leaving = false
                        edit.stop()
                    },
                ) { Text(stringResource(R.string.discard)) }
            },
            dismissButton = { TextButton(onClick = { leaving = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    conflict?.let { sentence ->
        AlertDialog(
            onDismissRequest = { conflict = null },
            text = { Text(sentence) },
            // Two ways on, and neither is the default: the words stack, so each is a whole row.
            confirmButton = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                    TextButton(
                        onClick = {
                            conflict = null
                            // What the file holds now, in place of what was typed here.
                            edit.stop()
                            wantsEdit = true
                            reads++
                        },
                    ) { Text(stringResource(R.string.reload)) }
                    TextButton(
                        onClick = {
                            conflict = null
                            save(force = true)
                        },
                    ) { Text(stringResource(R.string.files_save_anyway)) }
                    TextButton(onClick = { conflict = null }) { Text(stringResource(R.string.cancel)) }
                }
            },
        )
    }
    said?.let { SaidDialog(it, onClose = { said = null }) }
    if (versions) {
        VersionsDialog(
            path,
            onDownload = { version -> saveVersion(Raw.Path(path, version.id), name, file?.mime.orEmpty()) },
            onClose = { versions = false },
            onRestored = {
                versions = false
                reads++
                onChanged()
            },
        )
    }
    TransferHost(transfers)
}

@Composable
private fun MenuEntry(label: Int, onClick: () -> Unit) = DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = onClick)

/** A file that was read, by its kind. What cannot be drawn falls back to the details page, saying why. */
@Composable
private fun Shown(path: String, file: FileContent, actions: FileActions, tableAsText: Boolean = false) {
    val context = LocalContext.current
    val name = file.name.ifBlank { FileKinds.name(path) }
    val type = listOf(file.fileKind.orEmpty(), file.mime).filter { it.isNotBlank() }.joinToString(" · ")
    val details: @Composable (String?) -> Unit = { note -> DetailsBody(name, type, file.size, note ?: stringResource(R.string.files_not_shown), actions) }
    val withheld = file.withheld
    when (FileKinds.viewer(file.fileKind)) {
        FileView.Text -> when {
            withheld != null -> {
                val why = if (withheld.reason == "too_large") R.string.viewer_too_large else R.string.viewer_not_text
                details(withheld.message.ifBlank { stringResource(why) })
            }

            file.fileKind == "svg" -> Column(Modifier.fillMaxSize()) {
                Note(stringResource(R.string.files_svg_source))
                FileBody("code", "xml", 0, file.text)
            }

            else -> FileBody(file.viewKind, file.language, 0, file.text)
        }

        FileView.Image -> ImageBody(path, name, file.size, details)

        FileView.Pdf -> PdfBody(path, name, file.size, details)

        FileView.Audio -> MediaBody(Raw.Path(path), file.size, video = false, fallback = details)

        FileView.Video -> MediaBody(Raw.Path(path), file.size, video = true, fallback = details)

        FileView.Table -> if (tableAsText && withheld == null) {
            FileBody("text", "", 0, file.text)
        } else {
            val table by produceState<Fetched<Table>>(Fetched.Loading(), path, file.sha) {
                value = try {
                    // Up to 1 MB the server sent the text; of a longer table the first megabyte is asked for.
                    val text = if (withheld == null) {
                        file.text
                    } else {
                        val api = context.lembas.instances.client() ?: throw ApiException(401, context.getString(R.string.assist_signed_out))
                        withContext(Dispatchers.IO) { api.rawBytes(path, 0, TABLE_HEAD - 1).toString(Charsets.UTF_8) }
                    }
                    Fetched.Ready(withContext(Dispatchers.Default) { parseTable(text, tableDelimiter(name, text), partial = withheld != null) })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Fetched.Failed(if (noSuchRoute(e)) null else viewerSays(context, e))
                }
            }
            when (val now = table) {
                is Fetched.Loading -> Progress(now)
                is Fetched.Failed -> details(now.message)
                is Fetched.Ready -> if (now.value.rows.isEmpty()) FileBody("text", "", 0, file.text) else TableBody(now.value)
            }
        }

        FileView.Notebook -> {
            val notebook = remember(file.sha, file.text) { if (withheld == null) parseNotebook(file.text) else null }
            if (notebook != null) NotebookBody(notebook) else details(withheld?.message?.ifBlank { null })
        }

        FileView.Details -> details(
            stringResource(if (file.fileKind == "archive") R.string.archive_note else R.string.files_no_viewer),
        )
    }
}

/** How much of a table too long to be sent as text is read for its first rows. */
private const val TABLE_HEAD = 1024L * 1024
