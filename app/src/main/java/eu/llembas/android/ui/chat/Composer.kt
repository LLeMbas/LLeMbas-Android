package eu.llembas.android.ui.chat

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import eu.llembas.android.R
import eu.llembas.android.api.AgentsSetting
import eu.llembas.android.api.LinkedDevice
import eu.llembas.android.assist.Recorder
import eu.llembas.android.lembas
import eu.llembas.android.ui.theme.LocalPalette
import kotlinx.coroutines.launch

private val Field = RoundedCornerShape(28.dp)

/** The agent modes, as the web page offers them, in the order of how much the model may do. */
val AGENT_MODES = listOf("manual", "edit", "auto", "plan")

@Composable
fun modeName(mode: String): String = when (mode) {
    "manual" -> stringResource(R.string.mode_manual)
    "edit" -> stringResource(R.string.mode_edit)
    "auto" -> stringResource(R.string.mode_auto)
    "plan" -> stringResource(R.string.mode_plan)
    else -> mode.replaceFirstChar { it.uppercase() }
}

/** A level's name: `off` and `default` in words, an effort as the model calls it. */
@Composable
fun levelName(level: String): String = when (level) {
    "off" -> stringResource(R.string.level_off)
    "default" -> stringResource(R.string.level_default)
    else -> level
}

/**
 * The words on the tune button: what is set, briefly ("Thinking · Plan", "high · Agents 1–4"),
 * or nothing when all is as it starts.
 */
@Composable
fun tuneSummary(state: ChatState, agent: Boolean): String {
    val parts = mutableListOf<String>()
    val info = state.modelInfo
    val level = state.level
    if (info != null && level != null) {
        when (info.levelControl) {
            "switch" -> if (level != "off") parts += stringResource(R.string.thinking)
            "slider" -> parts += levelName(level)
        }
    }
    val chat = state.chat
    when {
        agent || chat?.isAgent == true -> parts += modeName(chat?.mode?.ifBlank { null } ?: state.draft.agentMode)
        (chat == null && state.draft.plan) || chat?.mode == "plan" -> parts += stringResource(R.string.mode_plan)
    }
    // The team only once someone chose its size: the default says nothing worth repeating.
    val chosenTeam = state.chat?.agents ?: state.draft.agents
    if (state.offersTeam(agent) && chosenTeam != null && chosenTeam.max > 1) {
        parts += stringResource(R.string.team_chip, state.agents.min.toString(), state.agents.max.toString())
    }
    return parts.joinToString(" · ")
}

/**
 * The composer: one rounded field with the attachments above the words and the controls
 * under them -- attach (+), the tune button (thinking, team, mode), the microphone, and Send,
 * which becomes Stop while a reply is being written and nothing new is typed. The controls'
 * row is a shared track: every icon keeps its 48 dp, the tune button's words give.
 */
