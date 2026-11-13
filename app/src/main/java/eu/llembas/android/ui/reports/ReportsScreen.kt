@file:OptIn(ExperimentalMaterial3Api::class)

package eu.llembas.android.ui.reports

import android.app.Application
import android.content.Intent
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.llembas.android.R
import eu.llembas.android.api.ApiClient
import eu.llembas.android.api.ApiException
import eu.llembas.android.api.Report
import eu.llembas.android.api.sortedReports
import eu.llembas.android.lembas
import eu.llembas.android.ui.chat.RichMarkdown
import eu.llembas.android.ui.explain
import eu.llembas.android.ui.pageProjection
import eu.llembas.android.ui.scheduled.BackBar
import eu.llembas.android.ui.scheduled.ScheduleLogic
import eu.llembas.android.ui.theme.LocalPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private suspend fun Application.api(): ApiClient = lembas.instances.client() ?: throw ApiException(401, "Not signed in.")

/** A server without the reports routes answers 404 (or 405): it is older, not broken. */
private fun Throwable.unsupported(): Boolean = this is ApiException && (status == 404 || status == 405)

// --- The list --------------------------------------------------------------------------------

data class ReportsState(
    val reports: List<Report> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** The instance is older than Reports: said plainly, instead of an error. */
    val unsupported: Boolean = false,
)

class ReportsViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(ReportsState())
    val state = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val reports = getApplication<Application>().api().reports()
                _state.value = ReportsState(reports = sortedReports(reports))
            } catch (e: Exception) {
                _state.update {
                    it.copy(loading = false, unsupported = e.unsupported(), error = if (e.unsupported()) null else getApplication<Application>().explain(e))
                }
            }
        }
    }

    /** Read: it stays in the list, among those read. A failure is put right by reading the list again. */
    fun markRead(report: Report) {
        _state.update { s -> s.copy(reports = sortedReports(s.reports.map { if (it.id == report.id) it.copy(unread = false) else it })) }
        viewModelScope.launch { runCatching { getApplication<Application>().api().readReport(report.id) }.onFailure { fail(it) } }
    }

    fun delete(report: Report) {
        _state.update { s -> s.copy(reports = s.reports.filterNot { it.id == report.id }) }
        viewModelScope.launch { runCatching { getApplication<Application>().api().deleteReport(report.id) }.onFailure { fail(it) } }
    }

    private fun fail(e: Throwable) {
        _state.update { it.copy(error = getApplication<Application>().explain(e)) }
        load()
    }
}

interface ReportsActions {
    fun open(report: Report) {}
    fun markRead(report: Report) {}
    fun delete(report: Report) {}
    fun reload() {}
}

@Composable
fun ReportsScreen(onBack: () -> Unit, onOpen: (String) -> Unit, model: ReportsViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    // Read again each time the page comes up: a report opened and read is no longer bold on the way back.
    LaunchedEffect(Unit) { model.load() }
    ReportsContent(
        state,
        object : ReportsActions {
            override fun open(report: Report) = onOpen(report.id)

            override fun markRead(report: Report) = model.markRead(report)

            override fun delete(report: Report) = model.delete(report)

            override fun reload() = model.load()
        },
        onBack,
    )
}

/**
 * Reports: what scheduled tasks had to tell. The unread first; each row its title, the task it
 * came from and when, with a mark where the model called it important. Pull to read the list
 * again; swipe a row one way to mark it read, the other to delete it (which asks once).
 */
@Composable
fun ReportsContent(state: ReportsState, actions: ReportsActions, onBack: () -> Unit = {}) {
    val palette = LocalPalette.current
    var deleting by remember { mutableStateOf<Report?>(null) }
    deleting?.let { report ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.report_delete_title)) },
            text = { Text(stringResource(R.string.report_delete_line, report.title.ifBlank { stringResource(R.string.report_untitled) })) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    actions.delete(report)
                }) { Text(stringResource(R.string.delete), color = palette.danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    Scaffold(containerColor = palette.bg, topBar = { BackBar(stringResource(R.string.reports), onBack) }) { padding ->
        PullToRefreshBox(isRefreshing = state.loading, onRefresh = actions::reload, modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.error?.let { error ->
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(error, color = palette.danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = actions::reload) { Text(stringResource(R.string.retry)) }
                        }
                    }
                }
                if (state.reports.isEmpty() && !state.loading && state.error == null) {
                    item {
                        Text(
                            stringResource(if (state.unsupported) R.string.reports_unsupported else R.string.reports_none),
                            color = palette.inkMuted,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                }
                items(state.reports, key = { it.id }) { report ->
                    ReportRow(report, onOpen = { actions.open(report) }, onRead = { actions.markRead(report) }, onDelete = { deleting = report })
                }
            }
        }
    }
}

/**
 * One report in the list. Swiping never removes the row by itself: towards the end marks it read,
 * towards the start asks whether to delete it, and either way the row comes back to rest.
 */
