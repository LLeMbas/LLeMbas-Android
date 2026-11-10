@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package eu.llembas.android.ui.chat

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.llembas.android.R
import eu.llembas.android.api.Activity
import eu.llembas.android.api.ActivityAgent
import eu.llembas.android.api.ActivityLogic
import eu.llembas.android.api.ActivitySummary
import eu.llembas.android.api.ActivityTeam
import eu.llembas.android.api.AgentDetail
import eu.llembas.android.api.ContextUse
import eu.llembas.android.api.Output
import eu.llembas.android.api.RoomMessage
import eu.llembas.android.api.TreeLogic
import eu.llembas.android.lembas
import eu.llembas.android.ui.MarkdownText
import eu.llembas.android.ui.explain
import eu.llembas.android.ui.theme.LocalPalette
import eu.llembas.android.ui.theme.Palette
import eu.llembas.android.ui.viewer.TreeFileViewer
import kotlinx.coroutines.delay

// --- The summary chip above the composer --------------------------------------------------

/**
 * What runs in the background, in one line ("2 running · 1 queued · 4 finished"), opening the
 * activity sheet. Only while anything runs: finished work alone is no reason for a line.
 */
@Composable
fun ActivityStrip(summary: ActivitySummary, onOpen: () -> Unit) {
    val counts = ActivityLogic.stripCounts(summary)
    if (counts.isEmpty()) return
    val palette = LocalPalette.current
    val words = counts.map { (state, n) -> stateCount(state, n) }
    Surface(
        onClick = onOpen,
        color = palette.surfaceRaised,
        contentColor = palette.ink,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).heightIn(min = 48.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Bolt, contentDescription = null, tint = palette.accent, modifier = Modifier.size(18.dp))
            Text(
                words.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.activity_open), tint = palette.inkFaint)
        }
    }
}

@Composable
private fun stateCount(state: String, n: Int): String = when (state) {
    "working" -> stringResource(R.string.tasks_n_running, n.toString())
    "queued" -> stringResource(R.string.agents_n_queued, n.toString())
    "waiting" -> stringResource(R.string.agents_n_waiting, n.toString())
    else -> stringResource(R.string.agents_n_finished, n.toString())
}

@Composable
private fun stateWord(state: String): String = when (state) {
    "queued" -> stringResource(R.string.agent_state_queued)
    "working" -> stringResource(R.string.agent_state_working)
    "waiting" -> stringResource(R.string.agent_state_waiting)
    "done" -> stringResource(R.string.agent_state_done)
    "stopped" -> stringResource(R.string.agent_state_stopped)
    else -> stringResource(R.string.agent_state_error)
}

@Composable
private fun kindWord(agent: ActivityAgent): String = when {
    agent.kind == "member" -> stringResource(R.string.task_member)
    agent.type == "command" -> stringResource(R.string.task_command)
    else -> stringResource(R.string.task_subagent)
}

/** The state's colour, in the web panel's: accent working, warning waiting, success done. */
private fun stateColour(palette: Palette, state: String): Color = when (state) {
    "working" -> palette.accent
    "queued", "waiting" -> palette.warning
    "done" -> palette.success
    "error" -> palette.danger
    else -> palette.inkFaint
}

/** A context category's colour, as the web's `activity-context__seg--*` maps them to tokens. */
private fun categoryColour(palette: Palette, key: String): Color = when (key) {
    "messages" -> palette.accent
    "system" -> palette.inkMuted
    "memory" -> palette.warning
    "instructions" -> palette.success
    "tools" -> palette.ink
    "mcp_tools" -> palette.danger
    "since" -> palette.inkFaint
    "buffer" -> palette.borderStrong
    else -> palette.border
}

@Composable
private fun categoryName(key: String): String = when (key) {
    "messages" -> stringResource(R.string.ctx_messages)
    "system" -> stringResource(R.string.ctx_system)
    "memory" -> stringResource(R.string.ctx_memory)
    "instructions" -> stringResource(R.string.ctx_instructions)
    "tools" -> stringResource(R.string.ctx_tools)
    "mcp_tools" -> stringResource(R.string.ctx_mcp_tools)
    "since" -> stringResource(R.string.ctx_since)
    "buffer" -> stringResource(R.string.ctx_buffer)
    "free" -> stringResource(R.string.ctx_free)
    else -> key
}

