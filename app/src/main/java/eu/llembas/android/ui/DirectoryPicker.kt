package eu.llembas.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.llembas.android.R
import eu.llembas.android.api.DeviceDirectory
import eu.llembas.android.api.DirectoryEntry
import eu.llembas.android.ui.theme.LocalPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Choosing a folder on a linked computer: the last listing the device gave ([listing]; null
 * before the first, or where there never was one), the path in the field ([typed]: where the
 * walk is, or what the person wrote -- the way in that always works), and the sentence for a
 * listing that could not be had, or that came with one ([error]). [edited]: the person wrote in
 * the field since the device was last asked, so its answer does not write over them.
 * [failed]: the last question got no listing.
 */
data class PickerState(
    val listing: DeviceDirectory? = null,
    val loading: Boolean = false,
    val typed: String = "",
    val error: String? = null,
    val edited: Boolean = false,
    val failed: Boolean = false,
)

/** The most subdirectories shown; a device that names more is cut there. */
const val PICKER_ENTRIES = 500

/** The longest a device's own words are kept: a name, a reason, a sentence. */
const val PICKER_WORDS = 300

private fun String.kept(): String = if (length > PICKER_WORDS) take(PICKER_WORDS) + "…" else this

/**
 * A listing as the picker holds it: each path once (the rows are keyed by it), at most
 * [PICKER_ENTRIES] of them, and the device's words -- names, reasons, a model's name -- cut to
 * a length that cannot push the controls away.
 */
fun DeviceDirectory.bounded(): DeviceDirectory = copy(
    reason = reason.kept(),
    error = error.kept(),
    model = model?.let { it.copy(name = it.name.kept(), id = it.id.kept()) },
    entries = entries.filter { it.path.isNotEmpty() }.distinctBy { it.path }.take(PICKER_ENTRIES).map { entry ->
        entry.copy(name = entry.name.kept(), reason = entry.reason.kept(), model = entry.model?.let { it.copy(name = it.name.kept(), id = it.id.kept()) })
    },
)

/** The device is being asked: what is typed from now on is the person's, newer than the answer. */
fun PickerState.asking(): PickerState = copy(loading = true, error = null, edited = false)

/**
 * The device's answer. A listing is shown ([bounded]) and the field follows it -- unless the
 * person wrote in the field while the device was answering, or the listing is the places,
 * which have no path: then the field keeps what it has. A listing may come with a sentence
 * (part of it could not be read): it is shown with it. No listing -- the device is away, too
 * old, silent or said no -- leaves the last listing and the typed path where they were, with
 * the server's sentence ([fallback] where it sent none).
 */
fun PickerState.took(answer: DeviceDirectory, fallback: String = ""): PickerState = when {
    answer.listed -> copy(
        listing = answer.bounded(),
        loading = false,
        failed = false,
        error = answer.error.kept().ifBlank { null },
        typed = if (edited) typed else answer.path.ifEmpty { typed },
    )

    else -> copy(loading = false, failed = true, error = answer.error.kept().ifBlank { fallback })
}

/** The device could not be asked at all ([said]: why): as an answer without a listing. */
fun PickerState.unanswered(said: String): PickerState = copy(loading = false, failed = true, error = said.kept())

/**
 * Where "up" leads from the listing shown: the parent, the places (`""`) from a directory that
 * has none, or null at the places themselves and where nothing is listed -- there the picker
 * closes instead.
 */
val PickerState.up: String?
    get() = listing?.takeIf { !it.places }?.parent

/**
 * Where Back leads: up a level, or null to close -- at the top, and after a question that got
 * no listing (the device went away mid-walk), where "up" would only ask it again for ever.
 */
val PickerState.back: String?
    get() = if (failed) null else up

/**
 * Whether the path in the field may be chosen: something is written, and it is not the listed
 * directory the device said it would open no session in. A path the device was never asked
 * about may be chosen: the instance checks it when it is used.
 */
val PickerState.canChoose: Boolean
    get() = typed.isNotBlank() && !refusedHere

/** The field holds the listed directory, and the device will not open a session there. */
val PickerState.refusedHere: Boolean
    get() = listing?.let { !it.places && !it.accepted && it.path == typed.trim() } == true

interface PickerActions {
    fun open(path: String) {}
    fun typed(text: String) {}
    fun choose() {}
    fun back() {}
    fun close() {}
}

/**
 * The directory picker over whatever asked for it: [load] asks the device for a path (`""`:
 * its places), [onPick] is given the chosen path. It opens at [initial], or at the places.
 * Back goes up a level, and closes at the top.
 */
