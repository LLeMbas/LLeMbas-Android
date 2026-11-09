@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package eu.llembas.android.ui.chat

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.llembas.android.R
import eu.llembas.android.api.AgentTarget
import eu.llembas.android.api.AgentsSetting
import eu.llembas.android.api.LinkedDevice
import eu.llembas.android.api.Message
import eu.llembas.android.api.ModelInfo
import eu.llembas.android.api.ShowCard
import eu.llembas.android.assist.Recorder
import eu.llembas.android.lembas
import eu.llembas.android.ui.ModelAvatar
import eu.llembas.android.ui.theme.LocalPalette
import eu.llembas.android.ui.viewer.FileViewer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chatId: String?,
    onBack: () -> Unit,
    onOpened: (String) -> Unit = {},
    agent: Boolean = false,
    model: ChatViewModel = viewModel(key = chatId ?: if (agent) "new-agent" else "new"),
) {
    val state by model.state.collectAsStateWithLifecycle()
    val palette = LocalPalette.current
    val list = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Message?>(null) }
    var showing by remember { mutableStateOf<ShowCard?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var activityOpen by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val copy: (String) -> Unit = { text ->
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("LLeMbas", text))
        // Android 13 and newer say "Copied" themselves.
        if (Build.VERSION.SDK_INT < 33) scope.launch { snackbar.showSnackbar(context.getString(R.string.copied)) }
    }

    LaunchedEffect(chatId) { model.open(chatId, agent = agent && chatId == null) }
    // A new chat gets its id from its first message; the route follows, so Back and a
    // rotation land on the chat rather than on an empty composer.
    LaunchedEffect(state.chat?.id) { state.chat?.id?.let { if (it != chatId) onOpened(it) } }
    // The chat's background agents, followed while the screen is in front and not otherwise.
    LaunchedEffect(state.chat?.id) {
        if (state.chat?.id != null) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.watchActivity() }
    }
    LaunchedEffect(state.error, state.notice) {
        (state.error ?: state.notice)?.let {
            snackbar.showSnackbar(it)
            model.clearError()
        }
    }
    val live = state.live
    val rows = state.messages
    // To the end when the transcript grows, and while a reply is being written -- but only if
    // the person is at the end already: reading back up is not interrupted by the next line.
    // Whether to follow is decided when the person's own scroll comes to rest, not when a
    // line arrives: by then the line has already made the list longer than the screen.
    var following by remember { mutableStateOf(true) }
    LaunchedEffect(list.isScrollInProgress) {
        if (!list.isScrollInProgress) following = !list.canScrollForward
    }
    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty()) list.toEnd()
    }
    val growth = live?.let { it.shown.size * 100_000 + (it.tail.text.length + it.tail.thinking.length) } ?: 0
    LaunchedEffect(growth) {
        if (growth > 0 && following) list.toEnd()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        state.chat?.model?.ifBlank { null }?.let { id ->
                            ModelAvatar(state.models.firstOrNull { it.id == id }, id, size = 28.dp, modifier = Modifier.padding(end = 10.dp))
                        }
                        Text(
                            state.chat?.title?.ifBlank { null } ?: stringResource(if (agent) R.string.new_agent_chat else R.string.new_chat),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    if (state.chat != null) {
                        IconButton(onClick = { activityOpen = true }) {
                            Icon(Icons.Filled.Bolt, contentDescription = stringResource(R.string.activity_open))
                        }
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.rename_chat)) }, onClick = {
                                menu = false
                                renaming = true
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.continue_reply)) }, onClick = {
                                menu = false
                                model.continueChat()
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.archive)) }, onClick = {
                                menu = false
                                model.change("archived", "true")
                                onBack()
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = {
                                menu = false
                                model.delete(onBack)
                            })
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column(Modifier.background(palette.surface).navigationBarsPadding().imePadding()) {
                if (state.loading || state.uploading) LinearProgressIndicator(Modifier.fillMaxWidth())
                live?.plan?.let { plan ->
                    Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
                        PlanBody(plan)
                    }
                }
                state.chat?.plan?.let { _ ->
                    if (live == null && state.chat?.mode == "plan") {
                        TextButton(onClick = { model.executePlan() }, modifier = Modifier.padding(horizontal = 8.dp)) {
                            Text(stringResource(R.string.plan_carry_out))
                        }
                    }
                }
                state.summary?.let { summary -> ActivityStrip(summary) { activityOpen = true } }
                // A server without the activity panel: its jobs frame, one chip each, as before.
                if (state.summary == null && live != null && live.jobs.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        live.jobs.forEach { job ->
                            AssistChip(onClick = {}, label = { Text("${job.id} · ${job.description.ifBlank { job.command }} · ${job.state}", maxLines = 1) })
                        }
                    }
                }
                if (live != null && live.status.isNotBlank()) {
                    Text(live.status, style = MaterialTheme.typography.labelSmall, color = palette.inkFaint, modifier = Modifier.padding(horizontal = 16.dp))
                }
                Composer(model, state, agent)
            }
        },
    ) { padding ->
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.more) {
                item { TextButton(onClick = model::older) { Text(stringResource(R.string.older)) } }
            }
            if (rows.isEmpty() && !state.loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(top = 96.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.empty_chat), color = palette.inkMuted)
                    }
                }
            }
            items(rows, key = { it.id }) { message ->
                when {
                    message.isUser -> Column {
                        UserBubble(message)
                        Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                            message.text?.takeIf { it.isNotBlank() }?.let { words -> CopyButton { copy(words) } }
                            if (!message.machine && live == null) {
                                TextButton(onClick = { editing = message }) { Text(stringResource(R.string.edit)) }
                            }
                        }
                    }

                    live != null && live.messageId == message.id && live.finished == null -> Column {
                        Steps(live.shown, onShow = { showing = it })
                        if (live.shown.isEmpty()) Text(stringResource(R.string.loading_line), color = palette.inkFaint)
                        live.metrics?.let { m -> ReplyFoot(message.copy(metrics = m)) }
                    }

                    else -> Column {
                        Steps(message.steps, onShow = { showing = it })
                        ReplyFoot(message)
                        val words = replyText(message)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (words.isNotBlank()) CopyButton { copy(words) }
                            if (live == null && message == rows.lastOrNull()) {
                                TextButton(onClick = { model.regenerate(message) }) { Text(stringResource(R.string.regenerate)) }
                            }
                        }
                    }
                }
            }
        }
    }

    // A card the reply is waiting on, as a sheet over the chat. One at a time, in the order asked.
    live?.cards?.firstOrNull()?.let { card ->
        ModalBottomSheet(
            onDismissRequest = {},
            // Not dismissable by a swipe: the reply is stopped until the card is answered, and
            // a card swiped away would leave it waiting with nothing on screen to say why.
            sheetState = rememberModalBottomSheetState(
                skipPartiallyExpanded = true,
                confirmValueChange = { it != SheetValue.Hidden },
            ),
        ) {
            CardSheet(card) { answer -> model.answer(card, answer) }
        }
    }

    if (activityOpen) {
        state.chat?.let { chat ->
            // A device's files are on the device: the panel lists them, the tree cannot open them.
            ActivityPanel(chat.id, state, model, canOpenFiles = !chat.isAgent, onClose = { activityOpen = false })
        }
    }

    showing?.let { file ->
        state.chat?.id?.let { id -> FileViewer(id, file, onClose = { showing = null }) }
    }

    if (renaming) {
        var title by remember { mutableStateOf(state.chat?.title.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.rename_chat)) },
            text = { OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(enabled = title.isNotBlank(), onClick = {
                    model.rename(title)
                    renaming = false
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    editing?.let { message ->
        var text by remember(message.id) { mutableStateOf(message.text.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.edit)) },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = {
                    model.edit(message, text)
                    editing = null
                }) { Text(stringResource(R.string.card_send)) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** To the very end of the transcript: the last row, then down to its foot however tall it is. */
private suspend fun LazyListState.toEnd() {
    val last = layoutInfo.totalItemsCount - 1
    if (last < 0) return
    scrollToItem(last)
    scrollBy(1_000_000f)
}

/** A reply's own words, as the person would copy them: its text steps, in order. */
private fun replyText(message: Message): String =
    message.steps.filter { it.kind == "text" }.mapNotNull { it.markdown }.joinToString("\n\n").ifBlank { message.text.orEmpty() }

@Composable
private fun CopyButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            Icons.Filled.ContentCopy,
            contentDescription = stringResource(R.string.copy_message),
            tint = LocalPalette.current.inkFaint,
        )
    }
}

