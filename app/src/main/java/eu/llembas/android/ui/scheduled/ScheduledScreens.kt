@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package eu.llembas.android.ui.scheduled

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.llembas.android.R
import eu.llembas.android.api.ApiClient
import eu.llembas.android.api.ApiException
import eu.llembas.android.api.Schedule
import eu.llembas.android.api.SchedulePreview
import eu.llembas.android.api.ScheduleRun
import eu.llembas.android.lembas
import eu.llembas.android.ui.explain
import eu.llembas.android.ui.theme.LocalPalette
import eu.llembas.android.ui.theme.Palette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A server without the scheduling routes answers 404 (or 405): it is older, not broken. */
private fun Throwable.unsupported(): Boolean = this is ApiException && (status == 404 || status == 405)

private suspend fun Application.api(): ApiClient = lembas.instances.client() ?: throw ApiException(401, "Not signed in.")

// --- The list --------------------------------------------------------------------------------

data class ScheduledState(
    val schedules: List<Schedule> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val unsupported: Boolean = false,
)

class ScheduledViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(ScheduledState())
    val state = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                _state.value = ScheduledState(schedules = getApplication<Application>().api().schedules())
            } catch (e: Exception) {
                _state.update {
                    if (e.unsupported()) {
                        it.copy(
                            loading = false,
                            unsupported = true,
                        )
                    } else {
                        it.copy(loading = false, error = getApplication<Application>().explain(e))
                    }
                }
            }
        }
    }

    /** Pause or resume; the switch moves at once and comes back if the server says no. */
    fun pause(schedule: Schedule, paused: Boolean) {
        _state.update { s -> s.copy(schedules = s.schedules.map { if (it.id == schedule.id) it.copy(paused = paused) else it }) }
        viewModelScope.launch {
            try {
                val saved = getApplication<Application>().api().pauseSchedule(schedule.id, paused)
                _state.update { s -> s.copy(schedules = s.schedules.map { if (it.id == saved.id) saved else it }) }
            } catch (e: Exception) {
                _state.update { s ->
                    s.copy(
                        schedules = s.schedules.map { if (it.id == schedule.id) schedule else it },
                        error = getApplication<Application>().explain(e),
                    )
                }
            }
        }
    }
}

@Composable
fun ScheduledScreen(onBack: () -> Unit, onOpen: (String) -> Unit, onNew: () -> Unit, model: ScheduledViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        model.load()
        onPauseOrDispose { }
    }
    ScheduledContent(state, onBack = onBack, onOpen = onOpen, onNew = onNew, onPause = model::pause, onRetry = model::load)
}

/**
 * Scheduled tasks: one card each, with the rule in words, the last run's status and the next
 * run, and a switch that pauses it. The card's words give; the switch keeps its size.
 */
@Composable
fun ScheduledContent(
    state: ScheduledState,
    onBack: () -> Unit = {},
    onOpen: (String) -> Unit = {},
    onNew: () -> Unit = {},
    onPause: (Schedule, Boolean) -> Unit = { _, _ -> },
    onRetry: () -> Unit = {},
) {
    val palette = LocalPalette.current
    Scaffold(
        containerColor = palette.bg,
        topBar = { BackBar(stringResource(R.string.scheduled), onBack) },
        floatingActionButton = {
            if (!state.unsupported) {
                ExtendedFloatingActionButton(
                    onClick = onNew,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.schedule_new)) },
                    containerColor = palette.accent,
                    contentColor = palette.accentInk,
                )
            }
        },
    ) { padding ->
        PullToRefreshBox(isRefreshing = state.loading, onRefresh = onRetry, modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.unsupported -> Message(stringResource(R.string.scheduled_unsupported))

                state.schedules.isEmpty() && state.error != null -> Message(state.error, retry = onRetry)

                state.schedules.isEmpty() && !state.loading -> Message(stringResource(R.string.scheduled_empty))

                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    state.error?.let { item { Text(it, color = palette.danger, style = MaterialTheme.typography.bodyMedium) } }
                    items(state.schedules, key = { it.id }) { s -> ScheduleCard(s, onOpen = { onOpen(s.id) }, onPause = { onPause(s, it) }) }
                }
            }
        }
    }
}

