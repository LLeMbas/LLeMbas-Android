@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package eu.llembas.android.ui.files

import android.app.Application
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.llembas.android.R
import eu.llembas.android.api.ApiClient
import eu.llembas.android.api.ApiException
import eu.llembas.android.api.BatchAnswer
import eu.llembas.android.api.FileEntry
import eu.llembas.android.api.FileExists
import eu.llembas.android.api.FileKinds
import eu.llembas.android.api.FileListing
import eu.llembas.android.api.Raw
import eu.llembas.android.lembas
import eu.llembas.android.ui.explain
import eu.llembas.android.ui.theme.LocalPalette
import eu.llembas.android.ui.viewer.TreeFileViewer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One directory of the person's tree: what is in it, what is chosen in it, and what is under
 * way. [error] is a listing that could not be read (said above the list, which stays); [said] a
 * refusal of something the person did (a sentence to read, in a dialog); [notice] a word that
 * it was done.
 */
data class FilesState(
    val path: String = "/",
    val parent: String? = null,
    val entries: List<FileEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val selected: Set<String> = emptySet(),
    /** An upload under way: which file of how many, and how far it is. */
    val upload: Transfer? = null,
    /** A write under way (a move, a rename…): the list waits for it. */
    val working: Boolean = false,
    val said: String? = null,
    val notice: String? = null,
) {
    val selecting: Boolean get() = selected.isNotEmpty()

    /** The chosen entries, in the list's order. */
    val chosen: List<FileEntry> get() = entries.filter { it.path in selected }

    fun toggled(path: String): FilesState = copy(selected = if (path in selected) selected - path else selected + path)

    fun allSelected(): FilesState = copy(selected = entries.map { it.path }.toSet())

    /** A listing read: the directory it is of, and of what was chosen only what is still there. */
    fun listed(listing: FileListing): FilesState {
        val same = listing.path == path
        val there = listing.entries.map { it.path }.toSet()
        return copy(
            path = listing.path,
            parent = listing.parent,
            entries = listing.entries,
            loading = false,
            error = null,
            selected = if (same) selected intersect there else emptySet(),
        )
    }
}

/** What a batch ended in: the server's sentences for what it refused, and the word for what it did. */
data class BatchOutcome(val said: String?, val done: Int)

fun outcome(answer: BatchAnswer): BatchOutcome {
    if (answer.failed == 0) return BatchOutcome(null, answer.done)
    // Where the server gave no sentence, at least which ones: their names.
    val names = answer.results.filter { !it.ok }.take(3).joinToString(", ") { FileKinds.name(it.path) }
    return BatchOutcome(answer.refusals().joinToString("\n").ifBlank { names }, answer.done)
}

/** Something the Files page asks the person before it is done, or a page it opens over itself. */
sealed interface Ask {
    data object NewFolder : Ask

    data object NewFile : Ask

    data object Trash : Ask

    data class Rename(val entry: FileEntry) : Ask

    data class Delete(val entries: List<FileEntry>) : Ask

    data class Move(val entries: List<FileEntry>) : Ask

    data class Copy(val entries: List<FileEntry>) : Ask

    data class Versions(val entry: FileEntry) : Ask
}

/** What the Files page can do; nothing by default, for the screenshot tests. */
interface FilesActions {
    fun back() {}

    fun open(entry: FileEntry) {}

    fun reload() {}

    fun toggle(entry: FileEntry) {}

    fun selectAll() {}

    fun clearSelection() {}

    fun pickUpload() {}

    fun ask(what: Ask) {}

    fun download(entries: List<FileEntry>) {}

    fun share(entries: List<FileEntry>) {}

    fun openWith(entry: FileEntry) {}
}

/**
 * The person's files as a file manager: `GET /api/v2/files` and the explorer's writes. The tree
 * is read and changed as the person, with no chat -- their own access, the same the web UI's
 * explorer draws by -- and every refusal is the server's own sentence.
 */
class FilesViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(FilesState(loading = true))
    val state = _state.asStateFlow()
    private var loading: Job? = null
    private var uploading: Job? = null
    private val app: Application get() = getApplication()

    private suspend fun api(): ApiClient = app.lembas.instances.client() ?: throw ApiException(401, app.getString(R.string.assist_signed_out))

    /**
     * Opens a directory. A refusal (a path no longer readable, the server unreachable) keeps the
     * directory already shown and says why above it, so the way back is never lost.
     */
    fun open(path: String) {
        loading?.cancel()
        loading = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                // Also read again after an upload that ended behind the lock: not before the app is in front.
                app.lembas.gate.pass()
                val listing = api().files(path)
                _state.update { it.listed(listing) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = app.explain(e)) }
            }
        }
    }

    fun reload() = open(_state.value.path)

    fun up(): Boolean {
        val parent = _state.value.parent ?: return false
        open(parent)
        return true
    }

    fun toggle(entry: FileEntry) = _state.update { it.toggled(entry.path) }

    fun selectAll() = _state.update { it.allSelected() }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    fun clearSaid() = _state.update { it.copy(said = null, notice = null) }

    /** One write, then the directory read again: what is there is what the server says is there. */
    private fun write(block: suspend (ApiClient) -> String?) {
        if (_state.value.working) return
        viewModelScope.launch {
            _state.update { it.copy(working = true, said = null, notice = null) }
            try {
                val notice = block(api())
                _state.update { it.copy(notice = notice) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(said = app.filesSay(e)) }
            } finally {
                _state.update { it.copy(working = false) }
            }
            reload()
        }
    }

    fun newFolder(name: String) = write { api ->
        api.makeFolder(_state.value.path, name)
        null
    }

    /**
     * A new, empty text file, and [then] its path, to open it for writing. The instance makes it
     * only where nothing is called that (`create`): otherwise nothing is written and its own
     * sentence says what is there. An instance from before `create` would write over what is
     * there, so the directory is read first and a name it holds is refused here
     * ([ApiClient.newFile]).
     */
    fun newFile(name: String, then: (String) -> Unit) = write { api ->
        try {
            then(api.newFile(_state.value.path, name))
        } catch (e: FileExists) {
            _state.update { it.copy(said = e.message?.ifBlank { null } ?: app.getString(R.string.files_taken, name)) }
        }
        null
    }

    fun rename(entry: FileEntry, name: String) = write { api ->
        api.renameFile(entry.path, name)
        null
    }

    /** Move or copy the entries into the directory [to], or with `delete` put them in the trash; each is answered for by itself. */
    fun batch(op: String, entries: List<FileEntry>, to: String? = null) = write { api ->
        val done = outcome(api.batchFiles(op, entries.map { it.path }, to))
        _state.update { it.copy(selected = emptySet(), said = done.said) }
        when {
            done.done == 0 -> null
            op == "delete" -> app.getString(R.string.files_trashed)
            op == "copy" -> app.getString(R.string.copied)
            else -> null
        }
    }

    /**
     * Upload files of the phone into the directory shown, one request each: a file the instance
     * refuses (too large: its 413 names the file) does not take the others with it. Each is
     * streamed from where it is; none is read into memory.
     */
    fun upload(uris: List<Uri>) {
        if (uris.isEmpty() || uploading?.isActive == true) return
        val dir = _state.value.path
        uploading = viewModelScope.launch {
            val refused = mutableListOf<String>()
            var sent = 0
            try {
                val api = api()
                val files = uris.map { app.contentResolver.describe(it) }
                files.forEachIndexed { i, file ->
                    val words = app.getString(R.string.files_uploading, (i + 1).toString(), files.size.toString())
                    _state.update { it.copy(upload = Transfer(words, 0, file.size)) }
                    var last = 0L
                    try {
                        // The file under way when the phone locks goes through; the next waits for the app in front, unlocked.
                        app.lembas.gate.pass { waiting -> _state.update { it.copy(upload = it.upload?.copy(waiting = waiting)) } }
                        api.uploadFrom(app, file, dir) { bytes ->
                            if (bytes - last >= 256 * 1024) {
                                last = bytes
                                _state.update { it.copy(upload = it.upload?.copy(done = bytes)) }
                            }
                        }
                        sent++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        refused += app.filesSay(e)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                refused += app.filesSay(e)
            } finally {
                val words = refused.distinct().take(3).joinToString("\n").ifBlank { null }
                _state.update { it.copy(upload = null, said = words, notice = if (sent > 0) app.getString(R.string.files_uploaded) else null) }
                reload()
            }
        }
    }

    fun cancelUpload() {
        uploading?.cancel()
    }
}

@Composable
fun FilesScreen(onBack: () -> Unit, start: String? = null, model: FilesViewModel = viewModel(), transfers: TransferViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var viewing by rememberSaveable { mutableStateOf<String?>(null) }
    var writing by rememberSaveable { mutableStateOf(false) }
    var asked by remember { mutableStateOf<Ask?>(null) }
    var saving by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    // Opened at a folder ([start]: where a generated file is) the first time; after that, where the person walked to.
    LaunchedEffect(Unit) { if (state.entries.isEmpty() && state.error == null) model.open(start?.ifBlank { null } ?: state.path) }
    val saveVersion = rememberSaver(transfers)
    LaunchedEffect(state.notice) {
        state.notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            model.clearSaid()
        }
    }

    // Back lets go of what is chosen first, then goes up one directory, as a file manager's does; at the top it leaves.
    BackHandler(enabled = state.selecting || state.parent != null) { if (state.selecting) model.clearSelection() else model.up() }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { model.upload(it) }
    val saveTo = rememberLauncherForActivityResult(CreateFile()) { to ->
        val what = saving
        saving = emptyList()
        if (to != null && what.isNotEmpty()) {
            transfers.download(what.map { it.path }, zip = asZip(what), to = to)
            model.clearSelection()
        }
    }
    val actions = remember(model, transfers) {
        object : FilesActions {
            override fun back() {
                if (!model.up()) onBack()
            }

            override fun open(entry: FileEntry) {
                if (entry.isFolder) {
                    model.open(entry.path)
                } else {
                    writing = false
                    viewing = entry.path
                }
            }

            override fun reload() = model.reload()

            override fun toggle(entry: FileEntry) = model.toggle(entry)

            override fun selectAll() = model.selectAll()

            override fun clearSelection() = model.clearSelection()

            override fun pickUpload() = pick.launch(arrayOf("*/*"))

            override fun ask(what: Ask) {
                asked = what
            }

            override fun download(entries: List<FileEntry>) {
                if (entries.isEmpty()) return
                saving = entries
                val zip = asZip(entries)
                saveTo.launch(
                    CreateFile.Ask(
                        if (zip) {
                            TransferViewModel.zipName(
                                entries.map {
                                    it.path
                                },
                            )
                        } else {
                            entries[0].name
                        },
                        if (zip) "application/zip" else entries[0].mime,
                    ),
                )
            }

            override fun share(entries: List<FileEntry>) {
                transfers.share(entries)
                model.clearSelection()
            }

            override fun openWith(entry: FileEntry) = transfers.openWith(entry.path, entry.name)
        }
    }

    FilesContent(state, actions)

    when (val now = asked) {
        null -> Unit

        Ask.NewFolder -> NameDialog(
            stringResource(R.string.folder_new),
            stringResource(R.string.files_new_folder_ask),
            "",
            stringResource(R.string.save),
            onName = {
                asked = null
                model.newFolder(it)
            },
            onDismiss = { asked = null },
        )

        Ask.NewFile -> NameDialog(
            stringResource(R.string.files_new_file),
            stringResource(R.string.files_new_file_ask),
            "",
            stringResource(R.string.save),
            onName = { name ->
                asked = null
                model.newFile(name) { path ->
                    writing = true
                    viewing = path
                }
            },
            onDismiss = { asked = null },
        )

        is Ask.Rename -> NameDialog(
            stringResource(R.string.rename),
            stringResource(R.string.files_new_name),
            now.entry.name,
            stringResource(R.string.rename),
            onName = {
                asked = null
                model.rename(now.entry, it)
            },
            onDismiss = { asked = null },
        )

        is Ask.Delete -> ConfirmDialog(
            if (now.entries.size == 1) {
                stringResource(R.string.files_trash_one, now.entries[0].name)
            } else {
                stringResource(R.string.files_trash_many, now.entries.size.toString())
            },
            stringResource(R.string.files_to_trash),
            onConfirm = {
                asked = null
                model.batch("delete", now.entries)
            },
            onDismiss = { asked = null },
        )

        is Ask.Move -> FolderPicker(
            stringResource(R.string.files_move_which),
            stringResource(R.string.files_move_here),
            state.path,
            onPick = {
                asked = null
                model.batch("move", now.entries, it)
            },
            onDismiss = { asked = null },
            hidden = now.entries.map { it.path }.toSet(),
        )

        is Ask.Copy -> FolderPicker(
            stringResource(R.string.files_copy_which),
            stringResource(R.string.files_copy_here),
            state.path,
            onPick = {
                asked = null
                model.batch("copy", now.entries, it)
            },
            onDismiss = { asked = null },
        )

        is Ask.Versions -> VersionsDialog(
            now.entry.path,
            onDownload = { version -> saveVersion(Raw.Path(now.entry.path, version.id), now.entry.name, now.entry.mime) },
            onClose = { asked = null },
            onRestored = {
                asked = null
                model.reload()
            },
        )

        Ask.Trash -> TrashDialog(onClose = { asked = null }, onChanged = model::reload)
    }

    state.upload?.let { TransferDialog(it, onCancel = model::cancelUpload) }
    state.said?.let { SaidDialog(it, onClose = model::clearSaid) }
    TransferHost(transfers)
    viewing?.let { path -> TreeFileViewer(path, onClose = { viewing = null }, startEditing = writing, onChanged = model::reload) }
}

