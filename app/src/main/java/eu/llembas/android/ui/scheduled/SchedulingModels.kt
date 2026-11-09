package eu.llembas.android.ui.scheduled

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.llembas.android.R
import eu.llembas.android.api.ApiException
import eu.llembas.android.api.SchedulingModel
import eu.llembas.android.api.SchedulingModels
import eu.llembas.android.lembas
import eu.llembas.android.ui.explain
import eu.llembas.android.ui.theme.LocalPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ModelsState(val models: SchedulingModels? = null, val loading: Boolean = false, val error: String? = null)

class SchedulingModelsViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(ModelsState())
    val state = _state.asStateFlow()

    private suspend fun api() = getApplication<Application>().lembas.instances.client() ?: throw ApiException(401, "Not signed in.")

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val models = api().schedulingModels()
                _state.update { it.copy(models = models, loading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = getApplication<Application>().explain(e)) }
            }
        }
    }

    /** Tick or untick one; the server keeps the person's narrowing and answers the whole list. */
    fun toggle(model: SchedulingModel) {
        val now = _state.value.models ?: return
        val chosen = SchedulingLogic.toggled(now.models, model.id) ?: return
        viewModelScope.launch {
            try {
                val saved = api().chooseSchedulingModels(chosen)
                _state.update { it.copy(models = saved, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(error = getApplication<Application>().explain(e)) }
            }
        }
    }
}

/** The narrowing a tick makes, pure so it can be tested. */
object SchedulingLogic {
    /**
     * The ids to send after ticking or unticking [id]: in the list's order; empty when every
     * one is ticked (the server's "no narrowing"). Null when it would untick the last one --
     * scheduled work needs at least one model.
     */
    fun toggled(models: List<SchedulingModel>, id: String): List<String>? {
        val chosen = models.filter { if (it.id == id) !it.chosen else it.chosen }.map { it.id }
        return when {
            chosen.isEmpty() -> null
            chosen.size == models.size -> emptyList()
            else -> chosen
        }
    }
}

@Composable
fun SchedulingModelsScreen(onBack: () -> Unit, model: SchedulingModelsViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        model.load()
        onPauseOrDispose { }
    }
    SchedulingModelsContent(state, onBack = onBack, onToggle = model::toggle, onRetry = model::load)
}

/**
 * The models scheduled tasks run on, in the order they are tried -- each with whether it is free
 * now or busy (the server's words) -- and a tick for the person's own narrowing of the list.
 */
@Composable
fun SchedulingModelsContent(state: ModelsState, onBack: () -> Unit = {}, onToggle: (SchedulingModel) -> Unit = {}, onRetry: () -> Unit = {}) {
    val palette = LocalPalette.current
    val models = state.models?.models.orEmpty()
    Scaffold(containerColor = palette.bg, topBar = { BackBar(stringResource(R.string.schedule_models), onBack) }) { padding ->
        PullToRefreshBox(isRefreshing = state.loading, onRefresh = onRetry, modifier = Modifier.fillMaxSize().padding(padding)) {
            if (models.isEmpty() && state.error != null) {
                Message(state.error, retry = onRetry)
                return@PullToRefreshBox
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        stringResource(R.string.schedule_models_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.inkMuted,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
                    )
                }
                state.error?.let { item { Text(it, color = palette.danger, style = MaterialTheme.typography.bodyMedium) } }
                items(models, key = { it.id }) { m -> ModelRow(m, onToggle = { onToggle(m) }) }
            }
        }
    }
}

@Composable
private fun ModelRow(model: SchedulingModel, onToggle: () -> Unit) {
    val palette = LocalPalette.current
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(16.dp),
        color = palette.surfaceRaised,
        contentColor = palette.ink,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.heightIn(min = 64.dp).padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = model.chosen,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(checkedColor = palette.accent, checkmarkColor = palette.accentInk, uncheckedColor = palette.inkMuted),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(model.name.ifBlank { model.id }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val (line, tone) = if (model.free) {
                    stringResource(R.string.model_free) to palette.success
                } else {
                    stringResource(R.string.model_busy, model.busy.orEmpty()).trimEnd(' ', ':') to palette.inkMuted
                }
                Text(line, style = MaterialTheme.typography.bodySmall, color = tone, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