@Composable
private fun ScheduleCard(schedule: Schedule, onOpen: () -> Unit, onPause: (Boolean) -> Unit) {
    val palette = LocalPalette.current
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(20.dp),
        color = palette.surfaceRaised,
        contentColor = palette.ink,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    schedule.title.ifBlank { schedule.instruction },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (schedule.rule.isNotBlank()) {
                    Text(schedule.rule, style = MaterialTheme.typography.bodyMedium, color = palette.inkMuted)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    schedule.lastRun?.let { StatusChip(it.status, it.missed) }
                    val next = when {
                        schedule.paused -> stringResource(R.string.schedule_paused)
                        else -> ScheduleLogic.moment(schedule.nextRun)?.let { stringResource(R.string.schedule_next, it) }
                    }
                    next?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = if (schedule.lastRun != null) 8.dp else 0.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = !schedule.paused, onCheckedChange = { on -> onPause(!on) })
        }
    }
}

/** A run's status in words, on its tone's soft colour. */
@Composable
fun StatusChip(status: String, missed: Int = 0) {
    val palette = LocalPalette.current
    val (ink, soft) = toneColours(palette, ScheduleLogic.tone(status))
    val words = when (status) {
        "ok" -> stringResource(R.string.run_ok)
        "silent" -> stringResource(R.string.run_silent)
        "failed" -> stringResource(R.string.run_failed)
        "missed" -> stringResource(R.string.run_missed, maxOf(missed, 1).toString())
        "skipped" -> stringResource(R.string.run_skipped)
        "waiting" -> stringResource(R.string.run_waiting)
        "running" -> stringResource(R.string.run_running)
        else -> status
    }
    Box(Modifier.background(soft, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(words, style = MaterialTheme.typography.labelMedium, color = ink, maxLines = 1)
    }
}

private fun toneColours(palette: Palette, tone: Tone): Pair<Color, Color> = when (tone) {
    Tone.Good -> palette.success to palette.successSoft
    Tone.Bad -> palette.danger to palette.dangerSoft
    Tone.Waiting -> palette.warning to palette.warningSoft
    Tone.Quiet, Tone.Neutral -> palette.inkMuted to palette.surfaceActive
}

@Composable
private fun BackBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    val palette = LocalPalette.current
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.bg, scrolledContainerColor = palette.bg),
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
        actions = { actions() },
    )
}

@Composable
private fun Message(text: String, retry: (() -> Unit)? = null) {
    val palette = LocalPalette.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Schedule, contentDescription = null, tint = palette.inkFaint, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = palette.inkMuted, textAlign = TextAlign.Center)
        retry?.let { TextButton(onClick = it) { Text(stringResource(R.string.retry)) } }
    }
}

// --- One schedule -----------------------------------------------------------------------------

data class ScheduleState(
    val schedule: Schedule? = null,
    val runs: List<ScheduleRun> = emptyList(),
    val more: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

class ScheduleViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(ScheduleState())
    val state = _state.asStateFlow()
    private var id: String? = null

    fun open(id: String) {
        this.id = id
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val api = getApplication<Application>().api()
                val schedule = api.schedule(id)
                val runs = runCatching { api.scheduleRuns(id) }.getOrNull()
                _state.update { it.copy(schedule = schedule, runs = runs?.runs.orEmpty(), more = runs?.more == true, loading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = getApplication<Application>().explain(e)) }
            }
        }
    }

    fun more() {
        val schedule = _state.value.schedule ?: return
        val last = _state.value.runs.lastOrNull() ?: return
        viewModelScope.launch {
            runCatching { getApplication<Application>().api().scheduleRuns(schedule.id, last.id) }
                .onSuccess { page -> _state.update { it.copy(runs = it.runs + page.runs, more = page.more) } }
        }
    }

    fun runNow() = act { api, s ->
        api.runScheduleNow(s.id)
        _state.update { it.copy(notice = getApplication<Application>().getString(R.string.schedule_started)) }
        id?.let(::open)
    }

    fun pause(paused: Boolean) = act { api, s -> _state.update { it.copy(schedule = api.pauseSchedule(s.id, paused)) } }

    fun delete(then: () -> Unit) = act { api, s ->
        api.deleteSchedule(s.id)
        then()
    }

    fun clear() = _state.update { it.copy(error = null, notice = null) }

    private fun act(block: suspend (ApiClient, Schedule) -> Unit) {
        val schedule = _state.value.schedule ?: return
        viewModelScope.launch {
            try {
                block(getApplication<Application>().api(), schedule)
            } catch (e: Exception) {
                _state.update { it.copy(error = getApplication<Application>().explain(e)) }
            }
        }
    }
}

