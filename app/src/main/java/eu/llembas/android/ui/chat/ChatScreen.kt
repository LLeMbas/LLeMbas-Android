@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package eu.llembas.android.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.llembas.android.R
import eu.llembas.android.api.Attachment
import eu.llembas.android.api.Drawn
import eu.llembas.android.api.GeneratedMedia
import eu.llembas.android.api.MediaJob
import eu.llembas.android.api.MediaView
import eu.llembas.android.api.Message
import eu.llembas.android.api.Raw
import eu.llembas.android.api.ShowCard
import eu.llembas.android.api.drawn
import eu.llembas.android.ui.ModelAvatar
import eu.llembas.android.ui.ProgressStatus
import eu.llembas.android.ui.files.TransferHost
import eu.llembas.android.ui.files.TransferViewModel
import eu.llembas.android.ui.files.rememberSaver
import eu.llembas.android.ui.theme.LocalPalette
import eu.llembas.android.ui.viewer.FileViewer
import eu.llembas.android.ui.viewer.PlayerDialog
import eu.llembas.android.ui.viewer.Playing
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * One chat, with its model behind it: the transcript, the composer, and the sheets and panels
 * that open over them. [chatId] null is a new chat (an agent chat with [agent]); its first
 * message makes it, and [onOpened] then moves the route to it.
 */
@Composable
fun ChatScreen(
    chatId: String?,
    onMenu: () -> Unit,
    onNewChat: () -> Unit,
    onLeave: () -> Unit,
    onOpened: (String) -> Unit = {},
    agent: Boolean = false,
    onKind: ((agent: Boolean) -> Unit)? = null,
    /** Entered from a report (its Reply): the bar says "Report: title" and has Back, to the report, where the menu is. */
    report: String? = null,
    /** Open the person's Files at a folder: where a generated file's "Open in Files" leads. */
    onFiles: (String) -> Unit = {},
    model: ChatViewModel = viewModel(key = chatId ?: if (agent) "new-agent" else "new"),
) {
    val state by model.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var activityOpen by rememberSaveable { mutableStateOf(false) }
    var showing by remember { mutableStateOf<ShowCard?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val isAgent = agent || state.chat?.isAgent == true

    LaunchedEffect(chatId) { model.open(chatId, agent = agent && chatId == null) }
    // A new chat gets its id from its first message; the route follows, so Back and a
    // rotation land on the chat rather than on an empty composer.
    LaunchedEffect(state.chat?.id) { state.chat?.id?.let { if (it != chatId) onOpened(it) } }
    // The chat's background agents, followed while the screen is in front and not otherwise.
    LaunchedEffect(state.chat?.id) {
        if (state.chat?.id != null) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.watchActivity() }
    }
    // The chat's long generations (a video, a track), read again every few seconds while the
    // screen is in front and not otherwise. An agent chat runs on a computer and makes none.
    LaunchedEffect(state.chat?.id) {
        if (state.chat?.id != null && !isAgent) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.watchMedia() }
    }
    LaunchedEffect(state.error, state.notice) {
        (state.error ?: state.notice)?.let {
            snackbar.showSnackbar(it)
            model.clearError()
        }
    }

    // What a generated file's card does: the player over the chat, the folder in Files, and the
    // ways a file leaves the app. Its own transfers, as each screen that hands files out has.
    val transfers: TransferViewModel = viewModel(key = "chat-transfers")
    val saveTo = rememberSaver(transfers)
    var playing by remember { mutableStateOf<Playing?>(null) }
    val media = remember(model, transfers) {
        object : MediaActions {
            override fun play(media: GeneratedMedia) {
                playing = Playing(media.raw, media.title, media.size, video = media.view == MediaView.Video)
            }

            override fun play(attachment: Attachment) {
                playing = Playing(Raw.Attached(attachment.id), attachment.name, attachment.size, video = attachment.kind == "video")
            }

            override fun openInFiles(media: GeneratedMedia) = onFiles(media.folder)

            override fun download(media: GeneratedMedia) = saveTo(media.raw, media.title, media.mime)

            override fun share(media: GeneratedMedia) = transfers.share(media.raw, media.title, media.mime)

            override fun openWith(media: GeneratedMedia) = transfers.openWith(media.raw, media.title, media.mime)

            override fun cancel(job: MediaJob) = model.cancelJob(job)
        }
    }

    // Read aloud on a reply, when the instance has a voice; it stops when the chat is left.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val readAloud = remember {
        ReadAloud(
            context,
            scope,
            media = { model.state.value.mediaFor },
            onFallback = { source -> model.mediaFellBack(MEDIA_SPEECH, context.getString(R.string.read_by_fallback, source)) },
            onError = { said -> scope.launch { snackbar.showSnackbar(said) } },
        )
    }
    LaunchedEffect(Unit) { readAloud.load() }
    DisposableEffect(Unit) { onDispose { readAloud.stop() } }

    CompositionLocalProvider(LocalReadAloud provides readAloud.takeIf { it.available }) {
        ChatContent(
            state = state,
            actions = model,
            agent = isAgent,
            snackbar = snackbar,
            onMenu = onMenu,
            onNewChat = onNewChat,
            onLeave = onLeave,
            onActivity = { activityOpen = true },
            onShow = { showing = it },
            onKind = onKind,
            report = report,
            media = media,
        )
    }

    if (activityOpen) {
        // A device's files are on the device: the sheet lists them, the tree cannot open them.
        ActivityPanel(state.chat?.id, state, model, canOpenFiles = !isAgent, onClose = { activityOpen = false })
    }
    showing?.let { file ->
        state.chat?.id?.let { id -> FileViewer(id, file, onClose = { showing = null }) }
    }
    playing?.let { PlayerDialog(it, onClose = { playing = null }, gone = stringResource(R.string.media_gone)) }
    TransferHost(transfers)
}

