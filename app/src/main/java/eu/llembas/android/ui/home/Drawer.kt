package eu.llembas.android.ui.home

import android.app.Application
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
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
import eu.llembas.android.api.Folder
import eu.llembas.android.api.fit
import eu.llembas.android.lembas
import eu.llembas.android.ui.explain
import eu.llembas.android.ui.signin.refusal
import eu.llembas.android.ui.theme.LocalPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.ZoneId

data class ChatsState(
    val chats: List<Chat> = emptyList(),
    val more: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    /** The person's chat folders (`GET /folders`); empty where they have none. */
    val folders: List<Folder> = emptyList(),
)

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
                // Folders are a side dish: the chats show without them if they cannot be read.
                val folders = runCatching { api.folders() }.getOrDefault(_state.value.folders)
                _state.value = ChatsState(chats = page.chats, more = page.more, folders = folders)
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

    /** Pinned chats stay at the top of the list, wherever they are filed (the web's Pin). */
    fun pin(chat: Chat, pinned: Boolean) = changeChat(chat.copy(pinned = pinned), buildJsonObject { put("pinned", pinned) })

    /** File [chat] in [folderId], or take it out of its folder with null. */
    fun move(chat: Chat, folderId: String?) = changeChat(chat.copy(folderId = folderId), buildJsonObject { put("folder_id", folderId) })

    private fun changeChat(changed: Chat, body: JsonObject) {
        _state.update { s -> s.copy(chats = s.chats.map { if (it.id == changed.id) changed else it }) }
        viewModelScope.launch {
            runCatching { getApplication<Application>().lembas.instances.client()?.updateChat(changed.id, body) }
                .onFailure { e -> failed(e) }
        }
    }

    /** A new folder at the top, and [then] the chat to file in it, if any. */
    fun newFolder(name: String, then: Chat? = null) {
        viewModelScope.launch {
            runCatching {
                val api = getApplication<Application>().lembas.instances.client() ?: return@launch
                val folder = api.createFolder(name)
                _state.update { it.copy(folders = it.folders + folder) }
                if (then != null) move(then, folder.id)
            }.onFailure { e -> failed(e) }
        }
    }

    fun renameFolder(folder: Folder, name: String) = changeFolder(folder.copy(name = name), buildJsonObject { put("name", name) })

    /** Open or fold a folder; the server keeps it, so the web's sidebar folds the same. */
    fun toggleFolder(folder: Folder) = changeFolder(folder.copy(collapsed = !folder.collapsed), buildJsonObject { put("collapsed", !folder.collapsed) })

    private fun changeFolder(changed: Folder, body: JsonObject) {
        _state.update { s -> s.copy(folders = s.folders.map { if (it.id == changed.id) changed else it }) }
        viewModelScope.launch {
            runCatching { getApplication<Application>().lembas.instances.client()?.updateFolder(changed.id, body) }
                .onFailure { e -> failed(e) }
        }
    }

    /** The folder and those inside it go; their chats stay, in no folder (the server's rule). */
    fun deleteFolder(folder: Folder) {
        viewModelScope.launch {
            runCatching { getApplication<Application>().lembas.instances.client()?.deleteFolder(folder.id) }
                .onSuccess { load() }
                .onFailure { e -> failed(e) }
        }
    }

    /** A refusal is the server's sentence; the list is read again so it shows what is true. */
    private fun failed(e: Throwable) {
        _state.update { it.copy(error = getApplication<Application>().explain(e)) }
        load()
    }
}

/** Where the drawer can go, besides a chat. */
interface DrawerActions {
    fun newChat() {}
    fun newAgentChat() {}
    fun open(chatId: String) {}
    fun archive(chat: Chat) {}
    fun pin(chat: Chat, pinned: Boolean) {}
    fun move(chat: Chat, folderId: String?) {}
    fun newFolder(name: String, then: Chat?) {}
    fun renameFolder(folder: Folder, name: String) {}
    fun toggleFolder(folder: Folder) {}
    fun deleteFolder(folder: Folder) {}
    fun files() {}
    fun scheduled() {}
    fun archived() {}
    fun memory() {}
    fun settings() {}
    fun more() {}
    fun retry() {}
}