/** Several things, or a folder, leave as one zip; a single file as itself. */
fun asZip(entries: List<FileEntry>): Boolean = entries.size > 1 || entries.any { it.isFolder }

/**
 * The Files page, from [state] alone. A tap opens; a long press chooses, and while anything is
 * chosen a tap chooses too and the bar at the bottom acts on all of it. Each row's own menu
 * (the three dots, 48 dp) has what can be done to that one thing.
 */
@Composable
fun FilesContent(state: FilesState, actions: FilesActions) {
    val palette = LocalPalette.current
    var adding by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            if (state.selecting) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = actions::clearSelection) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cancel)) }
                    },
                    title = { Text(stringResource(R.string.files_selected, state.selected.size.toString()), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    actions = {
                        IconButton(onClick = actions::selectAll) {
                            Icon(Icons.Filled.SelectAll, contentDescription = stringResource(R.string.files_select_all))
                        }
                    },
                )
            } else {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = actions::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
                    },
                    title = {
                        Column {
                            Text(stringResource(R.string.files), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (state.parent == null) stringResource(R.string.files_all) else state.path,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = if (state.parent == null) null else FontFamily.Monospace,
                                color = palette.inkFaint,
                                maxLines = 1,
                                overflow = TextOverflow.StartEllipsis,
                            )
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.files_upload)) }
                            DropdownMenu(expanded = adding, onDismissRequest = { adding = false }) {
                                MenuItem(R.string.files_upload) {
                                    adding = false
                                    actions.pickUpload()
                                }
                                MenuItem(R.string.folder_new) {
                                    adding = false
                                    actions.ask(Ask.NewFolder)
                                }
                                MenuItem(R.string.files_new_file) {
                                    adding = false
                                    actions.ask(Ask.NewFile)
                                }
                            }
                        }
                        IconButton(onClick = {
                            actions.ask(Ask.Trash)
                        }) { Icon(Icons.Filled.RestoreFromTrash, contentDescription = stringResource(R.string.trash)) }
                    },
                )
            }
        },
        bottomBar = {
            if (state.selecting) {
                val chosen = state.chosen
                // Five actions of one size, spread evenly: none of them is a word, so none can crowd another.
                BottomAppBar {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        BarButton(Icons.Filled.DriveFileMove, R.string.files_move) { actions.ask(Ask.Move(chosen)) }
                        BarButton(Icons.Filled.ContentCopy, R.string.files_copy_to) { actions.ask(Ask.Copy(chosen)) }
                        BarButton(Icons.Filled.Download, if (asZip(chosen)) R.string.files_download_zip else R.string.download) { actions.download(chosen) }
                        BarButton(Icons.Filled.Share, R.string.report_share) { actions.share(chosen) }
                        BarButton(Icons.Filled.Delete, R.string.files_to_trash) { actions.ask(Ask.Delete(chosen)) }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            PullToRefreshBox(isRefreshing = state.loading, onRefresh = actions::reload, modifier = Modifier.fillMaxSize()) {
                if (state.entries.isEmpty() && !state.loading && state.error == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.files_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(32.dp))
                    }
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.entries, key = { it.path }) { entry ->
                        EntryRow(entry, selected = entry.path in state.selected, selecting = state.selecting, actions = actions)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(label: Int, onClick: () -> Unit) = DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = onClick)

@Composable
private fun BarButton(icon: ImageVector, label: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, contentDescription = stringResource(label)) }
}

