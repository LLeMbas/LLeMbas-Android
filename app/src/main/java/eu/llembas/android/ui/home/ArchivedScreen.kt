@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package eu.llembas.android.ui.home

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.llembas.android.R
import eu.llembas.android.api.Chat
import eu.llembas.android.lembas
import eu.llembas.android.ui.explain
import eu.llembas.android.ui.theme.LocalPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The archived chats (`GET /api/v2/chats?archived=true`), newest first, a page at a time. */
class ArchivedViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(ChatsState(loading = true))
    val state = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val api = getApplication<Application>().lembas.instances.client() ?: return@launch
                val page = api.chats(archived = true)
                _state.value = ChatsState(chats = page.chats, more = page.more)
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = getApplication<Application>().explain(e)) }
            }
        }
    }

    fun more() {
        val last = _state.value.chats.lastOrNull() ?: return
        viewModelScope.launch {
            try {
                val api = getApplication<Application>().lembas.instances.client() ?: return@launch
                val page = api.chats(archived = true, before = last.id)
                _state.update { it.copy(chats = it.chats + page.chats, more = page.more) }
            } catch (e: Exception) {
                _state.update { it.copy(error = getApplication<Application>().explain(e)) }
            }
        }
    }

    /** Back among the chats: the web UI's Restore chat. [then] refreshes the drawer's list. */
    fun restore(chat: Chat, then: () -> Unit) {
        _state.update { s -> s.copy(chats = s.chats.filterNot { it.id == chat.id }) }
        viewModelScope.launch {
            runCatching {
                getApplication<Application>().lembas.instances.client()
                    ?.updateChat(chat.id, buildJsonObject { put("archived", false) })
            }.onSuccess { then() }
                .onFailure { e ->
                    _state.update { it.copy(error = getApplication<Application>().explain(e)) }
                    load()
                }
        }
    }
}

interface ArchivedActions {
    fun open(chat: Chat) {}
    fun restore(chat: Chat) {}
    fun more() {}
    fun reload() {}
}

@Composable
fun ArchivedScreen(onBack: () -> Unit, onOpen: (String) -> Unit, onRestored: () -> Unit, model: ArchivedViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { model.load() }
    ArchivedContent(
        state,
        object : ArchivedActions {
            override fun open(chat: Chat) = onOpen(chat.id)

            override fun restore(chat: Chat) = model.restore(chat, onRestored)

            override fun more() = model.more()

            override fun reload() = model.load()
        },
        onBack,
    )
}

/**
 * The archived chats: each opens as it is, and Restore puts it back among the chats. A row is
 * the title (which gives) and the restore button (which keeps its 48 dp).
 */
@Composable
fun ArchivedContent(state: ChatsState, actions: ArchivedActions, onBack: () -> Unit = {}) {
    val palette = LocalPalette.current
    Scaffold(
        containerColor = palette.bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.bg, scrolledContainerColor = palette.bg),
                title = { Text(stringResource(R.string.archived)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(isRefreshing = state.loading && state.chats.isNotEmpty(), onRefresh = actions::reload, modifier = Modifier.padding(padding)) {
            LazyColumn(Modifier.fillMaxSize()) {
                state.error?.let { error ->
                    item {
                        ListItem(
                            headlineContent = { Text(error, color = palette.danger, style = MaterialTheme.typography.bodyMedium) },
                            trailingContent = { TextButton(onClick = actions::reload) { Text(stringResource(R.string.retry)) } },
                            colors = ListItemDefaults.colors(containerColor = palette.bg),
                        )
                    }
                }
                if (state.chats.isEmpty() && !state.loading && state.error == null) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(24.dp)) {
                            Text(stringResource(R.string.archived_none), color = palette.inkMuted, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                items(state.chats, key = { it.id }) { chat ->
                    ListItem(
                        headlineContent = {
                            Text(chat.title.ifBlank { stringResource(R.string.new_chat) }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        },
                        leadingContent = if (chat.kind == "agent") {
                            { Icon(Icons.Filled.Terminal, contentDescription = null, tint = palette.inkMuted) }
                        } else {
                            null
                        },
                        trailingContent = {
                            IconButton(onClick = { actions.restore(chat) }) {
                                Icon(Icons.Filled.Unarchive, contentDescription = stringResource(R.string.archived_restore), tint = palette.accent)
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = palette.bg),
                        modifier = Modifier.clickable { actions.open(chat) },
                    )
                    HorizontalDivider(color = palette.border, modifier = Modifier.padding(start = 16.dp))
                }
                if (state.more) {
                    item { LaunchedEffect(state.chats.size) { actions.more() } }
                }
            }
        }
    }
}
