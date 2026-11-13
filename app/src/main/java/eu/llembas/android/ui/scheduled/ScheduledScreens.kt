@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package eu.llembas.android.ui.scheduled

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.clip
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
import eu.llembas.android.api.LembasJson
import eu.llembas.android.api.RunsOn
import eu.llembas.android.api.Schedule
import eu.llembas.android.api.ScheduleCompiled
import eu.llembas.android.api.ScheduleRun
import eu.llembas.android.api.SchedulesFrame
import eu.llembas.android.api.StreamClient
import eu.llembas.android.api.computerAway
import eu.llembas.android.api.notifyChoice
import eu.llembas.android.lembas
import eu.llembas.android.ui.explain
import eu.llembas.android.ui.progressLineOf
import eu.llembas.android.ui.theme.LocalPalette
import eu.llembas.android.ui.theme.Palette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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

    private var live: Job? = null

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                _state.value = ScheduledState(schedules = getApplication<Application>().api().schedules())
                watch()
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

    /**
     * The person's events route while the list is on screen: the whole list again whenever it
     * changes (a run starting, waiting or finishing, a schedule made or paused elsewhere).
     * Without it the list is what the last read said, and a pull reads it again.
     */
    private fun watch() {
        if (live?.isActive == true) return
        live = viewModelScope.launch {
            val client = runCatching { getApplication<Application>().api() }.getOrNull() ?: return@launch
            try {
                StreamClient(client).watch { client.events() }.collect { frame ->
                    if (frame.event != "schedules") return@collect
                    val read = runCatching { LembasJson.decodeFromString(SchedulesFrame.serializer(), frame.data) }.getOrNull() ?: return@collect
                    _state.update { it.copy(schedules = read.schedules) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // An older server, or the stream refused: the list stays as last read.
            }
        }
    }

    fun stop() {
        live?.cancel()
        live = null
    }

    /** Pause or resume; the switch moves at once and comes back if the server says no. */
    fun pause(schedule: Schedule, paused: Boolean) {
        _state.update { s -> s.copy(schedules = s.schedules.map { if (it.id == schedule.id) it.copy(enabled = !paused) else it }) }
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
fun ScheduledScreen(onBack: () -> Unit, onOpen: (String) -> Unit, onNew: () -> Unit, onModels: () -> Unit, model: ScheduledViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        model.load()
        onPauseOrDispose { model.stop() }
    }
    ScheduledContent(state, onBack = onBack, onOpen = onOpen, onNew = onNew, onModels = onModels, onPause = model::pause, onRetry = model::load)
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
    onModels: () -> Unit = {},
    onPause: (Schedule, Boolean) -> Unit = { _, _ -> },
    onRetry: () -> Unit = {},
) {
    val palette = LocalPalette.current
    Scaffold(
        containerColor = palette.bg,
        topBar = {
            BackBar(stringResource(R.string.scheduled), onBack) {
                if (!state.unsupported) {
                    IconButton(onClick = onModels) { Icon(Icons.Filled.Tune, contentDescription = stringResource(R.string.schedule_models)) }
                }
            }
        },
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
                schedule.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.inkMuted)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    val shown = if (schedule.running) "running" else schedule.lastRun?.status
                    shown?.let { StatusChip(it, schedule.lastRun?.missed ?: 0, schedule.lastRun?.waitingFor) }
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
                            modifier = Modifier.padding(start = if (shown != null) 8.dp else 0.dp),
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
fun StatusChip(status: String, missed: Int = 0, waitingFor: String? = null) {
    val palette = LocalPalette.current
    val (ink, soft) = toneColours(palette, ScheduleLogic.tone(status))
    val words = when (status) {
        "ok" -> stringResource(R.string.run_ok)
        "silent" -> stringResource(R.string.run_silent)
        "unchanged" -> stringResource(R.string.run_unchanged)
        "failed" -> stringResource(R.string.run_failed)
        "missed" -> stringResource(R.string.run_missed, maxOf(missed, 1).toString())
        "skipped" -> stringResource(R.string.run_skipped)
        "waiting" -> stringResource(if (waitingFor == "device") R.string.run_waiting_computer else R.string.run_waiting)
        "running" -> stringResource(R.string.run_running)
        else -> status
    }
    Box(Modifier.background(soft, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(words, style = MaterialTheme.typography.labelMedium, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun toneColours(palette: Palette, tone: Tone): Pair<Color, Color> = when (tone) {
    Tone.Good -> palette.success to palette.successSoft
    Tone.Bad -> palette.danger to palette.dangerSoft
    Tone.Waiting -> palette.warning to palette.warningSoft
    Tone.Quiet, Tone.Neutral -> palette.inkMuted to palette.surfaceActive
}

@Composable
internal fun BackBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    val palette = LocalPalette.current
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.bg, scrolledContainerColor = palette.bg),
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
        actions = { actions() },
    )
}

@Composable
internal fun Message(text: String, retry: (() -> Unit)? = null) {
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
        val run = api.runScheduleNow(s.id)
        _state.update { st ->
            st.copy(runs = listOf(run) + st.runs.filter { it.id != run.id }, notice = getApplication<Application>().getString(R.string.schedule_started))
        }
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
    onOpenRun: (String) -> Unit,
    onOpenReport: (String) -> Unit = {},
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
        onOpenRun = { run -> onOpenRun(run.id) },
        onOpenReport = onOpenReport,
        onMore = model::more,
    )
}

/**
 * One schedule: what it does and when, Run now and Pause side by side (equal halves), the next
 * runs, and its history -- each run's status, when, the model that ran it and why, the first
 * lines of its result; a run opens its result in full.
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
    onOpenReport: (String) -> Unit = {},
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
            ScheduleBody(state, schedule, padding, onRunNow, onPause, onOpenRun, onOpenReport, onMore)
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
    onOpenReport: (String) -> Unit,
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
                        Text(schedule.description.orEmpty(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    }
                    Text(schedule.instruction, style = MaterialTheme.typography.bodyMedium)
                    val facts = listOfNotNull(
                        schedule.model?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.schedule_model, it) },
                        modeWords(schedule.mode),
                        notifyWords(schedule),
                    )
                    if (facts.isNotEmpty()) {
                        Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = palette.inkFaint)
                    }
                    schedule.runsOn?.let { on -> ComputerLine(on, away = schedule.lastRun?.computerAway == true) }
                    schedule.watch?.target?.takeIf { it.isNotBlank() }?.let { url ->
                        Text(
                            stringResource(if (schedule.watch?.kind == "command") R.string.schedule_watching_command else R.string.schedule_watching) + ": " +
                                url,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkFaint,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
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
        items(state.runs, key = { it.id }) { run -> RunRow(run, schedule.runsOn, onOpen = { onOpenRun(run) }, onOpenReport = onOpenReport) }
        if (state.more) item { LaunchedEffect(state.runs.size) { onMore() } }
    }
}

@Composable
private fun notifyWords(schedule: Schedule): String? = when (schedule.notify) {
    "always" -> stringResource(R.string.notify_always)

    // "When…" with the person's own words (spec 3.8.0); a server before it said "matters".
    "when" -> stringResource(
        R.string.notify_when_words,
        schedule.notifyWhen.ifBlank {
            schedule.notifyDefault.ifBlank { stringResource(R.string.notify_when_default) }
        },
    )

    "matters" -> stringResource(R.string.notify_matters)

    "never" -> stringResource(R.string.notify_never)

    else -> null
}

@Composable
private fun modeWords(mode: String): String? = when (mode) {
    "fresh" -> stringResource(R.string.mode_fresh)
    "continue" -> stringResource(R.string.mode_continue)
    else -> null
}

/**
 * Where a schedule runs: Computer: its name · the folder there · the mode, and "not linked now"
 * in the waiting colour when its last run found it away. The folder gives first.
 */
@Composable
private fun ComputerLine(on: RunsOn, away: Boolean) {
    val palette = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Computer, contentDescription = null, tint = palette.inkFaint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        val parts = listOfNotNull(
            stringResource(R.string.schedule_computer) + ": " + on.deviceName.ifBlank { on.device },
            on.cwd.takeIf { it.isNotBlank() },
            agentModeWords(on.mode),
        )
        Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = palette.inkFaint, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (away) {
        Text(
            on.deviceName.ifBlank { on.device } + ": " + stringResource(R.string.schedule_computer_away),
            style = MaterialTheme.typography.bodySmall,
            color = palette.warning,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A run row's line: the computer's name, and "not linked now" when the run found it away. */
@Composable
private fun ComputerName(on: RunsOn, away: Boolean) {
    val palette = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Computer, contentDescription = stringResource(R.string.schedule_computer), tint = palette.inkFaint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            on.deviceName.ifBlank {
                on.device
            },
            style = MaterialTheme.typography.bodySmall,
            color = palette.inkMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (away) {
            Text(
                " · " + stringResource(R.string.schedule_computer_away),
                style = MaterialTheme.typography.bodySmall,
                color = palette.warning,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun agentModeWords(mode: String): String? = when (mode) {
    "manual" -> stringResource(R.string.mode_manual)
    "edit" -> stringResource(R.string.mode_edit)
    "auto" -> stringResource(R.string.mode_auto)
    else -> null
}

@Composable
internal fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = LocalPalette.current.accent,
        modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 4.dp),
    )
}

/**
 * A run: its status (a fixed column, so the times line up), when, the model, and the first
 * lines of its result (model output: plain text, never markup) or why it failed or waits. The
 * whole row opens the run.
 */
@Composable
private fun RunRow(run: ScheduleRun, runsOn: RunsOn?, onOpen: () -> Unit, onOpenReport: (String) -> Unit = {}) {
    val palette = LocalPalette.current
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(16.dp),
        color = palette.surfaceRaised,
        contentColor = palette.ink,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.width(112.dp)) { StatusChip(run.status, run.missed, run.waitingFor) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(ScheduleLogic.moment(run.startedAt ?: run.dueAt) ?: "—", style = MaterialTheme.typography.bodyMedium)
                // Which computer the run was for, and whether it was away.
                runsOn?.let { on -> ComputerName(on, away = run.computerAway) }
                RunFacts(run, maxLines = 2)
                run.reportId?.let { report -> ReportLink(onClick = { onOpenReport(report) }) }
            }
        }
    }
}

/** A run that filed a report: the way to it, a tap target of its own inside the run's row. */
@Composable
internal fun ReportLink(onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).heightIn(min = 40.dp).padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Summarize, contentDescription = null, tint = palette.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.run_open_report), style = MaterialTheme.typography.labelLarge, color = palette.accent)
    }
}