/** What the bar says of a chat entered from a report: "Report: title", or "Report" when the title is not known. */
@Composable
private fun reportTitle(title: String): String = if (title.isBlank()) stringResource(R.string.report) else stringResource(R.string.report_chat_title, title)

/**
 * The chat as it is drawn, from [state] alone: what the screenshot tests render with fake data.
 * Everything it does goes through [actions].
 */
@Composable
fun ChatContent(
    state: ChatState,
    actions: ChatActions,
    agent: Boolean,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    onMenu: () -> Unit = {},
    onNewChat: () -> Unit = {},
    onLeave: () -> Unit = {},
    onActivity: () -> Unit = {},
    onShow: (ShowCard) -> Unit = {},
    onKind: ((agent: Boolean) -> Unit)? = null,
    report: String? = null,
    /** What the cards of generated media do; null draws none of them (a picture stays a picture). */
    media: MediaActions? = null,
) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chatMedia = remember(state.mediaJobs, media) { media?.let { ChatMedia(state.mediaJobs, it) } }
    // A chat opens at its end, where the conversation is, not at its first message.
    val list = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, state.messages.size - 1 + if (state.more) 1 else 0))
    var menu by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var tuning by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Message?>(null) }
    var suggestion by remember { mutableStateOf("") }
    var suggested by remember { mutableIntStateOf(0) }
    val copy: (String) -> Unit = { text ->
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("LLeMbas", text))
        // Android 13 and newer say "Copied" themselves.
        if (Build.VERSION.SDK_INT < 33) scope.launch { snackbar.showSnackbar(context.getString(R.string.copied)) }
    }
    val chat = state.chat
    val live = state.live
    val rows = state.messages
    val info = state.modelInfo

    // The view keeps to the end while a reply is written only for a person who is there
    // (`Follow.kt` has the rule): a finger on the list ends the following at once, and where the
    // list comes to rest says whether it is taken up again. Kept over a rotation, with the place.
    var following by rememberSaveable { mutableStateOf(true) }
    var drawnBefore by rememberSaveable { mutableStateOf(false) }
    val slack = with(LocalDensity.current) { 48.dp.roundToPx() }
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect { if (it is DragInteraction.Start) following = false }
    }
    LaunchedEffect(list) {
        var moving = false
        snapshotFlow { list.isScrollInProgress }.collect { now ->
            if (moving && !now) following = followsAt(list.distanceToEnd(), slack)
            moving = now
        }
    }
    // The rows as they were at the last draw, to tell a message sent from an older page loaded.
    val known = remember { arrayOfNulls<List<Message>>(1) }
    LaunchedEffect(rows) {
        val before = known[0]
        known[0] = rows
        val change = transcriptChange(before, rows, restored = drawnBefore, sent = state.lastSent)
        if (rows.isNotEmpty()) drawnBefore = true
        if (change == TranscriptChange.Older) {
            // An older page above the very top: the row that was first stays first, so reading
            // goes on upwards from it. Anywhere else the list keeps its place by the rows' keys.
            val kept = keptRow(before.orEmpty(), rows)
            if (kept != null && list.firstVisibleItemIndex == 0) yielding { list.scrollToItem(kept + if (state.more) 1 else 0) }
        } else if (movesToEnd(change, following)) {
            following = true
            yielding { list.toEnd() }
        }
    }
    val growth = live?.let { it.shown.size * 100_000 + (it.tail.text.length + it.tail.thinking.length) } ?: 0
    LaunchedEffect(growth) { if (growth > 0 && following && !list.isScrollInProgress) yielding { list.toEnd() } }
    // The keyboard opening takes the list's foot away: a person at the end stays at the end.
    LaunchedEffect(list) {
        // A finger on the list ends a scroll of ours by cancelling it: this follower goes on.
        snapshotFlow { list.layoutInfo.viewportSize.height }.drop(1).collect { if (following && !list.isScrollInProgress) yielding { list.toEnd() } }
    }

    CompositionLocalProvider(LocalCopy provides copy, LocalChatMedia provides chatMedia) {
        Scaffold(
            containerColor = palette.bg,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.bg, scrolledContainerColor = palette.bg),
                    navigationIcon = {
                        if (report != null) {
                            // A report's conversation is reached from the report, and Back returns there.
                            IconButton(onClick = onMenu) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
                        } else {
                            IconButton(onClick = onMenu) { Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.chats_open)) }
                        }
                    },
                    title = {
                        ModelTitle(
                            model = info,
                            fallback = state.modelWord ?: stringResource(R.string.app_name),
                            // A report's conversation says which report: by what it was entered with, else by
                            // what the chat itself says (after the app was closed in between).
                            subtitle = if (report != null || chat?.report != null) {
                                reportTitle(report?.ifBlank { null } ?: chat?.report?.title.orEmpty())
                            } else {
                                chat?.title?.ifBlank { null }
                            },
                            onClick = { picking = true },
                        )
                    },
                    actions = {
                        // The activity sheet, always within reach: context, background tasks, plan.
                        IconButton(onClick = onActivity) { Icon(Icons.Filled.Bolt, contentDescription = stringResource(R.string.activity_open)) }
                        if (chat != null) {
                            IconButton(onClick = onNewChat) { Icon(Icons.Filled.EditNote, contentDescription = stringResource(R.string.new_chat)) }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more)) }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    MenuItem(Icons.Filled.DriveFileRenameOutline, stringResource(R.string.rename_chat)) {
                                        menu = false
                                        renaming = true
                                    }
                                    MenuItem(Icons.Filled.PlayArrow, stringResource(R.string.continue_reply)) {
                                        menu = false
                                        actions.continueChat()
                                    }
                                    // Only what may be done to this chat: a report's conversation is not archived.
                                    if (chat.allows("archive")) {
                                        MenuItem(Icons.Filled.Archive, stringResource(R.string.archive)) {
                                            menu = false
                                            actions.change("archived", "true")
                                            onLeave()
                                        }
                                    }
                                    MenuItem(Icons.Filled.Delete, stringResource(R.string.delete)) {
                                        menu = false
                                        actions.delete(onLeave)
                                    }
                                }
                            }
                        }
                    },
                )
            },
            bottomBar = {
                Column(Modifier.background(palette.bg).navigationBarsPadding().imePadding().padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
                    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 4.dp))
                    if (rows.isEmpty() && chat == null && !agent) {
                        Suggestions {
                            suggestion = it
                            suggested++
                        }
                    }
                    live?.plan?.let { plan ->
                        Surface(
                            color = palette.surfaceRaised,
                            contentColor = palette.ink,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        ) {
                            Column(Modifier.heightIn(max = 180.dp).padding(horizontal = 16.dp, vertical = 8.dp)) { PlanBody(plan) }
                        }
                    }
                    if (chat?.plan != null && live == null && chat.mode == "plan") {
                        TextButton(onClick = actions::executePlan) { Text(stringResource(R.string.plan_carry_out)) }
                    }
                    state.summary?.let { summary -> ActivityStrip(summary, onActivity) }
                    // A server without the activity panel: its jobs frame, one chip each, as before.
                    if (state.summary == null && live != null && live.jobs.isNotEmpty()) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            live.jobs.forEach { job ->
                                AssistChip(onClick = {
                                }, label = { Text("${job.id} · ${job.description.ifBlank { job.command }} · ${job.state}", maxLines = 1) })
                            }
                        }
                    }
                    // The 3.4.0 progress line, ticking; the free status text from an older server.
                    if (live != null) {
                        ProgressStatus(
                            live.progress,
                            live.status,
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.inkFaint,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                    // A new value each tap, so the same suggestion twice still fills the field.
                    androidx.compose.runtime.key(suggested) {
                        Composer(state, actions, agent, onTune = { tuning = true }, initial = suggestion)
                    }
                }
            },
        ) { padding ->
            if (rows.isEmpty() && !state.loading) {
                Greeting(
                    state,
                    agent,
                    onTune = { tuning = true },
                    onKind = onKind.takeIf { chat == null },
                    onRecheck = actions::recheckDevices,
                    modifier = Modifier.padding(padding),
                )
            } else {
                LazyColumn(
                    state = list,
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    if (state.more) {
                        item { TextButton(onClick = actions::older) { Text(stringResource(R.string.older)) } }
                    }
                    items(rows, key = { it.id }) { message ->
                        // The first row is the chat's first message only once nothing older is left to load.
                        val first = !state.more && message.id == rows.firstOrNull()?.id
                        when (drawn(message, first, reportChat = report != null || chat?.report != null)) {
                            // The server's own line in the person's place: nothing, or a note.
                            Drawn.Hidden -> Unit

                            Drawn.Note -> MachineNote(message.text.orEmpty())

                            Drawn.Person -> UserMessage(message, canEdit = live == null, onEdit = { editing = message })

                            // One call for a reply being written and the same reply finished:
                            // what the person opened in it (a thinking chip) stays open at `done`.
                            Drawn.Reply -> {
                                val writing = live?.takeIf { it.messageId == message.id && it.finished == null }
                                AssistantMessage(
                                    message,
                                    writing?.shown ?: message.steps,
                                    writing = writing != null,
                                    canRegenerate = live == null && message == rows.lastOrNull(),
                                    onRegenerate = { actions.regenerate(message) },
                                    onShow = onShow,
                                    models = state.models,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (picking) {
        ModalBottomSheet(onDismissRequest = { picking = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            ModelPickerContent(state.models, state.modelId, onPick = { id ->
                picking = false
                if (chat == null) actions.updateDraft { it.copy(model = id) } else actions.change("model", id)
            })
        }
    }
    if (tuning) {
        ModalBottomSheet(onDismissRequest = { tuning = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            TuneContent(state, actions, agent)
        }
    }

    // A card the reply is waiting on, as a sheet over the chat. One at a time, in the order asked.
    live?.cards?.firstOrNull()?.let { card ->
        ModalBottomSheet(
            onDismissRequest = {},
            // Not dismissable by a swipe: the reply is stopped until the card is answered, and
            // a card swiped away would leave it waiting with nothing on screen to say why.
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden }),
        ) {
            CardSheet(card) { answer -> actions.answer(card, answer) }
        }
    }

    if (renaming) {
        var title by remember { mutableStateOf(chat?.title.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.rename_chat)) },
            text = { OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(enabled = title.isNotBlank(), onClick = {
                    actions.rename(title)
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
                    actions.edit(message, text)
                    editing = null
                }) { Text(stringResource(R.string.card_send)) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun MenuItem(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    DropdownMenuItem(leadingIcon = { Icon(icon, contentDescription = null) }, text = { Text(text) }, onClick = onClick)
}

/** To the very end of the transcript: the last row, then down to its foot however tall it is. */
private suspend fun LazyListState.toEnd() {
    val last = layoutInfo.totalItemsCount - 1
    if (last < 0) return
    scrollToItem(last)
    scrollBy(1_000_000f)
}

/** How far the list stands from its very end, in pixels; a last row not on screen is "far". */
private fun LazyListState.distanceToEnd(): Int {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return 0
    if (last.index < info.totalItemsCount - 1) return Int.MAX_VALUE
    return last.offset + last.size + info.afterContentPadding - info.viewportEndOffset
}

/**
 * A new chat's empty page: what kind of chat it is ([onKind], Chat or Agent, one line each), then
 * the model's picture and a greeting. A new agent chat says where it will run instead, and that
 * line opens the tune sheet where the device and folder are chosen -- or, with no computer
 * linked, how to link one: an agent chat runs on a linked LLeMbas CLI and nowhere else.
 */
@Composable
private fun Greeting(
    state: ChatState,
    agent: Boolean,
    onTune: () -> Unit,
    onKind: ((Boolean) -> Unit)?,
    onRecheck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    if (agent && state.devices.isEmpty() && !state.loading) {
        Column(modifier.fillMaxSize()) {
            if (onKind != null) KindChoice(agent, onKind, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            LinkComputer(state.instanceBase, onRecheck, Modifier.weight(1f))
        }
        return
    }
    // One scroll for the choice and the greeting, so a small phone at large type still reaches
    // both; on a tall one the greeting sits in the middle of what is left.
    BoxWithConstraints(modifier.fillMaxSize()) {
        val room = maxHeight
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (onKind != null) KindChoice(agent, onKind, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            Column(
                Modifier.fillMaxWidth().heightIn(min = room * 0.6f).padding(horizontal = 32.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (agent) {
                    Box(Modifier.size(64.dp).background(palette.accentSoft, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Terminal, contentDescription = null, tint = palette.accent, modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(stringResource(R.string.agent_greeting), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    val device = state.device
                    val where = when {
                        device == null -> stringResource(R.string.agent_no_device)

                        else -> stringResource(
                            R.string.agent_runs_on,
                            device.name.ifBlank { device.host },
                            state.draft.directory.ifBlank { stringResource(R.string.agent_directory_default) },
                        )
                    }
                    TextButton(onClick = onTune) { Text(where, textAlign = TextAlign.Center, color = palette.inkMuted) }
                } else {
                    ModelAvatar(state.modelInfo, state.modelWord ?: "?", size = 64.dp)
                    Spacer(Modifier.height(20.dp))
                    Text(stringResource(R.string.empty_chat), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/**
 * Chat or Agent, for a chat not made yet: two cards side by side, one line each, on a shared
 * track -- both as tall as the taller, whatever either says in which language.
 */
@Composable
fun KindChoice(agent: Boolean, onKind: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KindCard(Icons.Filled.ChatBubbleOutline, stringResource(R.string.kind_chat), stringResource(R.string.kind_chat_line), !agent, Modifier.weight(1f)) {
            onKind(false)
        }
        KindCard(Icons.Filled.Terminal, stringResource(R.string.kind_agent), stringResource(R.string.kind_agent_line), agent, Modifier.weight(1f)) {
            onKind(true)
        }
    }
}

@Composable
private fun KindCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    line: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    Surface(
        onClick = onClick,
        selected = selected,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) palette.accentSoft else palette.surfaceRaised,
        contentColor = palette.ink,
        border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, if (selected) palette.accent else palette.border),
        modifier = modifier.fillMaxHeight().heightIn(min = 48.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = if (selected) palette.accent else palette.inkMuted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
            }
            Spacer(Modifier.height(4.dp))
            Text(line, style = MaterialTheme.typography.bodySmall, color = palette.inkMuted)
        }
    }
}

/**
 * A new agent chat with no computer linked: what an agent chat needs and the three steps to
 * link one, each command with Copy -- the web page's own steps -- and Check again.
 */
@Composable
private fun LinkComputer(base: String, onRecheck: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp)) {
        Box(Modifier.size(56.dp).background(palette.accentSoft, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Computer, contentDescription = null, tint = palette.accent, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.agent_link_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.agent_link_lede), style = MaterialTheme.typography.bodyMedium, color = palette.inkMuted)
        val steps = listOf(
            stringResource(R.string.agent_link_install) to "curl -fsSL $CLI_INSTALL | bash",
            stringResource(R.string.agent_link_login) to "lembas login ${base.ifBlank { "https://…" }}",
            stringResource(R.string.agent_link_service) to "remote:\n  enabled: true\n  roots: [ ~/projects ]\n\nlembas service install",
        )
        steps.forEachIndexed { i, (words, code) ->
            Spacer(Modifier.height(16.dp))
            Row {
                Text("${i + 1}.", style = MaterialTheme.typography.bodyMedium, color = palette.accent, modifier = Modifier.width(24.dp))
                Text(words, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            CodeBlock("sh", code)
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.agent_link_dials_out), style = MaterialTheme.typography.bodySmall, color = palette.inkFaint)
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onRecheck, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.agent_link_check)) }
    }
}

/** Where the LLeMbas CLI's installer is: the public release, as the web page gives it. */
private const val CLI_INSTALL = "https://github.com/LLeMbas/LLeMbas-CLI/releases/latest/download/get.sh"

/** The empty chat's suggestions: a sideways row of cards, all one height (the tallest's). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Suggestions(onPick: (String) -> Unit) {
    val palette = LocalPalette.current
    val all = listOf(
        R.string.suggest_explain to R.string.suggest_explain_line,
        R.string.suggest_write to R.string.suggest_write_line,
        R.string.suggest_plan to R.string.suggest_plan_line,
        R.string.suggest_check to R.string.suggest_check_line,
    )
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Max).horizontalScroll(rememberScrollState()).padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        all.forEach { (title, line) ->
            val t = stringResource(title)
            val l = stringResource(line)
            Surface(
                onClick = { onPick("$t $l") },
                shape = RoundedCornerShape(16.dp),
                color = palette.surfaceRaised,
                contentColor = palette.ink,
                border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
                modifier = Modifier.width(184.dp).fillMaxHeight(),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(t, style = MaterialTheme.typography.titleSmall)
                    Text(l, style = MaterialTheme.typography.bodySmall, color = palette.inkMuted)
                }
            }
        }
    }
}
