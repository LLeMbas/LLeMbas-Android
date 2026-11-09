@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package eu.llembas.android.ui.files

import android.app.Application
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import eu.llembas.android.api.FileEntry
import eu.llembas.android.lembas
import eu.llembas.android.ui.explain
import eu.llembas.android.ui.theme.LocalPalette
import eu.llembas.android.ui.viewer.TreeFileViewer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One directory of the person's tree, and whether it is on its way. */
data class FilesState(
    val path: String = "/",
    val parent: String? = null,
    val entries: List<FileEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * The person's files, read-only: `GET /api/v2/files` as the explorer's listing. The tree is read
 * as the person, with no chat -- their own access, the same the web UI's explorer draws by.
 */
class FilesViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(FilesState(loading = true))
    val state = _state.asStateFlow()
    private var loading: Job? = null

    /**
     * Opens a directory. A refusal (a path no longer readable, the server unreachable) keeps the
     * directory already shown and says why above it, so the way back is never lost.
     */
    fun open(path: String) {
        loading?.cancel()
        loading = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val api = getApplication<Application>().lembas.instances.client()
                if (api == null) {
                    _state.update { it.copy(loading = false) }
                    return@launch
                }
                val listing = api.files(path)
                _state.value = FilesState(path = listing.path, parent = listing.parent, entries = listing.entries)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(loading = false, error = getApplication<Application>().explain(e)) }
            }
        }
    }

    fun reload() = open(_state.value.path)

    fun up(): Boolean {
        val parent = _state.value.parent ?: return false
        open(parent)
        return true
    }
}

@Composable
fun FilesScreen(onBack: () -> Unit, model: FilesViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val palette = LocalPalette.current
    var viewing by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { if (state.entries.isEmpty() && state.error == null) model.open(state.path) }

    // Back goes up one directory first, as a file manager's does; at the top it leaves.
    BackHandler(enabled = state.parent != null) { model.up() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { if (!model.up()) onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
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
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            }
            PullToRefreshBox(isRefreshing = state.loading, onRefresh = { model.reload() }, modifier = Modifier.fillMaxSize()) {
                if (state.entries.isEmpty() && !state.loading && state.error == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.files_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(32.dp),
                        )
                    }
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.entries, key = { it.path }) { entry ->
                        EntryRow(entry) { if (entry.isFolder) model.open(entry.path) else viewing = entry.path }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    viewing?.let { path -> TreeFileViewer(path, onClose = { viewing = null }) }
}

@Composable
private fun EntryRow(entry: FileEntry, onOpen: () -> Unit) {
    val context = LocalContext.current
    ListItem(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        leadingContent = {
            Icon(
                when (entry.kind) {
                    "mount" -> Icons.Filled.FolderShared
                    "dir" -> Icons.Filled.Folder
                    else -> Icons.Filled.Description
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        headlineContent = { Text(entry.name.ifBlank { entry.path }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (entry.isFolder) {
            null
        } else {
            {
                val line = listOfNotNull(Formatter.formatShortFileSize(context, entry.size), entry.mime.ifBlank { null }).joinToString(" · ")
                Text(line, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
    )
}