@Composable
private fun ReportRow(report: Report, onOpen: () -> Unit, onRead: () -> Unit, onDelete: () -> Unit) {
    val palette = LocalPalette.current
    val swipe = rememberSwipeToDismissBoxState(
        confirmValueChange = { to ->
            when (to) {
                SwipeToDismissBoxValue.StartToEnd -> if (report.unread) onRead()
                SwipeToDismissBoxValue.EndToStart -> onDelete()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false
        },
    )
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = report.unread,
        backgroundContent = {
            val deletes = swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Row(
                Modifier.fillMaxSize().background(
                    if (deletes) palette.dangerSoft else palette.surfaceActive,
                    RoundedCornerShape(16.dp),
                ).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (deletes) Arrangement.End else Arrangement.Start,
            ) {
                Icon(
                    if (deletes) Icons.Filled.Delete else Icons.Filled.DoneAll,
                    contentDescription = stringResource(if (deletes) R.string.delete else R.string.report_mark_read),
                    tint = if (deletes) palette.danger else palette.inkMuted,
                )
            }
        },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(palette.surfaceRaised, RoundedCornerShape(16.dp))
                .clickable(onClick = onOpen)
                .heightIn(min = 72.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // The unread dot's place is kept for a read row too, so the titles start on one line.
            Box(Modifier.padding(top = 6.dp).size(10.dp).background(if (report.unread) palette.accent else palette.surfaceRaised, CircleShape))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    report.title.ifBlank { stringResource(R.string.report_untitled) },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (report.unread) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                ReportFacts(report)
                if (report.summary.isNotBlank()) {
                    Text(report.summary, style = MaterialTheme.typography.bodySmall, color = palette.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (report.high) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Filled.PriorityHigh,
                    contentDescription = stringResource(R.string.report_important),
                    tint = palette.warning,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** Under a report's title: the task it came from, and when. */
@Composable
private fun ReportFacts(report: Report) {
    val palette = LocalPalette.current
    val facts = listOfNotNull(report.schedule?.title?.ifBlank { null }, ScheduleLogic.moment(report.created)).joinToString(" · ")
    if (facts.isNotBlank()) Text(facts, style = MaterialTheme.typography.bodySmall, color = palette.inkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

// --- One report ------------------------------------------------------------------------------

data class ReportState(val report: Report? = null, val loading: Boolean = true, val error: String? = null, val replying: Boolean = false)

class ReportViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(ReportState())
    val state = _state.asStateFlow()

    /** Read the report, and mark it read: opening it is reading it. */
    fun load(id: String) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val api = getApplication<Application>().api()
                val report = api.report(id)
                _state.value = ReportState(report = report.copy(unread = false), loading = false)
                if (report.unread) runCatching { api.readReport(id) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = getApplication<Application>().explain(e)) }
            }
        }
    }

    /** Reply: the server continues the report as a conversation and says which chat it is. */
    fun reply(then: (chat: String, title: String) -> Unit) {
        val report = _state.value.report ?: return
        // A reply that was begun before goes on in its chat: nothing is asked of the server.
        report.chatId?.let { return then(it, report.title) }
        viewModelScope.launch {
            _state.update { it.copy(replying = true, error = null) }
            try {
                val chat = getApplication<Application>().api().replyToReport(report.id) ?: throw ApiException(502, "")
                _state.update { it.copy(replying = false) }
                then(chat, report.title)
            } catch (e: Exception) {
                _state.update { it.copy(replying = false, error = getApplication<Application>().explain(e)) }
            }
        }
    }
}

@Composable
fun ReportScreen(id: String, onBack: () -> Unit, onReply: (chat: String, title: String) -> Unit, model: ReportViewModel = viewModel(key = "report-$id")) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(id) { model.load(id) }
    ReportContent(
        state,
        onBack = onBack,
        onRetry = { model.load(id) },
        onReply = { model.reply(onReply) },
        onShare = { report ->
            val send = Intent(
                Intent.ACTION_SEND,
            ).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, report.title).putExtra(Intent.EXTRA_TEXT, shareText(report))
            runCatching { context.startActivity(Intent.createChooser(send, null)) }
        },
    )
}

/** What Share hands over: the title, then the report as text -- a page in it as its one line, never its source. */
fun shareText(report: Report): String {
    // The server's own text of the body where it sends one; else the same made here.
    val text = report.text?.trim()?.ifBlank { null } ?: pageProjection(report.body.orEmpty(), untitled = "Page")
    return listOf(report.title, text).filter { it.isNotBlank() }.joinToString("\n\n")
}

/**
 * A report, read as a page: its title, where it came from and when, then the body -- Markdown a
 * model wrote, drawn as the chat draws a reply (untrusted: no images, no HTML; a page in it is a
 * card, not a page). Reply stays at the foot; Share is in the bar.
 */
@Composable
fun ReportContent(state: ReportState, onBack: () -> Unit = {}, onRetry: () -> Unit = {}, onReply: () -> Unit = {}, onShare: (Report) -> Unit = {}) {
    val palette = LocalPalette.current
    val report = state.report
    Scaffold(
        containerColor = palette.bg,
        topBar = {
            BackBar(stringResource(R.string.report), onBack) {
                if (report !=
                    null
                ) {
                    IconButton(onClick = { onShare(report) }) { Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.report_share)) }
                }
            }
        },
        bottomBar = {
            if (report != null) {
                Box(Modifier.background(palette.bg).navigationBarsPadding().padding(16.dp)) {
                    Button(onClick = onReply, enabled = !state.replying, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        if (state.replying) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        } else {
                            Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.report_reply))
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let { error ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(error, color = palette.danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (report == null) TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                }
            }
            if (report == null) {
                if (state.loading) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            } else {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        report.title.ifBlank { stringResource(R.string.report_untitled) },
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (report.high) {
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Filled.PriorityHigh, contentDescription = stringResource(R.string.report_important), tint = palette.warning)
                    }
                }
                ReportFacts(report)
                RichMarkdown(report.body.orEmpty().ifBlank { report.summary })
            }
        }
    }
}