@Composable
fun ScheduleScreen(
    id: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onOpenChat: (String) -> Unit,
    model: ScheduleViewModel = viewModel(key = "schedule-$id"),
) {
    val state by model.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LifecycleResumeEffect(id) {
        model.open(id)
        onPauseOrDispose { }
    }
    LaunchedEffect(state.error, state.notice) {
        (state.notice ?: state.error.takeIf { state.schedule != null })?.let {
            snackbar.showSnackbar(it)
            model.clear()
        }
    }
    ScheduleContent(
        state,
        snackbar = snackbar,
        onBack = onBack,
        onEdit = { onEdit(id) },
        onDelete = { model.delete(onBack) },
        onRunNow = model::runNow,
        onPause = model::pause,
        onOpenRun = { run -> run.chatId?.let(onOpenChat) },
        onMore = model::more,
    )
}

/**
 * One schedule: what it does and when, Run now and Pause side by side (equal halves), the next
 * runs, and its history -- each run's status, when, the model that ran it and why, and Open for
 * its result.
 */
@Composable
fun ScheduleContent(
    state: ScheduleState,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    onBack: () -> Unit = {},
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
    onRunNow: () -> Unit = {},
    onPause: (Boolean) -> Unit = {},
    onOpenRun: (ScheduleRun) -> Unit = {},
    onMore: () -> Unit = {},
) {
    val palette = LocalPalette.current
    val schedule = state.schedule
    var deleting by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = palette.bg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            BackBar(schedule?.title?.ifBlank { null } ?: stringResource(R.string.scheduled), onBack) {
                if (schedule != null) {
                    IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.edit)) }
                    IconButton(onClick = { deleting = true }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete)) }
                }
            }
        },
    ) { padding ->
        if (schedule == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (state.loading) CircularProgressIndicator() else state.error?.let { Message(it) }
            }
        } else {
            ScheduleBody(state, schedule, padding, onRunNow, onPause, onOpenRun, onMore)
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.schedule_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    onDelete()
                }) { Text(stringResource(R.string.delete), color = palette.danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ScheduleBody(
    state: ScheduleState,
    schedule: Schedule,
    padding: PaddingValues,
    onRunNow: () -> Unit,
    onPause: (Boolean) -> Unit,
    onOpenRun: (ScheduleRun) -> Unit,
    onMore: () -> Unit,
) {
    val palette = LocalPalette.current
    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = palette.surfaceRaised, contentColor = palette.ink, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Schedule, contentDescription = null, tint = palette.accent, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(schedule.rule, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    }
                    Text(schedule.instruction, style = MaterialTheme.typography.bodyMedium)
                    val facts = listOfNotNull(
                        schedule.model.ifBlank { null }?.let { stringResource(R.string.schedule_model, it) },
                        notifyWords(schedule.notify),
                    )
                    if (facts.isNotEmpty()) {
                        Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = palette.inkFaint)
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = onRunNow, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.schedule_run_now), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = { onPause(!schedule.paused) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(if (schedule.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(if (schedule.paused) R.string.schedule_resume else R.string.schedule_pause),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        val next = ScheduleLogic.upcoming(schedule.nextRuns)
        if (next.isNotEmpty() && !schedule.paused) {
            item { Header(stringResource(R.string.schedule_next_runs)) }
            item {
                Surface(shape = RoundedCornerShape(16.dp), color = palette.surfaceRaised, contentColor = palette.ink, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        next.forEach {
                            Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                        }
                    }
                }
            }
        }
        item { Header(stringResource(R.string.schedule_history)) }
        if (state.runs.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.schedule_no_runs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.inkMuted,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        items(state.runs, key = { it.id }) { run -> RunRow(run, onOpen = { onOpenRun(run) }) }
        if (state.more) item { LaunchedEffect(state.runs.size) { onMore() } }
    }
}

@Composable
private fun notifyWords(notify: String): String? = when (notify) {
    "always" -> stringResource(R.string.notify_always)
    "matters", "when_matters" -> stringResource(R.string.notify_matters)
    "never" -> stringResource(R.string.notify_never)
    else -> null
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = LocalPalette.current.accent,
        modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** A run: its status (a fixed column, so the times line up), when, the model, and Open. */
@Composable
private fun RunRow(run: ScheduleRun, onOpen: () -> Unit) {
    val palette = LocalPalette.current
    Surface(shape = RoundedCornerShape(16.dp), color = palette.surfaceRaised, contentColor = palette.ink, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = 64.dp).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(112.dp)) { StatusChip(run.status, run.missed) }
            Column(Modifier.weight(1f)) {
                Text(ScheduleLogic.moment(run.startedAt ?: run.dueAt) ?: "—", style = MaterialTheme.typography.bodyMedium)
                val model = when {
                    run.model.isBlank() -> null
                    run.modelReason.startsWith("switched") -> stringResource(R.string.run_model_switched, run.model)
                    else -> run.model
                }
                model?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = palette.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (run.error.isNotBlank()) {
                    Text(run.error, style = MaterialTheme.typography.bodySmall, color = palette.danger, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (run.chatId != null) TextButton(onClick = onOpen) { Text(stringResource(R.string.viewer_open)) }
        }
    }
}

// --- Create and edit ------------------------------------------------------------------------

data class EditState(
    val words: String = "",
    val preview: SchedulePreview? = null,
    val compiling: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val unsupported: Boolean = false,
)

class ScheduleEditViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(EditState())
    val state = _state.asStateFlow()
    private var editing: String? = null
    private var started = false

    /** Begin: empty for a new schedule, the schedule's own words for one being edited. */
    fun start(id: String?) {
        if (started) return
        started = true
        editing = id
        if (id == null) return
        viewModelScope.launch {
            runCatching { getApplication<Application>().api().schedule(id) }.onSuccess { s ->
                _state.update { it.copy(words = listOf(s.rule, s.instruction).filter { w -> w.isNotBlank() }.joinToString(": ")) }
            }
        }
    }

    fun words(text: String) = _state.update { it.copy(words = text, preview = null) }

    /** The server reads the words: the rule, the instruction and the next runs come back. */
    fun preview() {
        val words = _state.value.words.trim()
        if (words.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(compiling = true, error = null) }
            try {
                val preview = getApplication<Application>().api().compileSchedule(words)
                _state.update { it.copy(preview = preview, compiling = false) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(compiling = false, unsupported = e.unsupported(), error = if (e.unsupported()) null else getApplication<Application>().explain(e))
                }
            }
        }
    }

    fun save(then: (String) -> Unit) {
        val preview = _state.value.preview ?: return
        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            try {
                val saved = getApplication<Application>().api().saveSchedule(preview, editing)
                then(saved.id)
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = getApplication<Application>().explain(e)) }
            }
        }
    }
}