/**
 * The navigation drawer: New chat (and New folder) at the top, search, Files, Scheduled,
 * Archived and Memory, then the chats -- the pinned ones, the folders as a tree with theirs, then today's,
 * the earlier ones and the agent chats -- and Settings with the account at the foot. A long press
 * on a chat offers Pin, Move to folder and Archive; on a folder, Rename and Delete.
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
    val groups = remember(state.chats, state.folders, query, today) { groupChats(state.chats, query, today, zone, state.folders) }
    // The one dialog open at a time: a chat to file, a folder to name (new or renamed), one to delete.
    var moving by remember { mutableStateOf<Chat?>(null) }
    var naming by remember { mutableStateOf<FolderNaming?>(null) }
    var deleting by remember { mutableStateOf<Folder?>(null) }
    val chatActions = remember(actions) {
        ChatRowActions(open = actions::open, archive = actions::archive, pin = actions::pin, move = { moving = it })
    }
    ModalDrawerSheet(modifier = modifier, drawerContainerColor = palette.surface, drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)) {
        Column(Modifier.fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { naming = FolderNaming() }) {
                    Icon(Icons.Filled.CreateNewFolder, contentDescription = stringResource(R.string.folder_new))
                }
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
                item { DrawerItem(Icons.Filled.Psychology, stringResource(R.string.memory), onClick = actions::memory) }
                item { HorizontalDivider(color = palette.border, modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp)) }
                state.error?.let { error ->
                    item {
                        Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(error, color = palette.danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = actions::retry) { Text(stringResource(R.string.retry)) }
                        }
                    }
                }
                section(R.string.chats_pinned, groups.pinned, current, chatActions, key = "pinned")
                groups.folders.forEach { group ->
                    item(key = "f${group.folder.id}") {
                        FolderItem(
                            group.folder,
                            group.depth,
                            onToggle = { actions.toggleFolder(group.folder) },
                            onRename = { naming = FolderNaming(group.folder) },
                            onDelete = { deleting = group.folder },
                        )
                    }
                    items(group.chats, key = { "f${group.folder.id}/${it.id}" }) { chat ->
                        ChatItem(chat, chat.id == current, chatActions, indent = 16 * (group.depth + 1))
                    }
                }
                section(R.string.chats_today, groups.today, current, chatActions)
                section(R.string.chats_earlier, groups.earlier, current, chatActions)
                section(R.string.chats_agents, groups.agents, current, chatActions)
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
    moving?.let { chat ->
        MoveDialog(
            chat,
            state.folders,
            onPick = { folder ->
                moving = null
                actions.move(chat, folder)
            },
            onNewFolder = {
                moving = null
                naming = FolderNaming(then = chat)
            },
            onDismiss = { moving = null },
        )
    }
    naming?.let { asked ->
        FolderNameDialog(
            asked.folder?.name.orEmpty(),
            title = if (asked.folder != null) R.string.folder_rename else R.string.folder_new,
            onSave = { name ->
                naming = null
                if (asked.folder != null) actions.renameFolder(asked.folder, name) else actions.newFolder(name, asked.then)
            },
            onDismiss = { naming = null },
        )
    }
    deleting?.let { folder ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            text = { Text(stringResource(R.string.folder_delete_confirm, folder.name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    actions.deleteFolder(folder)
                }) { Text(stringResource(R.string.folder_delete), color = LocalPalette.current.danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** A folder to name: [folder] to rename, or a new one (into which [then] is filed, if given). */
private data class FolderNaming(val folder: Folder? = null, val then: Chat? = null)

/** What a chat's row can do: open it, and from its long-press menu Pin, Move to folder, Archive. */
private class ChatRowActions(val open: (String) -> Unit, val archive: (Chat) -> Unit, val pin: (Chat, Boolean) -> Unit, val move: (Chat) -> Unit)

private fun LazyListScope.section(title: Int, chats: List<Chat>, current: String?, actions: ChatRowActions, key: String = "c") {
    if (chats.isEmpty()) return
    item(key = "h$title") {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.labelLarge,
            color = LocalPalette.current.inkFaint,
            modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 12.dp, bottom = 4.dp),
        )
    }
    items(chats, key = { "$key${it.id}" }) { chat -> ChatItem(chat, chat.id == current, actions) }
}

/**
 * A folder's row: its name (which gives), indented by its depth, and a chevron that says whether
 * it is open. A tap folds or opens it; a long press offers Rename folder and Delete folder.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderItem(folder: Folder, depth: Int, onToggle: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val palette = LocalPalette.current
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(start = 12.dp + (16 * depth).dp, end = 12.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .combinedClickable(onClick = onToggle, onLongClick = { menu = true })
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Folder, contentDescription = null, tint = palette.inkFaint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                folder.name,
                style = MaterialTheme.typography.bodyLarge,
                color = palette.inkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (folder.collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                contentDescription = null,
                tint = palette.inkFaint,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = null) },
                text = { Text(stringResource(R.string.folder_rename)) },
                onClick = {
                    menu = false
                    onRename()
                },
            )
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                text = { Text(stringResource(R.string.folder_delete)) },
                onClick = {
                    menu = false
                    onDelete()
                },
            )
        }
    }
}

/** Move to folder: No folder, every folder as its tree, and New folder at the foot. */
@Composable
private fun MoveDialog(chat: Chat, folders: List<Folder>, onPick: (String?) -> Unit, onNewFolder: () -> Unit, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    val tree = remember(folders) { folderTree(folders) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.folder_move)) },
        text = {
            LazyColumn {
                item { PickRow(stringResource(R.string.folder_none), depth = 0, chosen = chat.folderId == null) { onPick(null) } }
                items(tree, key = { it.first.id }) { (folder, depth) ->
                    PickRow(folder.name, depth, chosen = chat.folderId == folder.id) { onPick(folder.id) }
                }
                item {
                    TextButton(onClick = onNewFolder, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Filled.CreateNewFolder, contentDescription = null, tint = palette.accent)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.folder_new))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun PickRow(label: String, depth: Int, chosen: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (chosen) palette.accentSoft else Color.Transparent)
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(start = 8.dp + (16 * depth).dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Folder, contentDescription = null, tint = palette.inkFaint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** New folder / Rename folder: one line, saved only with a name. */
@Composable
private fun FolderNameDialog(initial: String, title: Int, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.folder_name)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
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
private fun ChatItem(chat: Chat, selected: Boolean, actions: ChatRowActions, indent: Int = 0) {
    val palette = LocalPalette.current
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(start = 12.dp + indent.dp, end = 12.dp)) {
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
                leadingIcon = { Icon(Icons.Filled.PushPin, contentDescription = null) },
                text = { Text(stringResource(if (chat.pinned) R.string.chat_unpin else R.string.chat_pin)) },
                onClick = {
                    menu = false
                    actions.pin(chat, !chat.pinned)
                },
            )
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = null) },
                text = { Text(stringResource(R.string.folder_move)) },
                onClick = {
                    menu = false
                    actions.move(chat)
                },
            )
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