// --- The sheet ------------------------------------------------------------------------------

/**
 * What the activity sheet asks of the chat. Every action has a do-nothing default, so the
 * screenshot tests draw the sheet with fake data and nothing behind it.
 */
interface ActivityActions {
    fun stop(agent: ActivityAgent) {}
    fun read(agent: ActivityAgent) {}
    fun openFile(path: String) {}
    fun compact() {}
}

/**
 * The chat's activity as a sheet over it, from the top bar's button or the strip: Context,
 * Outputs, Background tasks, the team's Room and Plan -- the web's side panel, from
 * `GET /chats/{chat}/activity`. Kept current by the events route through the view model;
 * without it, read again every few seconds while open. A chat not made yet has nothing to
 * show but says so in each section. Stopping anything asks first.
 */
@Composable
fun ActivityPanel(chatId: String?, state: ChatState, model: ChatViewModel, canOpenFiles: Boolean, onClose: () -> Unit) {
    val live by rememberUpdatedState(state.activityLive)
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var stopping by remember { mutableStateOf<ActivityAgent?>(null) }
    var reading by remember { mutableStateOf<ActivityAgent?>(null) }
    var viewing by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(chatId) {
        if (chatId == null) return@LaunchedEffect
        model.panel(true)
        var ticks = 0
        while (true) {
            delay(1_000)
            now = SystemClock.elapsedRealtime()
            ticks++
            // Without the events route, the sheet reads itself again: cheaply, every 5 s.
            if (!live && ticks % 5 == 0) model.loadActivity(context = false)
        }
    }

    val close = {
        model.panel(false)
        onClose()
    }
    val actions = object : ActivityActions {
        override fun stop(agent: ActivityAgent) {
            stopping = agent
        }

        override fun read(agent: ActivityAgent) {
            reading = agent
        }

        override fun openFile(path: String) {
            if (canOpenFiles) viewing = path
        }

        override fun compact() = model.compact()
    }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        ActivityContent(
            activity = if (chatId == null) Activity() else state.activity,
            readAt = state.activityAt,
            now = now,
            actions = actions,
            canCompact = chatId != null && !state.running,
            compacting = state.compacting,
            canOpenFiles = canOpenFiles,
            onClose = close,
        )
    }

    stopping?.let { agent ->
        AlertDialog(
            onDismissRequest = { stopping = null },
            title = { Text(stringResource(R.string.agent_stop, ActivityLogic.title(agent))) },
            text = {
                Text(
                    if (agent.kind == "member") {
                        stringResource(R.string.agent_stop_member_confirm, agent.name)
                    } else {
                        stringResource(R.string.agent_stop_confirm, ActivityLogic.title(agent))
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    model.stopAgent(agent)
                    stopping = null
                }) { Text(stringResource(R.string.stop)) }
            },
            dismissButton = { TextButton(onClick = { stopping = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (chatId != null) reading?.let { agent -> AgentConversation(chatId, agent, onClose = { reading = null }) }
    viewing?.let { path -> TreeFileViewer(path, onClose = { viewing = null }) }
}

/**
 * The sheet as it is drawn, from its facts alone: what the screenshot tests render. [activity]
 * null is still being read; [now] and [readAt] are the clock that elapsed time counts on;
 * [unfolded] opens the first task and the finished ones, for the screenshots to see them.
 */
@Composable
fun ActivityContent(
    activity: Activity?,
    readAt: Long,
    now: Long,
    actions: ActivityActions,
    canCompact: Boolean = true,
    compacting: Boolean = false,
    canOpenFiles: Boolean = true,
    unfolded: Boolean = false,
    onClose: () -> Unit = {},
) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.activity), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close), tint = palette.inkMuted) }
        }
        if (activity == null) {
            Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        ) {
            item {
                Section(stringResource(R.string.activity_context)) {
                    ContextSection(activity.context, canCompact, compacting, actions::compact)
                }
            }
            if (activity.outputs.isNotEmpty()) {
                item {
                    Section(stringResource(R.string.activity_outputs), count = activity.outputs.size) {
                        OutputsSection(activity.outputs, canOpenFiles, actions::openFile)
                    }
                }
            }
            // 3.4.0: the subagents as a reduced list, each opening its conversation read-only;
            // the Tasks section then holds the background jobs, the members being in the list.
            val tree = activity.tree?.takeIf { TreeLogic.hasSubagents(it) }
            if (tree != null) {
                item {
                    Section(stringResource(R.string.team_agents), count = tree.agents.count { !it.isMain }.takeIf { it > 0 }) {
                        SubagentList(tree) { agent -> actions.read(ActivityLogic.ofTreeAgent(agent)) }
                    }
                }
            }
            val tasks = if (tree != null) activity.copy(agents = activity.agents.filter { it.kind != "member" }, team = null) else activity
            item { Section(stringResource(R.string.activity_tasks)) { TasksSection(tasks, readAt, now, actions, unfolded) } }
            activity.team?.let { team ->
                item { Section(stringResource(R.string.team_room), count = team.room.size.takeIf { it > 0 }) { RoomSection(team) } }
            }
            ActivityLogic.plan(activity.plan)?.let { plan ->
                item { Section(stringResource(R.string.mode_plan), divider = false) { PlanBody(plan) } }
            }
        }
    }
}

