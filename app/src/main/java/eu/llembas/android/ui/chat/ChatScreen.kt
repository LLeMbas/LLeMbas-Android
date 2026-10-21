@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package eu.llembas.android.ui.chat

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.llembas.android.R
import eu.llembas.android.api.Message
import eu.llembas.android.api.ModelInfo
import eu.llembas.android.assist.Recorder
import eu.llembas.android.lembas
import eu.llembas.android.ui.theme.LocalPalette
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(chatId: String?, onBack: () -> Unit, onOpened: (String) -> Unit = {}, model: ChatViewModel = viewModel(key = chatId ?: "new")) {
    val state by model.state.collectAsStateWithLifecycle()
    val palette = LocalPalette.current
    val list = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Message?>(null) }

    LaunchedEffect(chatId) { model.open(chatId) }
    // A new chat gets its id from its first message; the route follows, so Back and a
    // rotation land on the chat rather than on an empty composer.
    LaunchedEffect(state.chat?.id) { state.chat?.id?.let { if (it != chatId) onOpened(it) } }
    LaunchedEffect(state.error, state.notice) {
        (state.error ?: state.notice)?.let {
            snackbar.showSnackbar(it)
            model.clearError()
        }
    }
    val live = state.live
    val rows = state.messages
    LaunchedEffect(rows.size, live?.shown?.size) {
        if (rows.isNotEmpty()) list.animateScrollToItem(rows.size - 1)
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
                    Text(
                        state.chat?.title?.ifBlank { null } ?: stringResource(R.string.new_chat),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    if (state.chat != null) {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
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
                if (live != null && live.jobs.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        live.jobs.forEach { job ->
                            AssistChip(onClick = {}, label = { Text("${job.id} · ${job.description.ifBlank { job.command }} · ${job.state}", maxLines = 1) })
                        }
                    }
                }
                if (live != null && live.status.isNotBlank()) {
                    Text(live.status, style = MaterialTheme.typography.labelSmall, color = palette.inkFaint, modifier = Modifier.padding(horizontal = 16.dp))
                }
                Composer(model, state)
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
                        if (!message.machine && live == null) {
                            TextButton(onClick = { editing = message }, modifier = Modifier.align(Alignment.End)) {
                                Text(stringResource(R.string.edit))
                            }
                        }
                    }

                    live != null && live.messageId == message.id && live.finished == null -> Column {
                        Steps(live.shown)
                        if (live.shown.isEmpty()) Text(stringResource(R.string.loading_line), color = palette.inkFaint)
                        live.metrics?.let { m -> ReplyFoot(message.copy(metrics = m)) }
                    }

                    else -> Column {
                        Steps(message.steps)
                        ReplyFoot(message)
                        if (live == null && message == rows.lastOrNull()) {
                            Row {
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

@SuppressLint("MissingPermission")
@Composable
private fun Composer(model: ChatViewModel, state: ChatState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var chosenModel by rememberSaveable { mutableStateOf<String?>(null) }
    var planMode by rememberSaveable { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    val recorder = remember { Recorder(scope) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { model.attach(it) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            recorder.start()
            listening = true
        }
    }
    val chat = state.chat
    val current = chat?.model ?: chosenModel ?: state.models.firstOrNull { it.default }?.id
    val running = state.live != null && state.live.finished == null

    Column(Modifier.fillMaxWidth().padding(8.dp)) {
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
            ModelChip(state.models, current) { id ->
                if (chat == null) chosenModel = id else model.change("model", id)
            }
            val efforts = state.models.firstOrNull { it.id == current }?.efforts.orEmpty()
            if (efforts.isNotEmpty()) {
                EffortChip(efforts, chat?.effort) { model.change("effort", it ?: "off") }
            }
            if (chat == null || !chat.isAgent) {
                FilterChip(
                    selected = if (chat == null) planMode else chat.mode == "plan",
                    onClick = {
                        if (chat == null) planMode = !planMode else model.change("mode", if (chat.mode == "plan") "" else "plan")
                    },
                    label = { Text(stringResource(R.string.mode_plan)) },
                )
            } else {
                AssistChip(onClick = {}, label = { Text(chat.mode.replaceFirstChar { it.uppercase() }) })
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            IconButton(onClick = { pick.launch("*/*") }) {
                Icon(Icons.Filled.AttachFile, contentDescription = stringResource(R.string.attach))
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
                    enabled = text.isNotBlank() || state.pending.isNotEmpty(),
                    onClick = {
                        model.send(text, model = chosenModel, mode = if (planMode) "plan" else null)
                        text = ""
                    },
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send)) }
            }
        }
    }
}

@Composable
private fun ModelChip(models: List<ModelInfo>, current: String?, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { open = true }, label = { Text(models.firstOrNull { it.id == current }?.name ?: current ?: "—", maxLines = 1) })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            models.forEach { m ->
                DropdownMenuItem(text = { Text(m.name) }, onClick = {
                    open = false
                    onPick(m.id)
                })
            }
        }
    }
}

@Composable
private fun EffortChip(efforts: List<String>, current: String?, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { open = true }, label = { Text(current ?: stringResource(R.string.effort_off)) })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.effort_off)) }, onClick = {
                open = false
                onPick(null)
            })
            efforts.forEach { e ->
                DropdownMenuItem(text = { Text(e) }, onClick = {
                    open = false
                    onPick(e)
                })
            }
        }
    }
}
