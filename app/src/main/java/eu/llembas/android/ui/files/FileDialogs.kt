@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package eu.llembas.android.ui.files

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import eu.llembas.android.R
import eu.llembas.android.api.ApiClient
import eu.llembas.android.api.ApiException
import eu.llembas.android.api.FileEntry
import eu.llembas.android.api.FileKinds
import eu.llembas.android.api.FileVersion
import eu.llembas.android.api.TrashItem
import eu.llembas.android.lembas
import eu.llembas.android.ui.scheduled.ScheduleLogic
import eu.llembas.android.ui.theme.LocalPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// The file manager's smaller pages: a name, a yes or no, the folder to move or copy into, the
// trash, and a file's versions. Each is drawn from its state alone.

/** Ask for a name: a new folder's, a new file's, or a new one for something that has one. */
@Composable
fun NameDialog(title: String, ask: String?, initial: String, confirm: String, onName: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val bad = FileKinds.badName(name)
    val problem: (@Composable () -> Unit)? = if (name.isNotEmpty() && bad) {
        { Text(stringResource(R.string.files_bad_name)) }
    } else {
        null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                ask?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 12.dp)) }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    isError = name.isNotEmpty() && bad,
                    supportingText = problem,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onName(name.trim()) }, enabled = !bad && name.trim() != initial) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A question with one thing to do and a way not to. */