/** A heading that folds its section away, open to begin with, with an optional count beside it. */
@Composable
private fun Section(title: String, count: Int? = null, divider: Boolean = true, content: @Composable () -> Unit) {
    val palette = LocalPalette.current
    var open by rememberSaveable(title) { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            count?.let { Text(it.toString(), style = MaterialTheme.typography.labelMedium, color = palette.inkMuted, modifier = Modifier.padding(end = 8.dp)) }
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = palette.inkFaint)
        }
        if (open) content()
        if (divider) HorizontalDivider(Modifier.padding(top = 12.dp), color = palette.border)
    }
}

@Composable
private fun Empty(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = LocalPalette.current.inkMuted, modifier = Modifier.padding(bottom = 4.dp))
}

// --- Context --------------------------------------------------------------------------------

/**
 * The reference's Context: "N% used" and used / window on one line, the segmented bar, one row
 * per category (swatch, name, tokens, per cent), then how far auto-compact is and the button
 * that compacts now.
 */
@Composable
private fun ContextSection(context: ContextUse?, canCompact: Boolean, compacting: Boolean, onCompact: () -> Unit) {
    val palette = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (context == null) {
            Empty(stringResource(R.string.activity_not_measured))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val pct = context.percent
                Text(
                    if (pct != null) stringResource(R.string.activity_used, ActivityLogic.percent(pct)) else ActivityLogic.compactTokens(context.used),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (context.limit > 0) {
                        "${ActivityLogic.compactTokens(context.used)} / ${ActivityLogic.compactTokens(context.limit)}"
                    } else {
                        stringResource(R.string.activity_window_unknown)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkMuted,
                )
            }
            val parts = ActivityLogic.barParts(context)
            if (parts.isNotEmpty()) {
                // One segment per category, by its tokens; the rest of the window is the bar's own
                // background. Weights, so the segments always fill exactly the bar's width.
                Row(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(palette.surfaceRaised)) {
                    parts.forEach { part -> Box(Modifier.weight(part.tokens.toFloat()).fillMaxHeight().background(categoryColour(palette, part.key))) }
                    val rest = (context.limit - parts.sumOf { it.tokens }).coerceAtLeast(0)
                    if (rest > 0) Spacer(Modifier.weight(rest.toFloat()))
                }
            }
            // Each row a fixed grid: swatch, the name that gives, two number columns of their own
            // width -- the numbers line up whatever the names say in which language. The columns
            // are as wide as five of their own figures, so large type widens them with it.
            val figures = MaterialTheme.typography.bodySmall.fontSize * 4.6f
            val column = with(LocalDensity.current) { figures.toDp() }
            context.categories.forEach { part ->
                Row(Modifier.fillMaxWidth().heightIn(min = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(categoryColour(palette, part.key)))
                    Text(
                        categoryName(part.key),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Text(
                        ActivityLogic.compactTokens(part.tokens),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        modifier = Modifier.width(column),
                    )
                    Text(
                        part.percent?.let { "${ActivityLogic.percent(it)}%" } ?: "—",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkMuted,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 4.dp).width(column),
                    )
                }
            }
            Text(
                stringResource(if (context.measured) R.string.activity_measured else R.string.activity_estimated) + " " +
                    stringResource(R.string.activity_tools_offered, context.toolsCount.toString()),
                style = MaterialTheme.typography.labelSmall,
                color = palette.inkFaint,
            )
        }
        // How far automatic compaction is, and the button. The words give; the button keeps its
        // own size whatever the sentence says in which language.
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            val until = context?.let { ActivityLogic.untilAutoCompact(it) }
            Text(
                when {
                    context == null -> ""
                    until != null -> stringResource(R.string.activity_until_compact, ActivityLogic.compactTokens(until))
                    context.autoCompact == false || context.threshold <= 0 -> stringResource(R.string.activity_compact_off)
                    else -> ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkMuted,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            // The server says when there is nothing to summarise yet (`compactable`); an older
            // one does not, and then its refusal says it in words.
            OutlinedButton(onClick = onCompact, enabled = canCompact && !compacting && context != null && context.compactable != false) {
                if (compacting) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.activity_compact))
            }
        }
    }
}

