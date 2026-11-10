@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package eu.llembas.android.ui.memory

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.llembas.android.R
import eu.llembas.android.api.DataGroup
import eu.llembas.android.api.Memory
import eu.llembas.android.lembas
import eu.llembas.android.ui.explain
import eu.llembas.android.ui.theme.LocalPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MemoryState(
    val memories: List<Memory> = emptyList(),
    val groups: List<DataGroup> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/** The person's memories (`/api/v2/memories`): read, added, changed and forgotten. */
class MemoryViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(MemoryState(loading = true))
    val state = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val api = getApplication<Application>().lembas.instances.client() ?: return@launch
                val list = api.memories()
                _state.value = MemoryState(memories = list.memories, groups = list.groups)
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = getApplication<Application>().explain(e)) }
            }
        }
    }

    fun add(content: String, groupId: String?) = act { api ->
        val made = api.addMemory(content, groupId)
        _state.update { it.copy(memories = listOf(made) + it.memories) }
    }

    fun edit(memory: Memory, content: String) = act { api ->
        val changed = api.editMemory(memory.id, content)
        _state.update { s -> s.copy(memories = s.memories.map { if (it.id == changed.id) changed else it }) }
    }

    fun forget(memory: Memory) = act { api ->
        api.forgetMemory(memory.id)
        _state.update { s -> s.copy(memories = s.memories.filterNot { it.id == memory.id }) }
    }

    /** One change; a refusal (a limit, an empty text, one already gone) is the server's sentence. */
    private fun act(change: suspend (eu.llembas.android.api.ApiClient) -> Unit) {
        viewModelScope.launch {
            try {
                val api = getApplication<Application>().lembas.instances.client() ?: return@launch
                _state.update { it.copy(error = null) }
                change(api)
            } catch (e: Exception) {
                _state.update { it.copy(error = getApplication<Application>().explain(e)) }
            }
        }
    }
}

interface MemoryActions {
    fun add(content: String, groupId: String?) {}
    fun edit(memory: Memory, content: String) {}
    fun forget(memory: Memory) {}
    fun reload() {}
}

@Composable
fun MemoryScreen(onBack: () -> Unit, model: MemoryViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { model.load() }
    MemoryContent(
        state,
        object : MemoryActions {
            override fun add(content: String, groupId: String?) = model.add(content, groupId)

            override fun edit(memory: Memory, content: String) = model.edit(memory, content)

            override fun forget(memory: Memory) = model.forget(memory)

            override fun reload() = model.load()
        },
        onBack,
    )
}

/** What the dialog is for: a new memory ([memory] null), or changing one. */
private data class Editing(val memory: Memory? = null)

/**
 * Memory, as Settings › Memory on the web: what models remember about you, newest first. A row
 * is the text (plain, never markdown: a model may have written it), with a mark when a model
 * did and the data group when there is more than one. A tap edits or forgets it; Add remembers
 * something new.
 */
@Composable
fun MemoryContent(state: MemoryState, actions: MemoryActions, onBack: () -> Unit = {}) {
    val palette = LocalPalette.current
    var editing by remember { mutableStateOf<Editing?>(null) }
    val manyGroups = state.groups.size > 1
    Scaffold(
        containerColor = palette.bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.bg, scrolledContainerColor = palette.bg),
                title = { Text(stringResource(R.string.memory)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = Editing() },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.memory_remember)) },
                containerColor = palette.accent,
                contentColor = palette.accentInk,
            )
        },
    ) { padding ->
        PullToRefreshBox(isRefreshing = state.loading && state.memories.isNotEmpty(), onRefresh = actions::reload, modifier = Modifier.padding(padding)) {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Text(
                        stringResource(R.string.memory_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.inkMuted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                state.error?.let { error ->
                    item {
                        ListItem(
                            headlineContent = { Text(error, color = palette.danger, style = MaterialTheme.typography.bodyMedium) },
                            trailingContent = { TextButton(onClick = actions::reload) { Text(stringResource(R.string.retry)) } },
                            colors = ListItemDefaults.colors(containerColor = palette.bg),
                        )
                    }
                }
                if (state.memories.isEmpty() && !state.loading && state.error == null) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(24.dp)) {
                            Text(stringResource(R.string.memory_none), color = palette.inkMuted, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                items(state.memories, key = { it.id }) { memory ->
                    ListItem(
                        headlineContent = { Text(memory.content, maxLines = 4, overflow = TextOverflow.Ellipsis) },
                        supportingContent = if (manyGroups) {
                            { Text(memory.dataGroup.name, color = palette.inkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        } else {
                            null
                        },
                        trailingContent = if (memory.byModel) {
                            { Icon(Icons.Filled.SmartToy, contentDescription = stringResource(R.string.memory_by_model), tint = palette.inkFaint) }
                        } else {
                            null
                        },
                        colors = ListItemDefaults.colors(containerColor = palette.bg),
                        modifier = Modifier.clickable { editing = Editing(memory) }.heightIn(min = 48.dp),
                    )
                    HorizontalDivider(color = palette.border, modifier = Modifier.padding(start = 16.dp))
                }
                // Room under the last row for the button over it.
                item { Box(Modifier.heightIn(min = 88.dp)) }
            }
        }
    }
    editing?.let { asked ->
        MemoryDialog(
            asked.memory,
            groups = if (manyGroups) state.groups else emptyList(),
            onSave = { content, group ->
                editing = null
                if (asked.memory != null) actions.edit(asked.memory, content) else actions.add(content, group)
            },
            onForget = {
                editing = null
                asked.memory?.let(actions::forget)
            },
            onDismiss = { editing = null },
        )
    }
}

/**
 * A memory's text in one box. New: Remember, and the data group when there is a choice. An
 * existing one: Save and Forget.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoryDialog(memory: Memory?, groups: List<DataGroup>, onSave: (String, String?) -> Unit, onForget: () -> Unit, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    var text by remember { mutableStateOf(memory?.content.orEmpty()) }
    var group by remember { mutableStateOf(groups.firstOrNull()?.id) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (memory == null) R.string.memory_remember else R.string.memory)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.memory_label)) },
                    placeholder = { Text(stringResource(R.string.memory_hint)) },
                    minLines = 2,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (memory == null && groups.isNotEmpty()) {
                    Text(stringResource(R.string.memory_group), style = MaterialTheme.typography.labelLarge, color = palette.inkMuted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        groups.forEach { g ->
                            FilterChip(selected = group == g.id, onClick = { group = g.id }, label = { Text(g.name) })
                        }
                    }
                }
                if (memory != null) {
                    TextButton(onClick = onForget, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.memory_forget), color = palette.danger)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim(), group) }, enabled = text.isNotBlank()) {
                Text(stringResource(if (memory == null) R.string.memory_remember else R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
