package eu.llembas.android.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.llembas.android.R
import eu.llembas.android.api.SubagentTree
import eu.llembas.android.api.TreeAgent
import eu.llembas.android.api.TreeLogic
import eu.llembas.android.api.TreeRow
import eu.llembas.android.ui.progressLineOf
import eu.llembas.android.ui.theme.LocalPalette
import eu.llembas.android.ui.theme.Palette

/**
 * The subagents as Android shows them (harness spec 3.4.0, `subagents.md` §12, the reduced
 * list): each row its name and id, its position, its state, what it is doing now (the progress
 * text, without the clock -- a row has no room) and how full its context is. The main agent
 * first; ended subagents after the live ones, dimmed, with how each ended. A subagent's row opens
 * its conversation, read-only ([onOpen]); the main agent's conversation is the chat itself.
 */
@Composable
fun SubagentList(tree: SubagentTree, onOpen: (TreeAgent) -> Unit) {
    val palette = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            stringResource(R.string.team_size, tree.size.toString(), tree.max.toString()),
            style = MaterialTheme.typography.bodySmall,
            color = palette.inkMuted,
        )
        TreeLogic.rows(tree).forEach { row -> TreeRowItem(row, onOpen) }
        if (tree.openSlots > 0) {
            Text(
                stringResource(R.string.tree_open_slots, tree.openSlots.toString()),
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkFaint,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
            )
        }
    }
}

/**
 * One row, two lines on a fixed frame: the name and position give way (cut, not wrapped) to the
 * state word; the progress text gives way to the context percentage in a column of its own width.
 */
@Composable
private fun TreeRowItem(row: TreeRow, onOpen: (TreeAgent) -> Unit) {
    val palette = LocalPalette.current
    val agent = row.agent
    val opens = !agent.isMain
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (opens) Modifier.clickable { onOpen(agent) } else Modifier)
            .alpha(if (row.ended) 0.6f else 1f)
            .padding(start = (16 * row.depth.coerceIn(0, 4)).dp, top = 6.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val title = if (agent.isMain) agent.name else "${agent.name} (${agent.id})"
            Text(
                listOf(title, agent.position).filter { it.isNotBlank() }.joinToString(" — "),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                treeStateWord(agent.state),
                style = MaterialTheme.typography.labelMedium,
                color = treeStateColour(palette, agent.state),
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        val doing = if (row.ended) null else progressLineOf(agent.progress, withElapsed = false)
        val percent = agent.contextPercent
        if (doing != null || percent != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    doing.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    percent?.let { stringResource(R.string.tree_context, it.toString()) }.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.inkFaint,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.width(88.dp),
                )
            }
        }
    }
}

/** The state's word (`loop.json` `team.read.states`), the main agent's and every subagent's. */
@Composable
internal fun treeStateWord(state: String): String = when (state) {
    "working" -> stringResource(R.string.tree_state_working)
    "waiting" -> stringResource(R.string.tree_state_waiting)
    "finished" -> stringResource(R.string.tree_state_finished)
    "idle" -> stringResource(R.string.tree_state_idle)
    "paused" -> stringResource(R.string.tree_state_paused)
    "done" -> stringResource(R.string.tree_state_done)
    "out_of_context" -> stringResource(R.string.tree_state_out_of_context)
    "stopped" -> stringResource(R.string.tree_state_stopped)
    else -> state
}

private fun treeStateColour(palette: Palette, state: String): Color = when (state) {
    "working" -> palette.accent
    "waiting", "paused" -> palette.warning
    "done", "finished" -> palette.success
    "out_of_context" -> palette.danger
    else -> palette.inkFaint
}
