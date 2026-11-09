package eu.llembas.android.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.llembas.android.R
import eu.llembas.android.api.ModelInfo
import eu.llembas.android.ui.ModelAvatar
import eu.llembas.android.ui.theme.LocalPalette

/** A context window in the way people say it: 8K, 128K, 1M. */
fun compactContext(tokens: Int): String = when {
    tokens >= 1_000_000 && tokens % 1_000_000 == 0 -> "${tokens / 1_000_000}M"
    tokens >= 1_000_000 -> "%.1fM".format(tokens / 1_000_000.0)
    else -> "${Math.round(tokens / 1024.0)}K"
}

/** What a model can do, in a few words for the picker: sees images, uses tools, its window. */
@Composable
fun modelNotes(model: ModelInfo): String = buildList {
    if (model.vision) add(stringResource(R.string.model_sees_images))
    if (model.tools) add(stringResource(R.string.model_uses_tools))
    if (model.context > 0) add(stringResource(R.string.model_context, compactContext(model.context)))
}.joinToString(" · ")

/** How the model thinks, for the picker: a switch, or its levels by name; null for one level. */
@Composable
fun modelLevels(model: ModelInfo): String? = when (model.levelControl) {
    "switch" -> stringResource(R.string.model_thinking_switchable)
    "slider" -> stringResource(R.string.model_levels, model.offered.map { levelName(it) }.joinToString(", "))
    else -> null
}

/**
 * The top bar's title: the model's picture and name, and a caret that says it opens; under it,
 * smaller, the chat's title once it has one. The name gives before the caret does.
 */
@Composable
fun ModelTitle(model: ModelInfo?, fallback: String, subtitle: String?, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = Color.Transparent) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ModelAvatar(model, fallback, size = 28.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f, fill = false)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        model?.name?.ifBlank { null } ?: fallback,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.model_choose), tint = palette.inkMuted)
                }
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = palette.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * The model picker (a bottom sheet's content): each model with its picture, its name, what it
 * can do and how it thinks; the one in use is ticked. Rows are at least 72 dp and their words
 * wrap rather than overflow.
 */
@Composable
fun ModelPickerContent(models: List<ModelInfo>, current: String?, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Column(modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.model_choose),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            items(models, key = { it.id }) { m ->
                val chosen = m.id == current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(m.id) }
                        .heightIn(min = 72.dp)
                        .padding(horizontal = 24.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ModelAvatar(m, m.id, size = 44.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.name.ifBlank { m.id }, style = MaterialTheme.typography.titleMedium)
                        modelNotes(m).takeIf { it.isNotEmpty() }?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.inkMuted)
                        }
                        modelLevels(m)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = palette.inkFaint)
                        }
                    }
                    if (chosen) {
                        Spacer(Modifier.width(12.dp))
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = stringResource(R.string.model_in_use),
                            tint = palette.accent,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
    }
}