@Composable
fun ConfirmDialog(text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Where the folder picker is: the folders of [path], on their way or refused. */
data class FolderPickState(
    val path: String = "/",
    val parent: String? = null,
    val folders: List<FileEntry> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

/**
 * Choosing a folder of the tree by walking to it: where something is moved or copied, or where
 * a share is saved. No path is typed. [hidden]: the things being moved, which cannot be their
 * own destination.
 */
@Composable
fun FolderPicker(title: String, confirm: String, start: String, onPick: (String) -> Unit, onDismiss: () -> Unit, hidden: Set<String> = emptySet()) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(FolderPickState(path = start)) }
    var asked by remember { mutableStateOf(start) }
    var again by remember { mutableIntStateOf(0) }
    var naming by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(asked, again) {
        state = state.copy(loading = true, error = null)
        state = try {
            val api = context.lembas.instances.client() ?: throw ApiException(401, context.getString(R.string.assist_signed_out))
            val listing = api.files(asked)
            FolderPickState(listing.path, listing.parent, listing.entries.filter { it.isFolder && it.path !in hidden }, loading = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.copy(loading = false, error = context.filesSay(e))
        }
    }
    val up: () -> Unit = {
        val parent = state.parent
        if (parent != null) asked = parent else onDismiss()
    }
    Dialog(onDismissRequest = up, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        FolderPickContent(
            title,
            confirm,
            state,
            onOpen = { asked = it },
            onUp = up,
            onPick = { onPick(state.path) },
            onDismiss = onDismiss,
            onNewFolder = { naming = true },
        )
    }
    if (naming) {
        NameDialog(
            stringResource(R.string.folder_new),
            stringResource(R.string.files_new_folder_ask),
            "",
            stringResource(R.string.save),
            onName = { name ->
                naming = false
                val dir = state.path
                scope.launch {
                    try {
                        val api = context.lembas.instances.client() ?: throw ApiException(401, context.getString(R.string.assist_signed_out))
                        api.makeFolder(dir, name)
                        // Made, and walked into: it is most likely where the thing is meant to go.
                        if (asked == FileKinds.child(dir, name)) again++ else asked = FileKinds.child(dir, name)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        said = context.filesSay(e)
                    }
                }
            },
            onDismiss = { naming = false },
        )
    }
    said?.let { SaidDialog(it, onClose = { said = null }) }
}

/** The folder picker's page: where it is, that folder's folders, and the one button that chooses the folder shown. */
@Composable
fun FolderPickContent(
    title: String,
    confirm: String,
    state: FolderPickState,
    onOpen: (String) -> Unit = {},
    onUp: () -> Unit = {},
    onPick: () -> Unit = {},
    onDismiss: () -> Unit = {},
    /** Make a folder in the one shown; null where the page cannot. */
    onNewFolder: (() -> Unit)? = null,
) {
    val palette = LocalPalette.current
    Scaffold(
        topBar = {
            TopAppBar(
                actions = {
                    if (onNewFolder != null) {
                        IconButton(onClick = onNewFolder, enabled = !state.loading && state.error == null) {
                            Icon(Icons.Filled.CreateNewFolder, contentDescription = stringResource(R.string.folder_new))
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = if (state.parent != null) onUp else onDismiss) {
                        Icon(
                            if (state.parent != null) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Close,
                            contentDescription = stringResource(if (state.parent != null) R.string.back else R.string.close),
                        )
                    }
                },
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            state.path,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = palette.inkFaint,
                            maxLines = 1,
                            overflow = TextOverflow.StartEllipsis,
                        )
                    }
                },
            )
        },
        bottomBar = {
            Button(
                onClick = onPick,
                enabled = !state.loading && state.error == null,
                colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.accentInk),
                modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 48.dp),
            ) { Text(confirm) }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.error != null -> Text(state.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))

                state.loading && state.folders.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.folders.isEmpty() -> Text(
                    stringResource(R.string.files_no_folders),
                    color = palette.inkMuted,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.folders, key = { it.path }) { folder ->
                        ListItem(
                            modifier = Modifier.fillMaxWidth().clickable { onOpen(folder.path) },
                            leadingContent = {
                                Icon(
                                    if (folder.kind == "mount") Icons.Filled.FolderShared else Icons.Filled.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            headlineContent = { Text(folder.name.ifBlank { folder.path }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/** The trash as the page draws it. */
data class TrashState(val items: List<TrashItem> = emptyList(), val loading: Boolean = true, val error: String? = null)

/** What the trash page can do; nothing by default, for the screenshot tests. */
interface TrashActions {
    fun restore(item: TrashItem) {}

    fun remove(item: TrashItem) {}

    fun empty() {}

    fun close() {}
}

/** The trash: what was deleted, each to be restored or removed for good, and all of it emptied. [onChanged]: something went back to the tree. */
@Composable
fun TrashDialog(onClose: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(TrashState()) }
    var asking by remember { mutableStateOf<TrashItem?>(null) }
    var emptying by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }

    fun act(block: suspend (ApiClient) -> List<TrashItem>) {
        scope.launch {
            try {
                val api = context.lembas.instances.client() ?: throw ApiException(401, context.getString(R.string.assist_signed_out))
                state = TrashState(block(api), loading = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val words = context.filesSay(e)
                if (state.loading) state = state.copy(loading = false, error = words) else said = words
            }
        }
    }
    LaunchedEffect(Unit) { act { it.trash().items } }
    val actions = remember {
        object : TrashActions {
            override fun restore(item: TrashItem) = act { api ->
                api.restoreTrashed(item.id).items.also { onChanged() }
            }

            override fun remove(item: TrashItem) {
                asking = item
            }

            override fun empty() {
                emptying = true
            }

            override fun close() = onClose()
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        TrashContent(state, actions)
    }
    asking?.let { item ->
        ConfirmDialog(
            stringResource(R.string.trash_remove_confirm, item.name),
            stringResource(R.string.trash_remove),
            onConfirm = {
                asking = null
                act { it.removeTrashed(item.id).items }
            },
            onDismiss = { asking = null },
        )
    }
    if (emptying) {
        ConfirmDialog(
            stringResource(R.string.trash_empty_confirm),
            stringResource(R.string.trash_empty),
            onConfirm = {
                emptying = false
                act { it.removeTrashed().items }
            },
            onDismiss = { emptying = false },
        )
    }
    said?.let { SaidDialog(it, onClose = { said = null }) }
}

/**
 * The trash's page. A row is the thing's name over where it was, and two buttons of 48 dp at
 * its end: the name gives, the buttons keep their size.
 */
@Composable
fun TrashContent(state: TrashState, actions: TrashActions) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = actions::close) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close)) } },
                title = { Text(stringResource(R.string.trash), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(onClick = actions::empty, enabled = state.items.isNotEmpty()) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = stringResource(R.string.trash_empty))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.error != null -> Text(state.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))

                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.items.isEmpty() -> Text(
                    stringResource(R.string.trash_is_empty),
                    color = palette.inkMuted,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.items, key = { it.id }) { item ->
                        ListItem(
                            leadingContent = { EntryIcon(if (item.kind == "file") null else item.kind, null, selected = false) },
                            headlineContent = { Text(item.name.ifBlank { item.path }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                val size = if (item.kind == "file") Formatter.formatShortFileSize(context, item.size) else null
                                Text(listOfNotNull(FileKinds.parent(item.path), size).joinToString(" · "), maxLines = 1, overflow = TextOverflow.StartEllipsis)
                            },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = { actions.restore(item) }) {
                                        Icon(Icons.Filled.RestoreFromTrash, contentDescription = stringResource(R.string.restore))
                                    }
                                    IconButton(onClick = { actions.remove(item) }) {
                                        Icon(Icons.Filled.DeleteForever, contentDescription = stringResource(R.string.trash_remove), tint = palette.danger)
                                    }
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/** A file's versions as the page draws them. */
data class VersionsState(val versions: List<FileVersion> = emptyList(), val loading: Boolean = true, val error: String? = null)

/** A file's versions, newest first; an older one can be made the file again, which adds a version and loses none. */
@Composable
fun VersionsDialog(path: String, onClose: () -> Unit, onRestored: () -> Unit, onDownload: ((FileVersion) -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember(path) { mutableStateOf(VersionsState()) }
    var asking by remember { mutableStateOf<FileVersion?>(null) }
    LaunchedEffect(path) {
        state = try {
            val api = context.lembas.instances.client() ?: throw ApiException(401, context.getString(R.string.assist_signed_out))
            VersionsState(api.fileVersions(path).versions, loading = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            VersionsState(loading = false, error = context.filesSay(e))
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        VersionsContent(FileKinds.name(path), state, onRestore = { asking = it }, onClose = onClose, onDownload = onDownload)
    }
    asking?.let { version ->
        ConfirmDialog(
            stringResource(R.string.version_restore_confirm),
            stringResource(R.string.version_restore),
            onConfirm = {
                asking = null
                scope.launch {
                    try {
                        val api = context.lembas.instances.client() ?: throw ApiException(401, context.getString(R.string.assist_signed_out))
                        api.restoreVersion(path, version.id)
                        onRestored()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        state = state.copy(error = context.filesSay(e))
                    }
                }
            },
            onDismiss = { asking = null },
        )
    }
}

/**
 * The versions' page: when, by whom and how large; the one the file holds now says so, the others
 * can be restored, and any of them saved to the phone as it was ([onDownload]; a 48 dp button
 * before the row's last part, so the buttons stand in one column whatever that part says).
 */
@Composable
fun VersionsContent(
    name: String,
    state: VersionsState,
    onRestore: (FileVersion) -> Unit = {},
    onClose: () -> Unit = {},
    onDownload: ((FileVersion) -> Unit)? = null,
) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close)) } },
                title = {
                    Column {
                        Text(stringResource(R.string.versions), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(name, style = MaterialTheme.typography.labelSmall, color = palette.inkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.versions, key = { it.id }) { version ->
                    val download: (@Composable () -> Unit)? = if (onDownload != null) {
                        {
                            IconButton(onClick = { onDownload(version) }) {
                                Icon(Icons.Filled.Download, contentDescription = stringResource(R.string.download))
                            }
                        }
                    } else {
                        null
                    }
                    ListItem(
                        headlineContent = { Text(ScheduleLogic.moment(version.at) ?: version.id, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            val facts = listOfNotNull(version.by?.ifBlank { null }, Formatter.formatShortFileSize(context, version.size))
                            Text(facts.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        leadingContent = download,
                        trailingContent = {
                            if (version.current) {
                                Text(stringResource(R.string.version_current), style = MaterialTheme.typography.labelLarge, color = palette.inkMuted)
                            } else {
                                TextButton(onClick = { onRestore(version) }) { Text(stringResource(R.string.restore)) }
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