@SuppressLint("MissingPermission")
@Composable
fun Composer(state: ChatState, actions: ChatActions, agent: Boolean, onTune: () -> Unit, modifier: Modifier = Modifier, initial: String = "") {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf(initial) }
    var attachMenu by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var photo by remember { mutableStateOf<Uri?>(null) }
    val recorder = remember { Recorder(scope) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { actions.attach(it) }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { actions.attach(it) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        photo?.let { if (taken) actions.attach(listOf(it)) }
        photo = null
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            recorder.start()
            listening = true
        }
    }
    // A message the server did not take comes back here, so nothing typed is lost.
    LaunchedEffect(state.unsent) {
        state.unsent?.let {
            if (text.isBlank()) text = it
            actions.clearUnsent()
        }
    }
    // A suggestion from the empty chat lands here (a new value each tap).
    LaunchedEffect(initial) { if (initial.isNotBlank()) text = initial }
    val newAgent = agent && state.chat == null
    val canSend = (text.isNotBlank() || state.pending.isNotEmpty()) && !state.uploading && (!newAgent || state.device != null)
    val summary = tuneSummary(state, agent)

    Surface(
        shape = Field,
        color = palette.surfaceRaised,
        contentColor = palette.ink,
        border = BorderStroke(1.dp, palette.border),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            if (state.uploading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
            if (state.pending.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.pending.forEach { a ->
                        Box {
                            AttachmentTile(a, side = 64.dp)
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(2.dp)
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(palette.ink)
                                    .clickable { actions.unattach(a) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.remove),
                                    tint = palette.surface,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }
            }
            TextField(
                value = text,
                onValueChange = { text = it },
                placeholder = {
                    Text(
                        stringResource(
                            if (listening) {
                                R.string.listening
                            } else if (agent) {
                                R.string.composer_hint_agent
                            } else {
                                R.string.composer_hint
                            },
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                textStyle = MaterialTheme.typography.bodyLarge,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            )
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    IconButton(onClick = { attachMenu = true }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.attach), tint = palette.inkMuted)
                    }
                    DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Filled.PhotoCamera, contentDescription = null) },
                            text = { Text(stringResource(R.string.attach_take_photo)) },
                            onClick = {
                                attachMenu = false
                                actions.photoTarget()?.also { photo = it }?.let { takePhoto.launch(it) }
                            },
                        )
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null) },
                            text = { Text(stringResource(R.string.attach_choose_photos)) },
                            onClick = {
                                attachMenu = false
                                pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                        )
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Filled.UploadFile, contentDescription = null) },
                            text = { Text(stringResource(R.string.attach_choose_files)) },
                            onClick = {
                                attachMenu = false
                                pickFiles.launch("*/*")
                            },
                        )
                    }
                }
                // The tune button: the icon, and what is set in a few words. It sits in the one
                // part of the row that gives, so the icons after it keep their place.
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    Surface(
                        onClick = onTune,
                        shape = RoundedCornerShape(50),
                        color = if (summary.isEmpty()) Color.Transparent else palette.accentSoft,
                    ) {
                        Row(Modifier.heightIn(min = 36.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Tune,
                                contentDescription = stringResource(R.string.tune),
                                tint = if (summary.isEmpty()) palette.inkMuted else palette.accent,
                                modifier = Modifier.size(20.dp),
                            )
                            if (summary.isNotEmpty()) {
                                Text(
                                    summary,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = palette.ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 6.dp),
                                )
                            }
                        }
                    }
                }
                IconButton(onClick = {
                    if (listening) {
                        listening = false
                        scope.launch {
                            val wav = recorder.stop()
                            if (wav.isNotEmpty()) {
                                runCatching { context.lembas.instances.client()?.transcribe(wav) }
                                    .onSuccess { heard -> if (!heard.isNullOrBlank()) text = (text + " " + heard).trim() }
                            }
                        }
                    } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        recorder.start()
                        listening = true
                    } else {
                        askMic.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }) {
                    Icon(
                        if (listening) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = stringResource(R.string.dictate),
                        tint = if (listening) palette.danger else palette.inkMuted,
                    )
                }
                if (state.running && text.isBlank() && state.pending.isEmpty()) {
                    IconButton(
                        onClick = actions::stop,
                        colors = IconButtonDefaults.iconButtonColors(containerColor = palette.ink, contentColor = palette.surface),
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.stop))
                    }
                } else {
                    IconButton(
                        enabled = canSend,
                        onClick = {
                            actions.send(text)
                            text = ""
                        },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = palette.accent,
                            contentColor = palette.accentInk,
                            disabledContainerColor = palette.surfaceActive,
                            disabledContentColor = palette.inkFaint,
                        ),
                    ) {
                        Icon(Icons.Filled.ArrowUpward, contentDescription = stringResource(R.string.send))
                    }
                }
            }
        }
    }
}

// --- The tune sheet --------------------------------------------------------------------------