/** The agent modes, as the web page offers them, in the order of how much the model may do. */
private val AGENT_MODES = listOf("manual", "edit", "auto", "plan")

@Composable
private fun modeName(mode: String): String = when (mode) {
    "manual" -> stringResource(R.string.mode_manual)
    "edit" -> stringResource(R.string.mode_edit)
    "auto" -> stringResource(R.string.mode_auto)
    "plan" -> stringResource(R.string.mode_plan)
    else -> mode.replaceFirstChar { it.uppercase() }
}

@SuppressLint("MissingPermission")
@Composable
private fun Composer(model: ChatViewModel, state: ChatState, agent: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var chosenModel by rememberSaveable { mutableStateOf<String?>(null) }
    var chosenEffort by rememberSaveable { mutableStateOf<String?>(null) }
    var chosenMin by rememberSaveable { mutableStateOf<Int?>(null) }
    var chosenMax by rememberSaveable { mutableStateOf<Int?>(null) }
    var askAgents by remember { mutableStateOf(false) }
    var planMode by rememberSaveable { mutableStateOf(false) }
    var agentMode by rememberSaveable { mutableStateOf("edit") }
    var device by rememberSaveable { mutableStateOf<String?>(null) }
    var directory by rememberSaveable { mutableStateOf("") }
    var askDirectory by remember { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var photo by remember { mutableStateOf<Uri?>(null) }
    val recorder = remember { Recorder(scope) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { model.attach(it) }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { model.attach(it) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        photo?.let { if (taken) model.attach(listOf(it)) }
        photo = null
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            recorder.start()
            listening = true
        }
    }
    // A message the server did not take comes back here, so nothing typed is lost.
    LaunchedEffect(state.unsent) {
        state.unsent?.let {
            if (text.isBlank()) text = it
            model.clearUnsent()
        }
    }
    val chat = state.chat
    val newAgent = agent && chat == null
    // The first linked device is the one a new agent chat runs on until another is chosen.
    val target: LinkedDevice? = state.devices.firstOrNull { it.id == device } ?: state.devices.firstOrNull()
    val current = chat?.model?.ifBlank { null } ?: chosenModel ?: state.models.firstOrNull { it.default }?.id
    val running = state.live != null && state.live.finished == null

    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        if (newAgent && state.devices.isEmpty() && !state.loading) {
            Text(
                stringResource(R.string.agent_no_device),
                style = MaterialTheme.typography.bodySmall,
                color = LocalPalette.current.inkMuted,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        if (state.pending.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.pending.forEach { a ->
                    InputChip(
                        selected = false,
                        onClick = { model.unattach(a) },
                        label = { Text(a.name, maxLines = 1) },
                        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.remove)) },
                    )
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (newAgent && state.devices.isNotEmpty()) {
                DeviceChip(state.devices, target) { device = it }
                AssistChip(
                    onClick = { askDirectory = true },
                    leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                    label = { Text(directory.ifBlank { stringResource(R.string.agent_directory) }, maxLines = 1) },
                )
            }
            ModelChip(state.models, current) { id ->
                if (chat == null) chosenModel = id else model.change("model", id)
            }
            val info = state.models.firstOrNull { it.id == current }
            if (info != null) {
                // A new chat keeps the choice until its first message makes it; an open one
                // changes at once, as the web page's control does. The control is the model's:
                // none for one level, a Thinking switch for off and one more, else the picker.
                val level = info.level(if (chat == null) chosenEffort else chat.effort?.ifBlank { null })
                LevelControl(info, level) { picked ->
                    if (chat == null) chosenEffort = picked else model.change("effort", picked)
                }
            }
            // The team's size, where a team is offered: the server's word for a chat that
            // exists, the model's facts for one about to be made.
            val teamOffered = if (chat != null) state.teamOffered else !newAgent && info?.mayHaveTeam == true
            if (teamOffered) {
                val setting = chat?.agents ?: AgentsSetting(chosenMin ?: 1, chosenMax ?: 4)
                AssistChip(
                    onClick = { askAgents = true },
                    leadingIcon = { Icon(Icons.Filled.Groups, contentDescription = null) },
                    label = { Text(stringResource(R.string.team_chip, setting.min.toString(), setting.max.toString()), maxLines = 1) },
                )
            }
            when {
                newAgent -> ModeChip(agentMode) { agentMode = it }

                chat != null && chat.isAgent -> ModeChip(chat.mode.ifBlank { "edit" }) { model.change("mode", it) }

                else -> FilterChip(
                    selected = if (chat == null) planMode else chat.mode == "plan",
                    onClick = {
                        if (chat == null) planMode = !planMode else model.change("mode", if (chat.mode == "plan") "" else "plan")
                    },
                    label = { Text(stringResource(R.string.mode_plan)) },
                )
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Box {
                IconButton(onClick = { attachMenu = true }) {
                    Icon(Icons.Filled.AttachFile, contentDescription = stringResource(R.string.attach))
                }
                DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.attach_take_photo)) }, onClick = {
                        attachMenu = false
                        model.photoTarget().also { photo = it }.let { takePhoto.launch(it) }
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.attach_choose_photos)) }, onClick = {
                        attachMenu = false
                        pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.attach_choose_files)) }, onClick = {
                        attachMenu = false
                        pickFiles.launch("*/*")
                    })
                }
            }
            TextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(if (listening) R.string.listening else R.string.composer_hint)) },
                modifier = Modifier.weight(1f).heightIn(max = 200.dp),
            )
            IconButton(onClick = {
                if (listening) {
                    listening = false
                    scope.launch {
                        val wav = recorder.stop()
                        if (wav.isNotEmpty()) {
                            runCatching { context.lembas.instances.client()?.transcribe(wav) }
                                .onSuccess { heard -> if (!heard.isNullOrBlank()) text = (text + " " + heard).trim() }
                        }
                    }
                } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    recorder.start()
                    listening = true
                } else {
                    askMic.launch(Manifest.permission.RECORD_AUDIO)
                }
            }) {
                Icon(if (listening) Icons.Filled.Stop else Icons.Filled.Mic, contentDescription = stringResource(R.string.dictate))
            }
            if (running && text.isBlank()) {
                IconButton(onClick = model::stop) { Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.stop)) }
            } else {
                IconButton(
                    enabled = (text.isNotBlank() || state.pending.isNotEmpty()) && !state.uploading && (!newAgent || target != null),
                    onClick = {
                        model.send(
                            text,
                            model = chosenModel,
                            mode = if (newAgent) {
                                agentMode
                            } else if (planMode) {
                                "plan"
                            } else {
                                null
                            },
                            effort = chosenEffort?.takeIf { picked -> state.models.firstOrNull { it.id == current }?.offered?.contains(picked) == true },
                            agent = if (newAgent) target?.let { AgentTarget(it.id, directory) } else null,
                            agents = if (chosenMin != null || chosenMax != null) AgentsSetting(chosenMin ?: 1, chosenMax ?: 4) else null,
                        )
                        text = ""
                    },
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send)) }
            }
        }
    }

    if (askAgents) {
        val now = chat?.agents ?: AgentsSetting(chosenMin ?: 1, chosenMax ?: 4)
        AgentsDialog(now, onDismiss = { askAgents = false }) { picked ->
            askAgents = false
            if (chat == null) {
                chosenMin = picked.min
                chosenMax = picked.max
            } else {
                model.setAgents(picked)
            }
        }
    }

    if (askDirectory) {
        var typed by remember { mutableStateOf(directory) }
        AlertDialog(
            onDismissRequest = { askDirectory = false },
            title = { Text(stringResource(R.string.agent_directory)) },
            text = {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.agent_directory_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    directory = typed.trim()
                    askDirectory = false
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { askDirectory = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun DeviceChip(devices: List<LinkedDevice>, current: LinkedDevice?, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            leadingIcon = { Icon(Icons.Filled.Terminal, contentDescription = stringResource(R.string.agent_device)) },
            label = { Text(current?.let { it.name.ifBlank { it.host } } ?: "—", maxLines = 1) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            devices.forEach { d ->
                DropdownMenuItem(
                    text = { Text(listOf(d.name, d.host).filter { it.isNotBlank() }.distinct().joinToString(" · ")) },
                    onClick = {
                        open = false
                        onPick(d.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun ModeChip(current: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { open = true }, label = { Text(modeName(current)) })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AGENT_MODES.forEach { m ->
                DropdownMenuItem(text = { Text(modeName(m)) }, onClick = {
                    open = false
                    onPick(m)
                })
            }
        }
    }
}

@Composable
private fun ModelChip(models: List<ModelInfo>, current: String?, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        val shown = models.firstOrNull { it.id == current }
        AssistChip(
            onClick = { open = true },
            leadingIcon = { ModelAvatar(shown, current ?: "?", size = 20.dp) },
            label = { Text(shown?.name ?: current ?: "—", maxLines = 1) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            models.forEach { m ->
                DropdownMenuItem(
                    leadingIcon = { ModelAvatar(m, m.id, size = 28.dp) },
                    text = { Text(m.name.ifBlank { m.id }) },
                    onClick = {
                        open = false
                        onPick(m.id)
                    },
                )
            }
        }
    }
}

/** A level's name: `off` and `default` in words, an effort as the model calls it. */
@Composable
private fun levelName(level: String): String = when (level) {
    "off" -> stringResource(R.string.level_off)
    "default" -> stringResource(R.string.level_default)
    else -> level
}

/**
 * How hard the model thinks, by the model's `control`: nothing for one level; a Thinking chip
 * that is on or off for `off` and one effort; the picker for more.
 */
@Composable
private fun LevelControl(model: ModelInfo, level: String, onPick: (String) -> Unit) {
    when (model.levelControl) {
        "switch" -> FilterChip(
            selected = level != "off",
            onClick = { onPick(if (level == "off") model.onLevel else "off") },
            leadingIcon = { Icon(Icons.Filled.Psychology, contentDescription = null) },
            label = { Text(stringResource(R.string.thinking_switch)) },
        )

        "slider" -> {
            var open by remember { mutableStateOf(false) }
            Box {
                AssistChip(onClick = { open = true }, label = { Text(stringResource(R.string.effort_level, levelName(level)), maxLines = 1) })
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    model.offered.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(levelName(option)) },
                            trailingIcon = { if (option == level) Text("✓") },
                            onClick = {
                                open = false
                                onPick(option)
                            },
                        )
                    }
                }
            }
        }

        else -> Unit
    }
}

/**
 * The team's size: the fewest and the most agents, its own included. Two sliders on one
 * column, each with its label and value on a row of its own, so the two line up whatever the
 * words. The most never goes under the fewest; the server checks the pair against its cap.
 */
@Composable
private fun AgentsDialog(now: AgentsSetting, onDismiss: () -> Unit, onSave: (AgentsSetting) -> Unit) {
    var low by remember { mutableStateOf(now.min.coerceIn(1, AgentsSetting.CAP)) }
    var high by remember { mutableStateOf(now.max.coerceIn(1, AgentsSetting.CAP)) }
    val palette = LocalPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.team_agents)) },
        text = {
            Column {
                Text(stringResource(R.string.team_hint), style = MaterialTheme.typography.bodySmall, color = palette.inkMuted)
                AgentsSlider(stringResource(R.string.team_fewest), low) {
                    low = it
                    if (high < it) high = it
                }
                AgentsSlider(stringResource(R.string.team_most), high) {
                    high = it
                    if (low > it) low = it
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(AgentsSetting(low, high)) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun AgentsSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Column(Modifier.padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(value.toString(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(Math.round(it).coerceIn(1, AgentsSetting.CAP)) },
            valueRange = 1f..AgentsSetting.CAP.toFloat(),
            steps = AgentsSetting.CAP - 2,
        )
    }
}
