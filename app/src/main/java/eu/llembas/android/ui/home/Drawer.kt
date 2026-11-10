package eu.llembas.android.ui.home

import android.app.Application
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.llembas.android.R
import eu.llembas.android.api.Chat
import eu.llembas.android.api.Doorstep
import eu.llembas.android.api.fit
import eu.llembas.android.lembas
import eu.llembas.android.ui.explain
import eu.llembas.android.ui.signin.refusal
import eu.llembas.android.ui.theme.LocalPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.ZoneId

data class ChatsState(val chats: List<Chat> = emptyList(), val more: Boolean = false, val loading: Boolean = false, val error: String? = null)

/** The drawer's list: every chat of the instance in use, both kinds, newest first. */
class ChatsViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(ChatsState())
    val state = _state.asStateFlow()

    /** Why this instance is refused, once it was asked what it is; null while it fits. */
    private var refused: Int? = null
    private var checked = false

    /** Read the list; the rows already shown stay while it is read again. */
    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val api = getApplication<Application>().lembas.instances.client() ?: return@launch
                // An instance signed in to before it was downgraded is refused here as at
                // sign-in. Unreachable is not refused: the list's own error says that better.
                if (!checked) {
                    runCatching { Doorstep().discover(api.base) }.onSuccess {
                        checked = true
                        refused = refusal(fit(it))
                    }
                }
                refused?.let { said ->
                    _state.value = ChatsState(error = getApplication<Application>().getString(said))
                    return@launch
                }
                val page = api.chats()
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
                val page = api.chats(before = last.id)
                _state.update { it.copy(chats = it.chats + page.chats, more = page.more) }
            } catch (e: Exception) {
                _state.update { it.copy(error = getApplication<Application>().explain(e)) }
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
                _state.update { it.copy(error = getApplication<Application>().explain(e)) }
                load()
            }
        }
    }
}

/** Where the drawer can go, besides a chat. */
interface DrawerActions {
    fun newChat() {}
    fun newAgentChat() {}
    fun open(chatId: String) {}
    fun archive(chat: Chat) {}
    fun files() {}
    fun scheduled() {}
    fun archived() {}
    fun settings() {}
    fun more() {}
    fun retry() {}
}

/**
 * The navigation drawer: New chat at the top, search, Files, Scheduled and Archived, then the chats --
 * today's, the earlier ones, and the agent chats -- and Settings with the account at the foot.
 * A long press on a chat offers Archive.
 */
@Composable
fun DrawerContent(
    state: ChatsState,
    current: String?,
    account: String,
    actions: DrawerActions,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val palette = LocalPalette.current
    var query by rememberSaveable { mutableStateOf("") }
    val groups = remember(state.chats, query, today) { groupChats(state.chats, query, today, zone) }
    ModalDrawerSheet(modifier = modifier, drawerContainerColor = palette.surface, drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)) {
        Column(Modifier.fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = actions::newChat) { Icon(Icons.Filled.EditNote, contentDescription = stringResource(R.string.new_chat)) }
            }
            TextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.chats_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear)) }
                    }
                },
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = palette.surfaceRaised,
                    unfocusedContainerColor = palette.surfaceRaised,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.weight(1f)) {
                item { DrawerItem(Icons.Filled.EditNote, stringResource(R.string.new_chat), onClick = actions::newChat) }
                item { DrawerItem(Icons.Filled.Terminal, stringResource(R.string.new_agent_chat), onClick = actions::newAgentChat) }
                item { DrawerItem(Icons.Filled.Folder, stringResource(R.string.files), onClick = actions::files) }
                item { DrawerItem(Icons.Filled.Schedule, stringResource(R.string.scheduled), onClick = actions::scheduled) }
                item { DrawerItem(Icons.Filled.Inventory2, stringResource(R.string.archived), onClick = actions::archived) }
                item { HorizontalDivider(color = palette.border, modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp)) }
                state.error?.let { error ->
                    item {
                        Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(error, color = palette.danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = actions::retry) { Text(stringResource(R.string.retry)) }
                        }
                    }
                }
                section(R.string.chats_today, groups.today, current, actions)
                section(R.string.chats_earlier, groups.earlier, current, actions)
                section(R.string.chats_agents, groups.agents, current, actions)
                if (groups.isEmpty && !state.loading && state.error == null) {
                    item {
                        Text(
                            stringResource(if (query.isBlank()) R.string.empty_chats else R.string.chats_none_found),
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.inkMuted,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
                        )
                    }
                }
                if (state.more && query.isBlank()) {
                    item { LaunchedEffect(state.chats.size) { actions.more() } }
                }
            }
            HorizontalDivider(color = palette.border)
            DrawerItem(Icons.Filled.Settings, stringResource(R.string.settings), subtitle = account.ifBlank { null }, onClick = actions::settings)
            Spacer(Modifier.size(8.dp))
        }
    }
}

private fun LazyListScope.section(title: Int, chats: List<Chat>, current: String?, actions: DrawerActions) {
    if (chats.isEmpty()) return
    item(key = "h$title") {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.labelLarge,
            color = LocalPalette.current.inkFaint,
            modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 12.dp, bottom = 4.dp),
        )
    }
    items(chats, key = { it.id }) { chat -> ChatItem(chat, chat.id == current, actions) }
}

@Composable
private fun DrawerItem(icon: ImageVector, label: String, subtitle: String? = null, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Row(Modifier.heightIn(min = 52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = palette.inkMuted, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = palette.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/** One chat: its title (which gives), and a dot for news or for a reply being written. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatItem(chat: Chat, selected: Boolean, actions: DrawerActions) {
    val palette = LocalPalette.current
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(if (selected) palette.accentSoft else Color.Transparent)
                .combinedClickable(onClick = { actions.open(chat.id) }, onLongClick = { menu = true })
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                chat.title.ifBlank { stringResource(R.string.new_chat) },
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) palette.ink else palette.inkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (chat.unread || chat.running) {
                Spacer(Modifier.width(12.dp))
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (chat.running) palette.leaf else palette.accent))
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Filled.Archive, contentDescription = null) },
                text = { Text(stringResource(R.string.archive)) },
                onClick = {
                    menu = false
                    actions.archive(chat)
                },
            )
        }
    }
}