@Composable
fun DirectoryPicker(title: String, initial: String, load: suspend (String) -> DeviceDirectory, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(PickerState(typed = initial.trim(), loading = true)) }
    var reading by remember { mutableStateOf<Job?>(null) }
    val fallback = stringResource(R.string.directory_no_listing)
    val read: (String) -> Unit = { path ->
        reading?.cancel()
        state = state.asking()
        reading = scope.launch {
            try {
                val answer = load(path)
                // Applied to the state as it is now: `took` leaves what was typed meanwhile.
                state = state.took(answer, fallback)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.unanswered(context.explain(e))
            }
        }
    }
    LaunchedEffect(Unit) { read(initial.trim()) }
    val actions = object : PickerActions {
        override fun open(path: String) = read(path)

        override fun typed(text: String) {
            state = state.copy(typed = text, edited = true)
        }

        override fun choose() {
            if (state.canChoose) onPick(state.typed.trim())
        }

        override fun back() {
            val up = state.back
            if (up == null) onDismiss() else read(up)
        }

        override fun close() = onDismiss()
    }
    Dialog(onDismissRequest = actions::back, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        DirectoryPickerContent(title, state, actions)
    }
}

/**
 * The picker, drawn from its state: where the walk is, the model last used there, the
 * subdirectories (a row is 56 dp; its name gives, the model beside it keeps to a third), and at
 * the foot the path as text with Choose -- which is all there is when the device lists nothing.
 */
@Composable
fun DirectoryPickerContent(title: String, state: PickerState, actions: PickerActions, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val listing = state.listing
    Surface(color = palette.bg, contentColor = palette.ink, modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (state.loading) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(horizontal = 12.dp).size(20.dp))
                IconButton(onClick = actions::close) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close)) }
            }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    listing?.path?.ifEmpty { null } ?: stringResource(R.string.directory_places),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = if (listing?.places == false) FontFamily.Monospace else null,
                    color = palette.inkMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                listing?.model?.let {
                    Text(
                        stringResource(R.string.directory_last_used, it.label),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkMuted,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (listing != null && !listing.places && !listing.accepted) {
                    Text(
                        listing.reason.ifBlank { stringResource(R.string.directory_refused) },
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.warning,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                state.error?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.danger, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)) {
                // The way out of a directory, and -- where nothing is listed -- the way to the places.
                if (listing?.places != true) {
                    item(key = "up") {
                        val places = listing == null || listing.parent.isEmpty()
                        EntryRow(
                            name = stringResource(if (places) R.string.directory_all_places else R.string.directory_up),
                            icon = { Icon(Icons.Filled.ArrowUpward, contentDescription = null, tint = palette.inkMuted) },
                            onClick = { actions.open(listing?.parent.orEmpty()) },
                        )
                    }
                }
                if (listing != null && listing.entries.isEmpty() && !state.loading) {
                    item(key = "empty") {
                        Text(
                            stringResource(if (listing.places) R.string.directory_no_places else R.string.directory_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.inkMuted,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
                items(listing?.entries.orEmpty(), key = { "d:" + it.path }) { entry -> Entry(entry, onOpen = { actions.open(entry.path) }) }
            }
            HorizontalDivider(color = palette.border)
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.typed,
                    onValueChange = actions::typed,
                    singleLine = true,
                    label = { Text(stringResource(R.string.directory_path)) },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = actions::close, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) }
                    Button(onClick = actions::choose, enabled = state.canChoose, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.directory_choose))
                    }
                }
            }
        }
    }
}

/** A subdirectory: its name, why no session opens in it where none does, and the model last used in it. */
@Composable
private fun Entry(entry: DirectoryEntry, onOpen: () -> Unit) {
    val palette = LocalPalette.current
    EntryRow(
        name = entry.name.ifBlank { entry.path },
        note = if (entry.accepted) null else entry.reason.ifBlank { stringResource(R.string.directory_not_trusted) },
        model = entry.model?.label,
        icon = {
            Icon(
                if (entry.accepted) Icons.Filled.Folder else Icons.Filled.FolderOff,
                contentDescription = null,
                tint = if (entry.accepted) palette.accent else palette.inkFaint,
            )
        },
        onClick = onOpen,
    )
}

@Composable
private fun EntryRow(name: String, icon: @Composable () -> Unit, onClick: () -> Unit, note: String? = null, model: String? = null) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = palette.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        model?.let {
            Spacer(Modifier.width(12.dp))
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = palette.inkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp),
            )
        }
    }
}