/**
 * What can be tuned for this chat, and only what its model and kind offer: how hard the model
 * thinks (a switch for on or off, choices for several levels), the team's size, and the mode
 * (Plan first for a chat, the agent's mode for an agent chat). A new agent chat also chooses
 * its device and folder here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TuneContent(state: ChatState, actions: ChatActions, agent: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val chat = state.chat
    val info = state.modelInfo
    val level = state.level
    val newAgent = agent && chat == null
    var askDirectory by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(stringResource(R.string.tune), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))

        if (newAgent) {
            TuneHeader(stringResource(R.string.agent_device))
            if (state.devices.isEmpty()) {
                Text(
                    stringResource(R.string.agent_no_device),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.inkMuted,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            } else {
                DeviceChoices(state.devices, state.device) { id -> actions.updateDraft { it.copy(device = id) } }
            }
            TuneRow(
                title = stringResource(R.string.agent_directory),
                subtitle = state.draft.directory.ifBlank { stringResource(R.string.agent_directory_hint) },
                onClick = { askDirectory = true },
                leading = { Icon(Icons.Filled.Folder, contentDescription = null, tint = palette.inkMuted) },
            )
        }

        if (info != null && level != null) {
            when (info.levelControl) {
                "switch" -> TuneRow(
                    title = stringResource(R.string.thinking_switch),
                    subtitle = stringResource(R.string.thinking_switch_hint),
                    onClick = { pickLevel(actions, chat == null, if (level == "off") info.onLevel else "off") },
                    trailing = {
                        Switch(checked = level != "off", onCheckedChange = { on -> pickLevel(actions, chat == null, if (on) info.onLevel else "off") })
                    },
                )

                "slider" -> {
                    TuneHeader(stringResource(R.string.effort))
                    FlowRow(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        info.offered.forEach { option ->
                            FilterChip(
                                selected = option == level,
                                onClick = { pickLevel(actions, chat == null, option) },
                                label = { Text(levelName(option)) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                }
            }
        }

        if (state.offersTeam(agent)) {
            TuneHeader(stringResource(R.string.team_agents))
            Text(
                stringResource(R.string.team_hint),
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkMuted,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            val now = state.agents
            Column(Modifier.padding(horizontal = 24.dp)) {
                AgentsSlider(stringResource(R.string.team_fewest), now.min) { v ->
                    setTeam(actions, chat == null, AgentsSetting(v, maxOf(v, now.max)))
                }
                AgentsSlider(stringResource(R.string.team_most), now.max) { v ->
                    setTeam(actions, chat == null, AgentsSetting(minOf(now.min, v), v))
                }
            }
        }

        when {
            newAgent || chat?.isAgent == true -> {
                TuneHeader(stringResource(R.string.agent_mode))
                val current = chat?.mode?.ifBlank { null } ?: if (chat == null) state.draft.agentMode else "edit"
                FlowRow(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AGENT_MODES.forEach { m ->
                        FilterChip(
                            selected = m == current,
                            onClick = { if (chat == null) actions.updateDraft { it.copy(agentMode = m) } else actions.change("mode", m) },
                            label = { Text(modeName(m)) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }

            else -> {
                val on = if (chat == null) state.draft.plan else chat.mode == "plan"
                TuneRow(
                    title = stringResource(R.string.plan_first),
                    subtitle = stringResource(R.string.plan_first_hint),
                    onClick = { setPlan(actions, chat == null, !on) },
                    trailing = { Switch(checked = on, onCheckedChange = { setPlan(actions, chat == null, it) }) },
                )
            }
        }
    }

    if (askDirectory) {
        var typed by remember { mutableStateOf(state.draft.directory) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askDirectory = false },
            title = { Text(stringResource(R.string.agent_directory)) },
            text = {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.agent_directory_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    actions.updateDraft { it.copy(directory = typed.trim()) }
                    askDirectory = false
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { askDirectory = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun pickLevel(actions: ChatActions, draft: Boolean, level: String) {
    if (draft) actions.updateDraft { it.copy(effort = level) } else actions.change("effort", level)
}

private fun setTeam(actions: ChatActions, draft: Boolean, agents: AgentsSetting) {
    if (draft) actions.updateDraft { it.copy(agents = agents) } else actions.setAgents(agents)
}

private fun setPlan(actions: ChatActions, draft: Boolean, on: Boolean) {
    if (draft) actions.updateDraft { it.copy(plan = on) } else actions.change("mode", if (on) "plan" else "")
}

@Composable
private fun TuneHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = LocalPalette.current.accent,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp),
    )
}

/** A row of the sheet: words that give, and a control that keeps its size. */
@Composable
private fun TuneRow(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 64.dp).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.inkMuted) }
        }
        if (trailing != null) {
            Spacer(Modifier.width(16.dp))
            trailing()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceChoices(devices: List<LinkedDevice>, current: LinkedDevice?, onPick: (String) -> Unit) {
    FlowRow(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        devices.forEach { d ->
            FilterChip(
                selected = d.id == current?.id,
                onClick = { onPick(d.id) },
                label = { Text(listOf(d.name, d.host).filter { it.isNotBlank() }.distinct().joinToString(" · "), maxLines = 1) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

/**
 * One end of the team's size: its label and value on a row of their own, so the two sliders
 * line up whatever the words, and the slider under them.
 */
@Composable
fun AgentsSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    var shown by remember(value) { mutableStateOf(value.toFloat()) }
    Column(Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(Math.round(shown).toString(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
        }
        Slider(
            value = shown,
            onValueChange = { shown = it },
            onValueChangeFinished = { onChange(Math.round(shown).coerceIn(1, AgentsSetting.CAP)) },
            valueRange = 1f..AgentsSetting.CAP.toFloat(),
            // Continuous: 31 tick marks would be noise, and the value is rounded anyway.
        )
    }
}
