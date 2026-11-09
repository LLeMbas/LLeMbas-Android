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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import eu.llembas.android.api.AgentDetail
import eu.llembas.android.api.ContextUse
import eu.llembas.android.api.Output
import eu.llembas.android.api.RoomMessage
import eu.llembas.android.lembas
import eu.llembas.android.ui.MarkdownText
import eu.llembas.android.ui.explain
import eu.llembas.android.ui.theme.LocalPalette
import eu.llembas.android.ui.theme.Palette
import eu.llembas.android.ui.viewer.TreeFileViewer
import kotlinx.coroutines.delay

// --- The strip above the composer ---------------------------------------------------------

/** What runs in the background, in one line ("3 agents working · 1 queued"), opening the panel. */
@Composable
fun ActivityStrip(summary: ActivitySummary, onOpen: () -> Unit) {
    val counts = ActivityLogic.stripCounts(summary)
    if (counts.isEmpty()) return
    val palette = LocalPalette.current
    val words = counts.map { (state, n) -> stateCount(state, n) }
    Surface(
        onClick = onOpen,
        color = palette.surfaceRaised,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).heightIn(min = 48.dp),
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
    "working" -> if (n == 1) stringResource(R.string.agents_one_working) else stringResource(R.string.agents_n_working, n.toString())
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

/** The state's dot, in the web panel's colours: accent working, warning waiting, success done. */
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

// --- The panel ------------------------------------------------------------------------------

/**
 * The chat's activity, full screen: Context, Outputs, Background tasks (with the team's room)
 * and Plan -- the web's activity panel, from `GET /chats/{chat}/activity`. Kept current by the
 * events route through the view model; without it, read again every few seconds while open.
 * Everything an agent wrote is a model's words: names, roles and briefs as plain text, the
 * room and replies through [MarkdownText]'s untrusted rules.
 */
@Composable
fun ActivityPanel(chatId: String, state: ChatState, model: ChatViewModel, canOpenFiles: Boolean, onClose: () -> Unit) {
    val activity = state.activity
    val live by rememberUpdatedState(state.activityLive)
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var stopping by remember { mutableStateOf<ActivityAgent?>(null) }
    var reading by remember { mutableStateOf<ActivityAgent?>(null) }
    var viewing by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        model.panel(true)
        var ticks = 0
        while (true) {
            delay(1_000)
            now = SystemClock.elapsedRealtime()
            ticks++
            // Without the events route, the panel reads itself again: cheaply, every 5 s.
            if (!live && ticks % 5 == 0) model.loadActivity(context = false)
        }
    }

    val close = {
        model.panel(false)
        onClose()
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = close) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close)) }
                    },
                    title = { Text(stringResource(R.string.activity)) },
                )
            },
        ) { padding ->
            if (activity == null) {
                Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Scaffold
            }
            LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Section(stringResource(R.string.activity_context)) { ContextSection(activity.context) } }
                item {
                    Section(stringResource(R.string.activity_outputs), count = activity.outputs.size.takeIf { it > 0 }) {
                        OutputsSection(activity.outputs, canOpenFiles) { viewing = it }
                    }
                }
                item {
                    Section(stringResource(R.string.activity_tasks)) {
                        TasksSection(activity, state.activityAt, now, onStop = { stopping = it }, onRead = { reading = it })
                    }
                }
                ActivityLogic.plan(activity.plan)?.let { plan ->
                    item { Section(stringResource(R.string.mode_plan)) { PlanBody(plan) } }
                }
            }
        }
    }

    stopping?.let { agent ->
        AlertDialog(
            onDismissRequest = { stopping = null },
            title = { Text(stringResource(R.string.agent_stop, agent.name)) },
            text = {
                Text(
                    if (agent.kind == "member") {
                        stringResource(R.string.agent_stop_member_confirm, agent.name)
                    } else {
                        stringResource(R.string.agent_stop_confirm, agent.name)
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
    reading?.let { agent -> AgentConversation(chatId, agent, onClose = { reading = null }) }
    viewing?.let { path -> TreeFileViewer(path, onClose = { viewing = null }) }
}

/** A heading that folds its section away, open to begin with. */
@Composable
private fun Section(title: String, count: Int? = null, content: @Composable () -> Unit) {
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
        HorizontalDivider(Modifier.padding(top = 8.dp), color = palette.border)
    }
}

@Composable
private fun Empty(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = LocalPalette.current.inkMuted)
}

// --- Context --------------------------------------------------------------------------------

@Composable
private fun ContextSection(context: ContextUse?) {
    val palette = LocalPalette.current
    if (context == null) {
        Empty(stringResource(R.string.activity_not_measured))
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
            Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(palette.surfaceRaised)) {
                parts.forEach { part -> Box(Modifier.weight(part.tokens.toFloat()).fillMaxHeight().background(categoryColour(palette, part.key))) }
                val rest = (context.limit - parts.sumOf { it.tokens }).coerceAtLeast(0)
                if (rest > 0) Spacer(Modifier.weight(rest.toFloat()))
            }
        }
        // Each row a fixed grid: swatch, the name that gives, two number columns of their own
        // width -- the numbers line up whatever the names say in which language.
        context.categories.forEach { part ->
            Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
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
                    modifier = Modifier.width(56.dp),
                )
                Text(
                    part.percent?.let { "${ActivityLogic.percent(it)}%" } ?: "—",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkMuted,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(56.dp),
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
}

// --- Outputs --------------------------------------------------------------------------------

@Composable
private fun OutputsSection(outputs: List<Output>, canOpen: Boolean, onOpen: (String) -> Unit) {
    val palette = LocalPalette.current
    if (outputs.isEmpty()) {
        Empty(stringResource(R.string.outputs_empty))
        return
    }
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

@Composable
private fun TasksSection(activity: Activity, readAt: Long, now: Long, onStop: (ActivityAgent) -> Unit, onRead: (ActivityAgent) -> Unit) {
    val palette = LocalPalette.current
    val team = activity.team
    if (activity.agents.isEmpty() && team == null) {
        Empty(stringResource(R.string.tasks_empty))
        return
    }
    val (live, ended) = ActivityLogic.split(activity.agents)
    var showEnded by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val counts = ActivityLogic.stripCounts(activity.summary)
        Text(
            if (counts.isEmpty()) stringResource(R.string.tasks_none_running) else counts.map { (s, n) -> stateCount(s, n) }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = palette.inkMuted,
        )
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
        ActivityLogic.flatten(live).forEach { AgentRow(it, readAt, now, onStop, onRead) }
        if (ended.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { showEnded = !showEnded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (showEnded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = palette.inkFaint)
                Text(
                    stringResource(R.string.agents_n_finished, ended.size.toString()),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            if (showEnded) ActivityLogic.flatten(ended).forEach { AgentRow(it, readAt, now, onStop, onRead) }
        }
        team?.let { t ->
            Text(stringResource(R.string.team_room), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            if (t.room.isEmpty()) Empty(stringResource(R.string.room_empty)) else t.room.forEach { RoomEntry(it) }
        }
    }
}

/**
 * One agent: a fixed row (state dot, what it is -- the part that gives, cut not wrapped --,
 * elapsed time in a column of its own width, the stop button at its tap size), indented by
 * its depth in the tree; tapped, it opens to its brief, model, latest step and what it is
 * writing, and the way into its conversation.
 */
@Composable
private fun AgentRow(agent: ActivityAgent, readAt: Long, now: Long, onStop: (ActivityAgent) -> Unit, onRead: (ActivityAgent) -> Unit) {
    val palette = LocalPalette.current
    var open by rememberSaveable(agent.kind, agent.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = (16 * agent.depth.coerceIn(0, 4)).dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(stateColour(palette, agent.state)))
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(agent.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (agent.role.isNotBlank()) {
                    Text(agent.role, style = MaterialTheme.typography.labelSmall, color = palette.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                if (agent.stoppable) {
                    IconButton(onClick = { onStop(agent) }) {
                        Icon(Icons.Filled.StopCircle, contentDescription = stringResource(R.string.agent_stop, agent.name), tint = palette.inkMuted)
                    }
                }
            }
        }
        if (open) {
            Column(Modifier.padding(start = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val facts = listOfNotNull(
                    stateWord(agent.state),
                    agent.agent.ifBlank { null },
                    agent.model.ifBlank { null },
                    if (agent.steps > 0) stringResource(R.string.agent_steps, agent.steps.toString(), agent.tools.toString()) else null,
                )
                Text(facts.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = palette.inkMuted)
                if (agent.brief.isNotBlank()) Text(agent.brief, style = MaterialTheme.typography.bodySmall)
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
                    OutlinedButton(onClick = { onRead(agent) }) {
                        Icon(Icons.Filled.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.agent_open_conversation), modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
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
