package eu.llembas.android.assist

import android.text.format.DateUtils
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.ScreenshotMonitor
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.llembas.android.R
import eu.llembas.android.ui.ModelAvatar
import eu.llembas.android.ui.chat.Steps
import eu.llembas.android.ui.theme.LocalPalette
import kotlinx.coroutines.launch

/** Where a let-go drag leaves the sheet. */
enum class SheetStop { Half, Full, Gone }

/**
 * Where the sheet goes when a drag of its handle ends at [height] px moving at [velocity] px/s
 * (positive is down), between [half] and [full]: a fling decides by its direction, a slow
 * release by the nearest stop -- and below most of the half, it goes.
 */
fun settle(height: Float, half: Float, full: Float, velocity: Float): SheetStop = when {
    velocity > FLING -> if (height < half + 1f) SheetStop.Gone else SheetStop.Half
    velocity < -FLING -> SheetStop.Full
    height < half * 0.6f -> SheetStop.Gone
    height > (half + full) / 2f -> SheetStop.Full
    else -> SheetStop.Half
}

private const val FLING = 1_200f

/**
 * The assistant's window: the app it opened over stays in sight under a dim, and the sheet rises
 * from the bottom to about half the height. Its handle drags it to the full height or down and
 * away; a tap on the dim, or Back, closes it too. With the keyboard up, the sheet's stops are
 * measured above the keyboard.
 */
@Composable
fun AssistSheetFrame(onDismiss: () -> Unit, content: @Composable ColumnScope.(handle: Modifier, expanded: Boolean) -> Unit) {
    val palette = LocalPalette.current
    val inspection = LocalInspectionMode.current
    val scope = rememberCoroutineScope()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .imePadding(),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val full = with(density) { (maxHeight - top - 8.dp).toPx() }.coerceAtLeast(0f)
            val half = (full * 0.56f).coerceAtLeast(minOf(full, with(density) { 360.dp.toPx() }))
            var expanded by remember { mutableStateOf(false) }
            val target = if (expanded) full else half
            val height = remember { Animatable(if (inspection) target else 0f) }
            LaunchedEffect(target) { height.animateTo(target) }
            val drag = rememberDraggableState { delta -> scope.launch { height.snapTo((height.value - delta).coerceIn(0f, full)) } }
            val handle = Modifier.draggable(
                drag,
                Orientation.Vertical,
                onDragStopped = { velocity ->
                    when (settle(height.value, half, full, velocity)) {
                        SheetStop.Gone -> {
                            height.animateTo(0f)
                            onDismiss()
                        }

                        SheetStop.Full -> {
                            expanded = true
                            height.animateTo(full)
                        }

                        SheetStop.Half -> {
                            expanded = false
                            height.animateTo(half)
                        }
                    }
                },
            )
            Surface(
                color = palette.surface,
                contentColor = palette.ink,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                tonalElevation = 2.dp,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(with(density) { height.value.toDp() })
                    // Taps inside the sheet are the sheet's, not the dim's.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            ) {
                Column(Modifier.fillMaxSize().navigationBarsPadding()) { content(handle, expanded) }
            }
        }
    }
}

/**
 * The sheet's contents, from [state] alone (what the screenshot tests draw):
 *
 * - **the top**: the drag handle, the model and connection chip (the model's picture, its name,
 *   the instance and whether it answers; a tap picks another model), voice reply on or off, and
 *   Open in app, which carries on this same chat in the app;
 * - **the middle**: before anything is asked, the last assistant chat to continue, the quick
 *   actions (the screen's, when the system handed one over), and how to open the assistant --
 *   and that there is no wake word; then the questions and answers, the answers as they are
 *   written;
 * - **the foot**: Use screen, then the microphone first -- while listening, the words as they are
 *   heard and how loud -- or the words typed, and Send.
 *
 * Nothing from the account is on it while the phone is [locked]: no model list, no last chat.
 */