@Composable
fun ScheduleEditScreen(
    id: String?,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    model: ScheduleEditViewModel = viewModel(key = "schedule-edit-${id ?: "new"}"),
) {
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(id) { model.start(id) }
    ScheduleEditContent(state, editing = id != null, onBack = onBack, onWords = model::words, onPreview = model::preview, onSave = { model.save(onSaved) })
}

/**
 * A schedule in words: the person says when and what, the server reads it, and the preview
 * shows what it understood -- the rule in words, the instruction, the next five runs -- before
 * anything is saved. Save stays at the foot, out of the way of the keyboard.
 */
@Composable
fun ScheduleEditContent(
    state: EditState,
    editing: Boolean,
    onBack: () -> Unit = {},
    onWords: (String) -> Unit = {},
    onPreview: () -> Unit = {},
    onSave: () -> Unit = {},
) {
    val palette = LocalPalette.current
    Scaffold(
        containerColor = palette.bg,
        topBar = { BackBar(stringResource(if (editing) R.string.schedule_edit else R.string.schedule_new), onBack) },
        bottomBar = {
            Box(Modifier.background(palette.bg).navigationBarsPadding().imePadding().padding(16.dp)) {
                Button(
                    onClick = onSave,
                    enabled = state.preview != null && !state.saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) { Text(stringResource(R.string.save)) }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.schedule_words_hint), style = MaterialTheme.typography.bodyMedium, color = palette.inkMuted)
            OutlinedTextField(
                value = state.words,
                onValueChange = onWords,
                minLines = 3,
                placeholder = { Text(stringResource(R.string.schedule_words_example)) },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            FilledTonalButton(
                onClick = onPreview,
                enabled = state.words.isNotBlank() && !state.compiling,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                if (state.compiling) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.schedule_preview))
            }
            if (state.unsupported) Text(stringResource(R.string.scheduled_unsupported), color = palette.inkMuted)
            state.error?.let { Text(it, color = palette.danger, style = MaterialTheme.typography.bodyMedium) }
            state.preview?.let { p ->
                Surface(shape = RoundedCornerShape(20.dp), color = palette.surfaceRaised, contentColor = palette.ink, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.schedule_understood), style = MaterialTheme.typography.labelLarge, color = palette.accent)
                        if (p.title.isNotBlank()) Text(p.title, style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Schedule, contentDescription = null, tint = palette.inkMuted, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(p.ruleText, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        }
                        Text(p.instruction, style = MaterialTheme.typography.bodyMedium, color = palette.inkMuted)
                        val next = ScheduleLogic.upcoming(p.nextRuns)
                        if (next.isNotEmpty()) {
                            Text(
                                stringResource(R.string.schedule_next_runs),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            next.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