// --- Outputs --------------------------------------------------------------------------------

@Composable
private fun OutputsSection(outputs: List<Output>, canOpen: Boolean, onOpen: (String) -> Unit) {
    val palette = LocalPalette.current
    Column {
        outputs.forEach { out ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = canOpen && out.path.isNotBlank()) { onOpen(out.path) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (out.created) Icons.AutoMirrored.Filled.NoteAdd else Icons.Filled.Description,
                    contentDescription = null,
                    tint = palette.inkMuted,
                    modifier = Modifier.size(18.dp),
                )
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(out.name.ifBlank { out.path }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        out.path,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = palette.inkFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (out.added != null) {
                    Text("+${out.added}", style = MaterialTheme.typography.labelMedium, color = palette.success)
                    Text(" −${out.removed ?: 0}", style = MaterialTheme.typography.labelMedium, color = palette.danger)
                }
            }
        }
    }
}

// --- Background tasks -----------------------------------------------------------------------

/**
 * Subagents, a team's members and background commands, as the reference has them: "Running N"
 * with a card per task (elapsed and a stop each, those under it folded into "N under it"),
 * then "Finished N" folded away.
 */
@Composable
private fun TasksSection(activity: Activity, readAt: Long, now: Long, actions: ActivityActions, unfolded: Boolean) {
    val palette = LocalPalette.current
    val team = activity.team
    if (activity.agents.isEmpty() && team == null) {
        Empty(stringResource(R.string.tasks_empty))
        return
    }
    val (live, ended) = ActivityLogic.split(activity.agents)
    var showEnded by rememberSaveable { mutableStateOf(unfolded) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        team?.let { t ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Groups, contentDescription = null, tint = palette.inkMuted, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(R.string.team_size, t.size.toString(), t.max.toString()),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            if (t.goal.isNotBlank()) Text(t.goal, style = MaterialTheme.typography.bodySmall, color = palette.inkMuted)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.tasks_running), style = MaterialTheme.typography.labelLarge, color = palette.inkMuted)
            Text(live.size.toString(), style = MaterialTheme.typography.labelLarge, color = palette.inkFaint, modifier = Modifier.padding(start = 6.dp))
        }
        if (live.isEmpty()) Empty(stringResource(R.string.tasks_none_running))
        live.forEachIndexed { i, task -> TaskCard(task, readAt, now, actions, unfolded && i == 0) }
        if (ended.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { showEnded = !showEnded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (showEnded) Icons.Filled.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = palette.inkFaint,
                )
                Text(
                    stringResource(R.string.tasks_finished),
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.inkMuted,
                    modifier = Modifier.padding(start = 6.dp),
                )
                Text(ended.size.toString(), style = MaterialTheme.typography.labelLarge, color = palette.inkFaint, modifier = Modifier.padding(start = 6.dp))
            }
            if (showEnded) ended.forEach { TaskCard(it, readAt, now, actions, unfolded = false) }
        }
    }
}