/** What an entry is, as a picture: a folder, a shared folder, or the file's kind; a tick when it is chosen. */
@Composable
internal fun EntryIcon(folder: String?, fileKind: String?, selected: Boolean) {
    val palette = LocalPalette.current
    val icon = when {
        selected -> Icons.Filled.CheckCircle

        folder == "mount" -> Icons.Filled.FolderShared

        folder != null -> Icons.Filled.Folder

        else -> when (fileKind) {
            "image", "svg" -> Icons.Filled.Image
            "pdf" -> Icons.Filled.PictureAsPdf
            "audio" -> Icons.Filled.MusicNote
            "video" -> Icons.Filled.Movie
            "table" -> Icons.Filled.TableChart
            "code", "html", "notebook" -> Icons.Filled.Code
            "archive" -> Icons.Filled.Archive
            "model3d", "binary" -> Icons.AutoMirrored.Filled.InsertDriveFile
            else -> Icons.Filled.Description
        }
    }
    Icon(icon, contentDescription = null, tint = if (selected) palette.accent else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun EntryRow(entry: FileEntry, selected: Boolean, selecting: Boolean, actions: FilesActions) {
    val context = LocalContext.current
    val palette = LocalPalette.current
    var menu by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (selecting) actions.toggle(entry) else actions.open(entry) },
                onLongClick = { actions.toggle(entry) },
            ),
        colors = ListItemDefaults.colors(containerColor = if (selected) palette.accentSoft else palette.bg),
        leadingContent = { EntryIcon(if (entry.isFolder) entry.kind else null, entry.fileKind, selected) },
        headlineContent = { Text(entry.name.ifBlank { entry.path }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (entry.isFolder) {
            null
        } else {
            {
                val line = listOfNotNull(Formatter.formatShortFileSize(context, entry.size), entry.mime.ifBlank { null }).joinToString(" · ")
                Text(line, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        trailingContent = if (selecting) {
            null
        } else {
            {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        val one = listOf(entry)
                        val then: (() -> Unit) -> (() -> Unit) = { act ->
                            {
                                menu = false
                                act()
                            }
                        }
                        MenuItem(R.string.rename, then { actions.ask(Ask.Rename(entry)) })
                        MenuItem(R.string.files_move, then { actions.ask(Ask.Move(one)) })
                        MenuItem(R.string.files_copy_to, then { actions.ask(Ask.Copy(one)) })
                        MenuItem(if (entry.isFolder) R.string.files_download_zip else R.string.download, then { actions.download(one) })
                        MenuItem(R.string.report_share, then { actions.share(one) })
                        if (!entry.isFolder) {
                            MenuItem(R.string.files_open_with, then { actions.openWith(entry) })
                            MenuItem(R.string.versions, then { actions.ask(Ask.Versions(entry)) })
                        }
                        MenuItem(R.string.files_to_trash, then { actions.ask(Ask.Delete(one)) })
                    }
                }
            }
        },
    )
}