/** Under a run's time: the model and why, what it is doing or the trouble, then its note (or the result's first lines). */
@Composable
internal fun RunFacts(run: ScheduleRun, maxLines: Int = Int.MAX_VALUE, withPreview: Boolean = true) {
    val palette = LocalPalette.current
    val name = run.model.label
    val from = run.model.switchedFrom
    val model = when {
        name == null -> null
        run.model.reason == "switched" && from != null -> stringResource(R.string.run_model_instead, name, from)
        else -> name
    }
    model?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = palette.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    // A run under way says what it is doing, in the words every client uses (spec 3.4.0).
    if (run.status == "waiting" || run.status == "running") {
        progressLineOf(run.progress)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = palette.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    if (run.status == "waiting") {
        ScheduleLogic.moment(run.retryAt)?.let {
            Text(stringResource(R.string.run_retry_at, it), style = MaterialTheme.typography.bodySmall, color = palette.warning, maxLines = 1)
        }
    }
    run.error?.text?.takeIf { it.isNotBlank() }?.let {
        val tone = if (run.status == "waiting") palette.inkMuted else palette.danger
        Text(it, style = MaterialTheme.typography.bodySmall, color = tone, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
    // The run's own line for the history (spec 3.8.0): what it found, or why it filed no report.
    // A model's words: plain text. A server before it sends the result's first lines instead.
    if (run.note.isNotBlank()) {
        Text(run.note, style = MaterialTheme.typography.bodySmall, color = palette.ink, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    } else if (withPreview && run.preview.isNotBlank()) {
        Text(run.preview, style = MaterialTheme.typography.bodySmall, color = palette.ink, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

// --- Create and edit ------------------------------------------------------------------------

data class EditState(
    val words: String = "",
    val compiled: ScheduleCompiled? = null,
    val compiling: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val unsupported: Boolean = false,
    /** Notify when (spec 3.8.0): `always`, `when` or `never`, and the person's words for `when` (empty: the default). */
    val notify: String = "when",
    val notifyWhen: String = "",
    /** Whether the instance has Reports (LLeMbas 2.0.0-beta.12): an older one is neither shown nor sent "notify when". */
    val reports: Boolean = false,
    /** The server's default condition in the account's language, when it said one. */
    val notifyDefault: String = "",
)

class ScheduleEditViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(EditState())
    val state = _state.asStateFlow()
    private var editing: String? = null
    private var started = false

    /** Begin: empty for a new schedule, the words a schedule was made from for one being edited. */
    fun start(id: String?) {
        if (started) return
        started = true
        editing = id
        // "Notify when" belongs to an instance that files reports; one that has no such route
        // would refuse the new values, so it is asked first.
        viewModelScope.launch {
            val has = runCatching { getApplication<Application>().api().reports(unread = true) }.isSuccess
            _state.update { it.copy(reports = has) }
        }
        if (id == null) return
        viewModelScope.launch {
            runCatching { getApplication<Application>().api().schedule(id) }.onSuccess { s ->
                _state.update {
                    it.copy(
                        words = s.request.ifBlank {
                            s.instruction
                        },
                        notify = notifyChoice(s.notify),
                        notifyWhen = s.notifyWhen,
                        notifyDefault = s.notifyDefault,
                    )
                }
            }
        }
    }

    fun words(text: String) = _state.update { it.copy(words = text, compiled = null) }

    fun notify(value: String) = _state.update { it.copy(notify = notifyChoice(value)) }

    fun notifyWhen(text: String) = _state.update { it.copy(notifyWhen = text) }

    /** The server reads the words: the title, the instruction, the rule and the next runs. */
    fun preview() {
        val words = _state.value.words.trim()
        if (words.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(compiling = true, error = null) }
            try {
                val compiled = getApplication<Application>().api().compileSchedule(words)
                _state.update { it.copy(compiled = compiled, compiling = false) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(compiling = false, unsupported = e.unsupported(), error = if (e.unsupported()) null else getApplication<Application>().explain(e))
                }
            }
        }
    }

    fun save(then: (String) -> Unit) {
        val compiled = _state.value.compiled?.takeIf { it.savable } ?: return
        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            try {
                val now = _state.value
                val saved = getApplication<Application>().api().saveSchedule(
                    compiled,
                    now.words.trim(),
                    editing,
                    notify = now.notify.takeIf { now.reports },
                    notifyWhen = now.notifyWhen,
                )
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
    ScheduleEditContent(
        state,
        editing = id != null,
        onBack = onBack,
        onWords = model::words,
        onPreview = model::preview,
        onSave = { model.save(onSaved) },
        onNotify = model::notify,
        onNotifyWhen = model::notifyWhen,
    )
}

/**
 * A schedule in words: the person says when and what, the server reads it, and the preview
 * shows what it understood -- the title, the rule in words, the instruction, the next five runs
 * -- before anything is saved. When it could not make out all of it, it says why, and Save
 * waits for words it can. Save stays at the foot, out of the way of the keyboard.
 */
@Composable
fun ScheduleEditContent(
    state: EditState,
    editing: Boolean,
    onBack: () -> Unit = {},
    onWords: (String) -> Unit = {},
    onPreview: () -> Unit = {},
    onSave: () -> Unit = {},
    onNotify: (String) -> Unit = {},
    onNotifyWhen: (String) -> Unit = {},
) {
    val palette = LocalPalette.current
    Scaffold(
        containerColor = palette.bg,
        topBar = { BackBar(stringResource(if (editing) R.string.schedule_edit else R.string.schedule_new), onBack) },
        bottomBar = {
            Box(Modifier.background(palette.bg).navigationBarsPadding().imePadding().padding(16.dp)) {
                Button(
                    onClick = onSave,
                    enabled = state.compiled?.savable == true && !state.saving,
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
            state.compiled?.let { Understood(it) }
            if (state.reports) NotifyWhen(state.notify, state.notifyWhen, state.notifyDefault, onNotify, onNotifyWhen)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Notify when: every run, when the person's own words hold, or never -- each with one line
 * saying what it does. A report is what tells the person; a run without one is a line in the
 * task's history. "When…" opens a field with the default already in it.
 */
@Composable
private fun NotifyWhen(notify: String, words: String, default: String, onNotify: (String) -> Unit, onWords: (String) -> Unit) {
    val palette = LocalPalette.current
    val choices = listOf(
        Triple("always", R.string.notify_every_run, R.string.notify_every_run_line),
        Triple("when", R.string.notify_when_option, R.string.notify_when_line),
        Triple("never", R.string.notify_never_option, R.string.notify_never_line),
    )
    Surface(shape = RoundedCornerShape(20.dp), color = palette.surfaceRaised, contentColor = palette.ink, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(
                stringResource(R.string.notify_when),
                style = MaterialTheme.typography.labelLarge,
                color = palette.accent,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            choices.forEach { (value, title, line) ->
                Row(
                    Modifier.fillMaxWidth().clickable { onNotify(value) }.heightIn(min = 56.dp).padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = notify == value, onClick = { onNotify(value) })
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(line), style = MaterialTheme.typography.bodySmall, color = palette.inkMuted)
                    }
                }
                if (value == "when" && notify == "when") {
                    val usual = default.ifBlank { stringResource(R.string.notify_when_default) }
                    OutlinedTextField(
                        value = words.ifEmpty { usual },
                        onValueChange = onWords,
                        label = { Text(stringResource(R.string.notify_when_label)) },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().padding(start = 52.dp, end = 16.dp, bottom = 8.dp),
                    )
                }
            }
        }
    }
}

/** What the server made of the words. The title and instruction are a model's: plain text. */
@Composable
private fun Understood(c: ScheduleCompiled) {
    val palette = LocalPalette.current
    Surface(shape = RoundedCornerShape(20.dp), color = palette.surfaceRaised, contentColor = palette.ink, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.schedule_understood), style = MaterialTheme.typography.labelLarge, color = palette.accent)
            c.reason?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.warning) }
            if (c.title.isNotBlank()) Text(c.title, style = MaterialTheme.typography.titleMedium)
            c.description?.takeIf { it.isNotBlank() }?.let { words ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Schedule, contentDescription = null, tint = palette.inkMuted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(words, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                }
            }
            if (c.instruction.isNotBlank()) Text(c.instruction, style = MaterialTheme.typography.bodyMedium, color = palette.inkMuted)
            val next = ScheduleLogic.upcoming(c.nextRuns)
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
