package eu.llembas.android.ui.scheduled

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.llembas.android.R
import eu.llembas.android.api.ApiException
import eu.llembas.android.api.RunResult
import eu.llembas.android.lembas
import eu.llembas.android.ui.chat.LocalCopy
import eu.llembas.android.ui.chat.RichMarkdown
import eu.llembas.android.ui.explain
import eu.llembas.android.ui.theme.LocalPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RunState(val title: String = "", val result: RunResult? = null, val loading: Boolean = false, val error: String? = null)

class RunViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(RunState())
    val state = _state.asStateFlow()

    /** The run and its result; the schedule's title too, for the bar (without it, "Scheduled"). */
    fun open(schedule: String, run: String) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val api = getApplication<Application>().lembas.instances.client() ?: throw ApiException(401, "Not signed in.")
                val result = api.scheduleRun(schedule, run)
                val title = runCatching { api.schedule(schedule).title }.getOrDefault("")
                _state.update { it.copy(result = result, title = title, loading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = getApplication<Application>().explain(e)) }
            }
        }
    }
}

@Composable
fun RunScreen(
    scheduleId: String,
    runId: String,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    model: RunViewModel = viewModel(key = "run-$scheduleId-$runId"),
) {
    val state by model.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(runId) {
        model.open(scheduleId, runId)
        onPauseOrDispose { }
    }
    RunContent(state, onBack = onBack, onOpenChat = onOpenChat)
}

/**
 * One run, where a notification's tap lands: its status and when, the model and why, and the
 * result in full -- Markdown a model wrote, drawn by the app's Markwon (no images, no HTML,
 * links only outside) -- or why there is none. Open its chat goes on from there.
 */
@Composable
fun RunContent(state: RunState, onBack: () -> Unit = {}, onOpenChat: (String) -> Unit = {}) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val copy: (String) -> Unit = remember(context) {
        { text -> context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("LLeMbas", text)) }
    }
    val result = state.result
    CompositionLocalProvider(LocalCopy provides copy) {
        Scaffold(
            containerColor = palette.bg,
            topBar = { BackBar(state.title.ifBlank { stringResource(R.string.scheduled) }, onBack) },
        ) { padding ->
            if (result == null) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    if (state.loading) CircularProgressIndicator() else state.error?.let { Message(it) }
                }
                return@Scaffold
            }
            val run = result.run
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(shape = RoundedCornerShape(20.dp), color = palette.surfaceRaised, contentColor = palette.ink, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusChip(run.status, run.missed, run.waitingFor)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                ScheduleLogic.moment(run.finishedAt ?: run.startedAt ?: run.dueAt) ?: "—",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        RunFacts(run, withPreview = false)
                    }
                }
                Header(stringResource(R.string.run_result))
                val text = result.text
                if (text.isNullOrBlank()) {
                    Text(stringResource(R.string.run_no_text), style = MaterialTheme.typography.bodyMedium, color = palette.inkMuted)
                } else {
                    RichMarkdown(text)
                }
                run.chatId?.let { chat ->
                    FilledTonalButton(onClick = { onOpenChat(chat) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 8.dp)) {
                        Text(stringResource(R.string.run_open_chat))
                    }
                }
                Spacer(Modifier.heightIn(min = 24.dp))
            }
        }
    }
}