@Composable
fun ColumnScope.AssistSheetContent(
    state: AssistUiState,
    actions: AssistActions,
    locked: Boolean,
    handle: Modifier = Modifier,
    onOpenApp: () -> Unit = {},
    onSend: (String) -> Unit = actions::ask,
    onMic: () -> Unit = actions::listen,
) {
    val palette = LocalPalette.current
    var text by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    val growth = state.turns.lastOrNull()?.steps?.sumOf { (it.markdown?.length ?: 0) + (it.text?.length ?: 0) } ?: 0
    LaunchedEffect(state.turns.size, growth) { if (state.turns.isNotEmpty()) scroll.animateScrollTo(scroll.maxValue) }

    // The top: the handle and the bar. Both drag the sheet.
    Column(handle.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 36.dp, height = 4.dp).background(palette.inkFaint, RoundedCornerShape(2.dp)))
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val showChip = state.signedIn && !locked
            Box(Modifier.weight(1f)) {
                if (showChip) {
                    ModelChip(state, actions)
                } else {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(8.dp))
                }
            }
            if (state.canSpeak && !locked) {
                IconButton(onClick = { actions.voiceReply(!state.voiceReply) }) {
                    Icon(
                        if (state.voiceReply) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                        contentDescription = stringResource(if (state.voiceReply) R.string.assist_voice_reply_on else R.string.assist_voice_reply_off),
                        tint = if (state.voiceReply) palette.accent else palette.inkMuted,
                    )
                }
            }
            if (state.signedIn) {
                IconButton(onClick = onOpenApp) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = stringResource(R.string.assist_open_in_app), tint = palette.inkMuted)
                }
            }
        }
        if (state.busy) {
            val bar = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(2.dp)
            // A preview (the screenshot tests) cannot wait out an endless animation: a still bar there.
            if (LocalInspectionMode.current) LinearProgressIndicator(progress = { 0.4f }, modifier = bar) else LinearProgressIndicator(bar)
        } else {
            Spacer(Modifier.height(2.dp))
        }
    }

    if (!state.signedIn) {
        Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.assist_signed_out), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onOpenApp) { Text(stringResource(R.string.assist_open_app)) }
        }
        return
    }

    // The middle.
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.empty) {
            Starting(state, actions, locked)
        } else {
            state.turns.forEachIndexed { i, turn ->
                Turn(turn, last = i == state.turns.lastIndex, state, actions, onOpenApp)
            }
        }
        state.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.danger) }
    }

    // The foot.
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)) {
        if (state.screenAvailable && !locked) ScreenToggle(state, actions)
        if (state.listening) {
            Listening(state, onDone = actions::heard, onType = actions::type)
        } else {
            val focus = remember { FocusRequester() }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(if (state.busy && state.heard.isNotBlank()) R.string.assist_writing_down else R.string.assist_hint)) },
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = palette.surfaceRaised,
                        unfocusedContainerColor = palette.surfaceRaised,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    maxLines = 4,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp).focusRequester(focus),
                )
                Spacer(Modifier.width(8.dp))
                // One slot of fixed size: the microphone, or Send once there are words.
                Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                    if (text.isBlank()) {
                        if (state.canListen) {
                            Surface(
                                onClick = onMic,
                                shape = CircleShape,
                                color = palette.accent,
                                contentColor = palette.accentInk,
                                modifier = Modifier.size(52.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Mic, contentDescription = stringResource(R.string.dictate)) }
                            }
                        }
                    } else {
                        Surface(
                            onClick = {
                                onSend(text)
                                text = ""
                            },
                            enabled = !state.busy || locked,
                            shape = CircleShape,
                            color = palette.accent,
                            contentColor = palette.accentInk,
                            modifier = Modifier.size(52.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send))
                            }
                        }
                    }
                }
            }
            if (state.heard.isNotBlank()) {
                Text(state.heard, style = MaterialTheme.typography.bodyMedium, color = palette.inkMuted, modifier = Modifier.padding(start = 16.dp, top = 6.dp))
            }
        }
    }
}

