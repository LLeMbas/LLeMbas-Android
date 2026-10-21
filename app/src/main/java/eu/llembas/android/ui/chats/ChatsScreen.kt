@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package eu.llembas.android.ui.chats

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ChatsState(val chats: List<Chat> = emptyList(), val more: Boolean = false, val loading: Boolean = false, val error: String? = null)

class ChatsViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(ChatsState())
    val state = _state.asStateFlow()
    private var kind = "chat"

    fun load(kind: String) {
        this.kind = kind
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val api = getApplication<Application>().lembas.instances.client() ?: return@launch
                val page = api.chats(kind = kind)
                _state.value = ChatsState(chats = page.chats, more = page.more)
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    fun more() {
        val last = _state.value.chats.lastOrNull() ?: return
        viewModelScope.launch {
            try {
                val api = getApplication<Application>().lembas.instances.client() ?: return@launch
                val page = api.chats(kind = kind, before = last.id)
                _state.update { it.copy(chats = it.chats + page.chats, more = page.more) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    /** Out of the way, and reversible: the web UI's Archive. */
    fun archive(chat: Chat) {
        _state.update { s -> s.copy(chats = s.chats.filterNot { it.id == chat.id }) }
        viewModelScope.launch {
            runCatching {
                getApplication<Application>().lembas.instances.client()
                    ?.updateChat(chat.id, buildJsonObject { put("archived", true) })
            }.onFailure { e ->
                _state.update { it.copy(error = e.message) }
                load(kind)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(onOpen: (String) -> Unit, onNew: () -> Unit, onSettings: () -> Unit, model: ChatsViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val kind = if (tab == 0) "chat" else "agent"
    LaunchedEffect(kind) { model.load(kind) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
        floatingActionButton = {
            if (tab == 0) {
                ExtendedFloatingActionButton(
                    onClick = onNew,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.new_chat)) },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.tab_chats)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.tab_agents)) })
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            }
            PullToRefreshBox(isRefreshing = state.loading, onRefresh = { model.load(kind) }, modifier = Modifier.fillMaxSize()) {
                if (state.chats.isEmpty() && !state.loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(if (tab == 0) R.string.empty_chats else R.string.empty_agents),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(32.dp),
                        )
                    }
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.chats, key = { it.id }) { chat ->
                        ChatRow(chat, onOpen = { onOpen(chat.id) }, onArchive = { model.archive(chat) })
                        HorizontalDivider()
                    }
                    if (state.more) {
                        item {
                            LaunchedEffect(state.chats.size) { model.more() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatRow(chat: Chat, onOpen: () -> Unit, onArchive: () -> Unit) {
    val swipe = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(state = swipe, onDismiss = { onArchive() }, backgroundContent = {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 24.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(stringResource(R.string.archive), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }) {
        ListItem(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
            leadingContent = {
                Icon(
                    if (chat.isAgent) Icons.Filled.Terminal else Icons.AutoMirrored.Filled.Chat,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            headlineContent = { Text(chat.title.ifBlank { stringResource(R.string.new_chat) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                val line = listOfNotNull(chat.model.ifBlank { null }, chat.device?.directory?.ifBlank { null }).joinToString(" · ")
                if (line.isNotEmpty()) Text(line, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (chat.unread || chat.running) {
                        Box(
                            Modifier.size(10.dp).clip(CircleShape).background(
                                if (chat.running) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                }
            },
        )
    }
}