/** One top-level task as a card of its own, with the tasks under it inside, folded. */
@Composable
private fun TaskCard(agent: ActivityAgent, readAt: Long, now: Long, actions: ActivityActions, unfolded: Boolean) {
    val palette = LocalPalette.current
    var nestedOpen by rememberSaveable(agent.kind, agent.id, "nested") { mutableStateOf(unfolded) }
    Surface(
        color = palette.surfaceRaised,
        contentColor = palette.ink,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 2.dp)) {
            TaskRow(agent, readAt, now, actions, unfolded)
            val under = ActivityLogic.descendants(agent)
            if (under > 0) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable { nestedOpen = !nestedOpen }.padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (nestedOpen) Icons.Filled.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = palette.inkFaint,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        stringResource(R.string.tasks_nested, under.toString()),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkMuted,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                if (nestedOpen) ActivityLogic.flatten(agent.children).forEach { TaskRow(it, readAt, now, actions, unfolded = false) }
            }
        }
    }
}

/**
 * One task: a fixed row (chevron, kind icon, what it is -- the part that gives, cut not
 * wrapped --, elapsed time in a column of its own width, the stop square at its tap size),
 * indented by its depth; tapped, it opens to its brief, model, latest step and live text, and
 * the way into its conversation.
 */
@Composable
private fun TaskRow(agent: ActivityAgent, readAt: Long, now: Long, actions: ActivityActions, unfolded: Boolean) {
    val palette = LocalPalette.current
    var open by rememberSaveable(agent.kind, agent.id) { mutableStateOf(unfolded) }
    Column(Modifier.fillMaxWidth().padding(start = (16 * agent.depth.coerceIn(0, 4)).dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { open = !open }.padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (open) Icons.Filled.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = palette.inkFaint,
                modifier = Modifier.size(18.dp),
            )
            Icon(
                when {
                    agent.kind == "member" -> Icons.Filled.Groups
                    agent.type == "command" -> Icons.Filled.Terminal
                    else -> Icons.Filled.SmartToy
                },
                contentDescription = kindWord(agent),
                tint = stateColour(palette, agent.state),
                modifier = Modifier.padding(start = 4.dp).size(18.dp),
            )
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(ActivityLogic.title(agent), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(
                    if (agent.kind == "member") agent.role.ifBlank { null } else null,
                    if (agent.isActive) null else stateWord(agent.state),
                )
                if (sub.isNotEmpty()) {
                    Text(
                        sub.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.inkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                ActivityLogic.duration(ActivityLogic.elapsed(agent, readAt, now)),
                style = MaterialTheme.typography.labelMedium,
                color = palette.inkMuted,
                textAlign = TextAlign.End,
                modifier = Modifier.width(64.dp),
            )
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (agent.stoppable && agent.isActive) {
                    IconButton(onClick = { actions.stop(agent) }) {
                        Box(
                            Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(palette.surfaceActive),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Stop,
                                contentDescription = stringResource(R.string.agent_stop, ActivityLogic.title(agent)),
                                tint = palette.ink,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
        if (open) {
            Column(Modifier.padding(start = 34.dp, end = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val facts = listOfNotNull(
                    kindWord(agent),
                    stateWord(agent.state),
                    agent.agent.ifBlank { null },
                    agent.model.ifBlank { null },
                    if (agent.steps > 0) stringResource(R.string.agent_steps, agent.steps.toString(), agent.tools.toString()) else null,
                )
                Text(facts.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = palette.inkMuted)
                if (agent.brief.isNotBlank() && agent.brief != ActivityLogic.title(agent)) {
                    Text(
                        agent.brief,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = if (agent.type == "command") FontFamily.Monospace else null,
                    )
                }
                agent.step?.let { step ->
                    Text(
                        listOf(step.label.ifBlank { step.tool }, step.title).filter { it.isNotBlank() }.joinToString(" "),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (step.error) palette.danger else palette.inkMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (agent.text.isNotBlank()) {
                    Text(agent.text, style = MaterialTheme.typography.bodySmall, color = palette.ink, maxLines = 6, overflow = TextOverflow.Ellipsis)
                }
                if (agent.transcript) {
                    OutlinedButton(onClick = { actions.read(agent) }) {
                        Icon(Icons.Filled.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.agent_open_conversation), modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}

// --- The room -------------------------------------------------------------------------------

@Composable
private fun RoomSection(team: ActivityTeam) {
    if (team.room.isEmpty()) Empty(stringResource(R.string.room_empty)) else Column { team.room.forEach { RoomEntry(it) } }
}

@Composable
private fun RoomEntry(entry: RoomMessage) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        // A report is marked as on the web: a bar of the accent down its side.
        Box(Modifier.width(3.dp).heightIn(min = 24.dp).background(if (entry.report) palette.accent else Color.Transparent))
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "#${entry.n}  ${entry.from} → ${entry.to.joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (entry.report) {
                    Text(
                        stringResource(R.string.room_report),
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.accentInk,
                        modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(4.dp)).background(palette.accent).padding(horizontal = 4.dp),
                    )
                }
            }
            MarkdownText(entry.text)
        }
    }
}

// --- One agent's conversation, read-only ------------------------------------------------------

/** What a background agent was told and said: `GET /chats/{chat}/agents/{kind}/{id}`, re-read while it works. */
@Composable
private fun AgentConversation(chatId: String, agent: ActivityAgent, onClose: () -> Unit) {
    val context = LocalContext.current
    val palette = LocalPalette.current
    var detail by remember { mutableStateOf<AgentDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(agent.kind, agent.id) {
        val api = context.lembas.instances.client() ?: return@LaunchedEffect
        while (true) {
            try {
                detail = api.agent(chatId, agent.kind, agent.id)
                error = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = context.explain(e)
            }
            if (detail?.agent?.isActive != true) break
            delay(3_000)
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close)) } },
                    title = {
                        Column {
                            Text(agent.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val now = detail?.agent ?: agent
                            Text(
                                listOf(stateWord(now.state), now.model).filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.inkFaint,
                                maxLines = 1,
                            )
                        }
                    },
                )
            },
        ) { padding ->
            val turns = detail?.transcript
            when {
                turns == null && error != null -> Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(error.orEmpty(), color = palette.inkMuted, textAlign = TextAlign.Center)
                }

                turns == null -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

                else -> LazyColumn(
                    Modifier.padding(padding).fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (turns.isEmpty()) item { Empty(stringResource(R.string.agent_nothing_yet)) }
                    items(turns) { turn ->
                        if (turn.role == "user") {
                            Column(
                                Modifier.fillMaxWidth().clip(
                                    RoundedCornerShape(8.dp),
                                ).background(if (turn.machine) palette.surfaceRaised else palette.bubbleUser).padding(12.dp),
                            ) {
                                if (turn.machine) {
                                    Text(
                                        stringResource(R.string.agent_machine),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.inkFaint,
                                    )
                                }
                                Text(turn.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                turn.tools.forEach { tool ->
                                    Text(
                                        listOf(tool.name, tool.title).filter { it.isNotBlank() }.joinToString(" "),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (tool.isError) palette.danger else palette.inkMuted,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (turn.text.isNotBlank()) MarkdownText(turn.text)
                                if (turn.error.isNotBlank()) Text(turn.error, style = MaterialTheme.typography.bodySmall, color = palette.danger)
                                if (turn.running) {
                                    Text(
                                        stringResource(R.string.loading_line),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = palette.inkFaint,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