/** The model and the connection: its picture, its name, the instance and a dot that says whether it answers. */
@Composable
private fun ModelChip(state: AssistUiState, actions: AssistActions) {
    val palette = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    val dot = when (state.connection) {
        Connection.Online -> palette.success
        Connection.Offline -> palette.danger
        Connection.Checking -> palette.inkFaint
    }
    val where = when (state.connection) {
        Connection.Online -> state.instanceName
        Connection.Offline -> stringResource(R.string.assist_offline, state.instanceName)
        Connection.Checking -> stringResource(R.string.assist_connecting)
    }
    Box {
        Surface(
            onClick = { if (state.models.isNotEmpty()) open = true },
            shape = RoundedCornerShape(24.dp),
            color = palette.surfaceRaised,
            border = BorderStroke(1.dp, palette.border),
            modifier = Modifier.widthIn(max = 320.dp).heightIn(min = 48.dp),
        ) {
            Row(Modifier.padding(start = 6.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    ModelAvatar(state.modelInfo, state.modelId ?: "?", size = 32.dp)
                    // The connection's dot, ringed in the chip's colour so it reads over any picture.
                    val ring = Modifier.align(Alignment.BottomEnd).size(10.dp).clip(CircleShape).background(palette.surfaceRaised)
                    Box(ring.padding(2.dp).clip(CircleShape).background(dot))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f, fill = false)) {
                    Text(
                        state.modelInfo?.name?.ifBlank { null } ?: state.modelId ?: stringResource(R.string.assist_no_model),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(where, style = MaterialTheme.typography.labelSmall, color = palette.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = palette.inkMuted)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.models.forEach { model ->
                DropdownMenuItem(
                    leadingIcon = { ModelAvatar(model, model.id, size = 24.dp) },
                    text = { Text(model.name.ifBlank { model.id }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = if (model.id == state.modelId) {
                        { Box(Modifier.size(8.dp).clip(CircleShape).background(palette.accent)) }
                    } else {
                        null
                    },
                    onClick = {
                        open = false
                        actions.pickModel(model.id)
                    },
                )
            }
        }
    }
}

/** Before anything is asked: the last chat to continue, the quick actions, and how to open this. */
@Composable
private fun Starting(state: AssistUiState, actions: AssistActions, locked: Boolean) {
    val palette = LocalPalette.current
    if (!locked) {
        state.last?.let { last ->
            Surface(
                onClick = actions::continueLast,
                shape = RoundedCornerShape(20.dp),
                color = palette.surfaceRaised,
                border = BorderStroke(1.dp, palette.border),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.History, contentDescription = null, tint = palette.accent)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.assist_continue), style = MaterialTheme.typography.titleSmall)
                        Text(
                            listOf(last.title, if (last.at > 0) DateUtils.getRelativeTimeSpanString(last.at).toString() else "")
                                .filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.inkMuted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
    if (state.screenAvailable && !locked) {
        val summarise = stringResource(R.string.assist_quick_summarise_prompt)
        val translate = stringResource(R.string.assist_quick_translate_prompt)
        val explain = stringResource(R.string.assist_quick_explain_prompt)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickAction(Icons.Filled.Summarize, stringResource(R.string.assist_quick_summarise)) { actions.quick(summarise) }
            QuickAction(Icons.Filled.Translate, stringResource(R.string.assist_quick_translate)) { actions.quick(translate) }
            QuickAction(Icons.Filled.Lightbulb, stringResource(R.string.assist_quick_explain)) { actions.quick(explain) }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.assist_help), style = MaterialTheme.typography.bodySmall, color = palette.inkFaint)
        Text(stringResource(R.string.assist_no_wake_word), style = MaterialTheme.typography.bodySmall, color = palette.inkFaint)
    }
}

@Composable
private fun QuickAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
        colors = AssistChipDefaults.assistChipColors(containerColor = palette.surfaceRaised, leadingIconContentColor = palette.accent),
        border = BorderStroke(1.dp, palette.border),
        modifier = Modifier.heightIn(min = 40.dp),
    )
}

/** One question, as a bubble on the right, and its answer at full width -- as in the app's chats. */
@Composable
private fun Turn(turn: AssistTurn, last: Boolean, state: AssistUiState, actions: AssistActions, onOpenApp: () -> Unit) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(color = palette.bubbleUser, contentColor = palette.ink, shape = RoundedCornerShape(20.dp), modifier = Modifier.widthIn(max = 300.dp)) {
                Text(turn.question, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
        if (turn.steps.isNotEmpty()) Steps(turn.steps)
        val reply = turn.reply
        when {
            reply != null && reply.cards.isNotEmpty() -> TextButton(onClick = onOpenApp) { Text(stringResource(R.string.assist_answer_in_app)) }

            turn.writing && reply?.status?.isNotBlank() == true -> Text(reply.status, style = MaterialTheme.typography.labelSmall, color = palette.inkFaint)

            last && !turn.writing && turn.steps.isNotEmpty() -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.canSpeak) {
                    if (state.speaking) {
                        AssistChip(onClick = actions::stopReading, label = { Text(stringResource(R.string.assist_stop_reading)) }, leadingIcon = {
                            Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        })
                    } else {
                        AssistChip(onClick = actions::readAloud, label = { Text(stringResource(R.string.assist_read_aloud)) }, leadingIcon = {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                        })
                    }
                }
                AssistChip(onClick = actions::newChat, label = { Text(stringResource(R.string.new_chat)) }, leadingIcon = {
                    Icon(Icons.Filled.EditNote, contentDescription = null, modifier = Modifier.size(18.dp))
                })
            }
        }
    }
}

