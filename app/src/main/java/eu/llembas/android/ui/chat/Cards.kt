@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package eu.llembas.android.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import eu.llembas.android.R
import eu.llembas.android.api.Card
import eu.llembas.android.api.CardAnswer
import eu.llembas.android.api.CardItem
import eu.llembas.android.api.LembasJson
import eu.llembas.android.api.Plan
import eu.llembas.android.ui.theme.LocalPalette

/** The sentinel the web card's "Something else" row submits (`interaction.OTHER`). */
private const val OTHER = "__other__"

/**
 * A card the reply is waiting on, with the controls the web page gives it. Whatever is pressed
 * becomes a [CardAnswer] in the page form's own terms, so the server reads it through the same
 * lines (`answer_card`) and first answer wins wherever it was given.
 */
@Composable
fun CardSheet(card: Card, onAnswer: (CardAnswer) -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (card.why.isNotBlank()) {
            Text(card.why, style = MaterialTheme.typography.labelMedium, color = LocalPalette.current.inkMuted)
        }
        when (card.kind) {
            "approval" -> Approval(card, onAnswer)
            "plan" -> PlanCard(card, onAnswer)
            else -> Questions(card, onAnswer)
        }
    }
}

@Composable
private fun Approval(card: Card, onAnswer: (CardAnswer) -> Unit) {
    val palette = LocalPalette.current
    val edits = remember { mutableStateMapOf<String, String>() }
    var reason by remember { mutableStateOf("") }
    var refusing by remember { mutableStateOf(false) }
    Text(stringResource(R.string.card_approval), style = MaterialTheme.typography.titleMedium)
    card.items.forEach { item ->
        if (item.editable) {
            OutlinedTextField(
                value = edits[item.key] ?: item.title,
                onValueChange = { edits[item.key] = it },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(item.title, fontFamily = FontFamily.Monospace)
        }
        if (item.detail.isNotBlank()) Text(item.detail, style = MaterialTheme.typography.bodySmall, color = palette.inkMuted)
        if (item.purpose.isNotBlank()) Text(item.purpose, style = MaterialTheme.typography.bodySmall, color = palette.inkFaint)
    }
    // A corrected command goes with an allow, never with a refusal.
    val texts = edits.filter { (key, value) -> card.items.firstOrNull { it.key == key }?.title != value }
    if (refusing) {
        OutlinedTextField(
            value = reason,
            onValueChange = { reason = it },
            label = { Text(stringResource(R.string.card_reason)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onAnswer(CardAnswer(verdict = "deny", reason = reason)) }) { Text(stringResource(R.string.card_deny)) }
            TextButton(onClick = { refusing = false }) { Text(stringResource(R.string.cancel)) }
        }
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onAnswer(CardAnswer(verdict = "allow", texts = texts)) }) { Text(stringResource(R.string.card_allow_once)) }
            OutlinedButton(onClick = { onAnswer(CardAnswer(verdict = "allow_always", texts = texts)) }) {
                Text(stringResource(R.string.card_allow_always))
            }
            TextButton(onClick = { refusing = true }) { Text(stringResource(R.string.card_deny)) }
        }
    }
}

@Composable
private fun Questions(card: Card, onAnswer: (CardAnswer) -> Unit) {
    val palette = LocalPalette.current
    val picked = remember { mutableStateMapOf<String, Set<String>>() }
    val typed = remember { mutableStateMapOf<String, String>() }
    card.items.forEach { item ->
        if (item.header.isNotBlank()) Text(item.header, style = MaterialTheme.typography.labelMedium, color = palette.inkMuted)
        Text(item.title, style = MaterialTheme.typography.titleMedium)
        item.options.forEach { option ->
            val chosen = option.label in (picked[item.key] ?: emptySet())
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { picked[item.key] = toggle(item, picked[item.key], option.label) },
            ) {
                if (item.multiple) {
                    Checkbox(checked = chosen, onCheckedChange = { picked[item.key] = toggle(item, picked[item.key], option.label) })
                } else {
                    RadioButton(selected = chosen, onClick = { picked[item.key] = toggle(item, picked[item.key], option.label) })
                }
                Column {
                    Text(option.label + if (option.recommended) " · " + stringResource(R.string.card_recommended) else "")
                    if (option.description.isNotBlank()) {
                        Text(option.description, style = MaterialTheme.typography.bodySmall, color = palette.inkMuted)
                    }
                }
            }
        }
        if (item.freeText) {
            OutlinedTextField(
                value = typed[item.key].orEmpty(),
                onValueChange = { typed[item.key] = it },
                label = { Text(stringResource(if (item.options.isEmpty()) R.string.card_answer else R.string.card_something_else)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    val ready = card.items.all { !picked[it.key].isNullOrEmpty() || !typed[it.key].isNullOrBlank() }
    Button(
        enabled = ready,
        onClick = {
            val choices = card.items.associate { item ->
                val picks = picked[item.key].orEmpty().toList()
                // Typed beside options is "something else": the sentinel says so, as the page's does.
                item.key to if (!typed[item.key].isNullOrBlank() && item.options.isNotEmpty()) picks + OTHER else picks
            }.filterValues { it.isNotEmpty() }
            onAnswer(CardAnswer(choices = choices, texts = typed.filterValues { it.isNotBlank() }))
        },
    ) { Text(stringResource(R.string.card_send)) }
}

private fun toggle(item: CardItem, now: Set<String>?, label: String): Set<String> {
    val set = now.orEmpty()
    return when {
        !item.multiple -> setOf(label)
        label in set -> set - label
        else -> set + label
    }
}

@Composable
private fun PlanCard(card: Card, onAnswer: (CardAnswer) -> Unit) {
    val item = card.items.firstOrNull() ?: return
    var mode by remember { mutableStateOf("edit") }
    var revising by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }
    val plan = item.plan?.let { runCatching { LembasJson.decodeFromJsonElement(Plan.serializer(), it) }.getOrNull() }
    Text(plan?.title?.ifBlank { null } ?: item.title, style = MaterialTheme.typography.titleMedium)
    plan?.let { PlanBody(it) }
    if (item.modeChoice) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = mode == "edit", onClick = { mode = "edit" })
            Text(stringResource(R.string.mode_edit))
            RadioButton(selected = mode == "manual", onClick = { mode = "manual" })
            Text(stringResource(R.string.mode_manual))
        }
    }
    if (revising) {
        OutlinedTextField(
            value = feedback,
            onValueChange = { feedback = it },
            label = { Text(stringResource(R.string.plan_what_to_change)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { onAnswer(CardAnswer(verdict = "deny", reason = feedback)) }) { Text(stringResource(R.string.card_send)) }
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                onAnswer(CardAnswer(verdict = "allow", choices = if (item.modeChoice) mapOf(item.key to listOf(mode)) else emptyMap()))
            }) { Text(stringResource(R.string.plan_approve)) }
            OutlinedButton(onClick = { revising = true }) { Text(stringResource(R.string.plan_revise)) }
        }
    }
}

/** A plan's phases and tasks, ticked as the reply works through them. */
@Composable
fun PlanBody(plan: Plan) {
    val palette = LocalPalette.current
    if (plan.summary.isNotBlank()) Text(plan.summary, style = MaterialTheme.typography.bodySmall, color = palette.inkMuted)
    plan.phases.forEach { phase ->
        if (plan.phases.size > 1) Text(phase.title, style = MaterialTheme.typography.labelLarge)
        phase.tasks.forEach { task ->
            val mark = when (task.status) {
                "done" -> "✓"
                "doing" -> "▸"
                "dropped" -> "–"
                else -> "○"
            }
            Text("$mark ${task.text}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