/** Use screen: off every time the sheet opens; on, the screen goes with the next question only. */
@Composable
private fun ScreenToggle(state: AssistUiState, actions: AssistActions) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(
            selected = state.includeScreen,
            onClick = { actions.includeScreen(!state.includeScreen) },
            label = { Text(stringResource(R.string.assist_use_screen)) },
            leadingIcon = { Icon(Icons.Filled.ScreenshotMonitor, contentDescription = null, modifier = Modifier.size(18.dp)) },
            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = palette.accentSoft, selectedLeadingIconColor = palette.accent),
            modifier = Modifier.heightIn(min = 40.dp),
        )
        if (state.includeScreen) {
            Spacer(Modifier.width(10.dp))
            state.screenshot?.let { shot ->
                Image(
                    shot.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 24.dp, height = 40.dp).clip(RoundedCornerShape(4.dp)).background(palette.bgSunken),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                stringResource(
                    when {
                        state.screenText && state.screenshot != null -> R.string.assist_screen_both
                        state.screenText -> R.string.assist_screen_text
                        else -> R.string.assist_screen_shot
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkMuted,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Listening: the microphone, grown by how loud it hears you (a tap sends what was said), the
 * words so far, and the keyboard to type instead. The row's parts keep their sizes; the words
 * give.
 */
@Composable
private fun Listening(state: AssistUiState, onDone: () -> Unit, onType: () -> Unit) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(64.dp).scale(0.8f + 0.2f * (state.level * 3f).coerceAtMost(1f)).clip(CircleShape).background(palette.accentSoft))
            Surface(onClick = onDone, shape = CircleShape, color = palette.accent, contentColor = palette.accentInk, modifier = Modifier.size(52.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Mic, contentDescription = stringResource(R.string.assist_send_now)) }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.listening), style = MaterialTheme.typography.labelLarge, color = palette.accent)
            Text(
                state.heard.ifBlank { stringResource(R.string.assist_say_it) },
                style = MaterialTheme.typography.bodyLarge,
                color = if (state.heard.isBlank()) palette.inkFaint else palette.ink,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onType) { Icon(Icons.Filled.Keyboard, contentDescription = stringResource(R.string.assist_type), tint = palette.inkMuted) }
    }
}
