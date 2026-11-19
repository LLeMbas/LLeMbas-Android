package eu.llembas.android.ui.files

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import eu.llembas.android.R
import eu.llembas.android.api.ApiClient
import eu.llembas.android.api.ApiException
import eu.llembas.android.api.FileEntry
import eu.llembas.android.api.FileKinds
import eu.llembas.android.api.Raw
import eu.llembas.android.lembas
import eu.llembas.android.share.SharedRefused
import eu.llembas.android.ui.explain
import eu.llembas.android.ui.theme.LocalPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Something on its way to or from the instance: what it is, and how far ([total] -1: not known).
 * [waiting]: its next request has not started, because the app is not in front or the phone is
 * locked ([eu.llembas.android.InstanceGate]); it goes on by itself.
 */
data class Transfer(val text: String, val done: Long = 0, val total: Long = -1, val waiting: Boolean = false)

/** A transfer under way, what it ended in, and the other app to open once a copy is ready. */
data class TransferState(val running: Transfer? = null, val error: String? = null, val notice: String? = null, val launch: Intent? = null)

/** A failure of the file manager in words: an instance without these routes says so, the rest as everywhere. */
fun Context.filesSay(e: Throwable): String = when {
    noSuchRoute(e) -> getString(R.string.viewer_unavailable)
    e is SharedRefused -> getString(R.string.share_refused)
    e is ApiException && e.message.isNullOrBlank() -> getString(R.string.files_not_shown)
    else -> explain(e)
}

/**
 * Files leaving the instance for the phone: a download into a document the person chose, and a
 * copy in the private cache for another app (Open with, Share). One at a time, with its
 * progress, and it can be given up. It outlives a turn of the phone. Each request asks the gate
 * first ([turn]): none starts while the phone is locked or the app is not in front, and the one
 * under way then is let finish.
 */
class TransferViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(TransferState())
    val state = _state.asStateFlow()
    private var job: Job? = null
    private val app: Application get() = getApplication()

    /** One file's bytes, or with [zip] several files and folders as one zip, into the document [to]. */
    fun download(paths: List<String>, zip: Boolean, to: Uri) = start { api, progress ->
        turn()
        api.downloadTo(app, if (zip) api.zipFiles(paths) else api.rawFile(paths.first()), to, progress)
        _state.update { it.copy(notice = app.getString(R.string.files_downloaded)) }
    }

    /** One thing's bytes -- a version of a file, a generated file by its id, an attachment -- into the document [to]. */
    fun download(what: Raw, to: Uri) = start { api, progress ->
        turn()
        api.downloadTo(app, api.raw(what), to, progress)
        _state.update { it.copy(notice = app.getString(R.string.files_downloaded)) }
    }

    fun openWith(path: String, name: String, mime: String) = openWith(Raw.Path(path), name, mime)

    fun openWith(what: Raw, name: String, mime: String) = start { api, progress ->
        FileCache.sweepShared(app)
        turn()
        val copy = api.copyInto(api.raw(what), FileCache.shared(app, name), progress = progress)
        _state.update { it.copy(launch = Handover.view(app, copy, mime)) }
    }

    /** One thing that is not named by a path (a generated file by its id), shared as itself. */
    fun share(what: Raw, name: String, mime: String) = start { api, progress ->
        FileCache.sweepShared(app)
        turn()
        val copy = api.copyInto(api.raw(what), FileCache.shared(app, name), progress = progress)
        _state.update { it.copy(launch = Handover.share(app, listOf(copy), mime.ifBlank { "*/*" })) }
    }

    /** A few files go as themselves; many, or any folder, as one zip the instance makes. */
    fun share(entries: List<FileEntry>) = start { api, progress ->
        if (entries.isEmpty()) return@start
        FileCache.sweepShared(app)
        val plain = entries.none { it.isFolder } && entries.size <= SHARE_MOST
        val copies = if (plain) {
            entries.map {
                turn()
                api.copyInto(api.rawFile(it.path), FileCache.shared(app, it.name), progress = progress)
            }
        } else {
            turn()
            listOf(api.copyInto(api.zipFiles(entries.map { it.path }), FileCache.shared(app, zipName(entries.map { it.path })), progress = progress))
        }
        val mime = if (plain) entries.map { it.mime }.distinct().singleOrNull()?.ifBlank { null } ?: "*/*" else "application/zip"
        _state.update { it.copy(launch = Handover.share(app, copies, mime)) }
    }

    fun launched() = _state.update { it.copy(launch = null) }

    fun say(error: String) = _state.update { it.copy(error = error) }

    fun clear() = _state.update { it.copy(error = null, notice = null) }

    fun cancel() {
        job?.cancel()
    }

    /** The gate, before a request: while it is shut the dialog says the transfer is waiting. */
    private suspend fun turn() = app.lembas.gate.pass { waiting -> _state.update { it.copy(running = it.running?.copy(waiting = waiting)) } }

    private fun start(block: suspend (ApiClient, (Long, Long) -> Unit) -> Unit) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _state.update { it.copy(running = Transfer(app.getString(R.string.files_downloading)), error = null, notice = null) }
            try {
                val api = app.lembas.instances.client() ?: throw ApiException(401, app.getString(R.string.assist_signed_out))
                block(api, stepped { done, total -> _state.update { it.copy(running = it.running?.copy(done = done, total = total)) } })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = app.filesSay(e)) }
            } finally {
                _state.update { it.copy(running = null) }
            }
        }
    }

    /** The copies another app was handed stay while it may be reading them: only the old ones go. */
    override fun onCleared() {
        FileCache.sweepShared(app)
    }

    companion object {
        /** More files than this are shared as one zip. */
        const val SHARE_MOST = 10

        /** What a zip of [paths] is called: the one thing's name, else the folder they are in. */
        fun zipName(paths: List<String>): String {
            val one = paths.singleOrNull()?.let { FileKinds.name(it) }
            val folder = paths.firstOrNull()?.let { FileKinds.name(FileKinds.parent(it)) }?.takeIf { it != "/" }
            return (one ?: folder ?: "files").removeSuffix(".zip") + ".zip"
        }
    }
}

/**
 * "Save to" for one thing: the system's page for where it goes, and then the download into the
 * document chosen there. What is to be saved is kept while that page is open.
 */
@Composable
fun rememberSaver(transfers: TransferViewModel): (what: Raw, name: String, mime: String) -> Unit {
    var pending by remember { mutableStateOf<Raw?>(null) }
    val page = rememberLauncherForActivityResult(CreateFile()) { to ->
        val what = pending
        pending = null
        if (to != null && what != null) transfers.download(what, to)
    }
    return { what, name, mime ->
        pending = what
        page.launch(CreateFile.Ask(FileKinds.safeName(name), mime))
    }
}

/**
 * What a screen shows of its transfers: the one under way with Cancel, the other app once a
 * copy is ready, a failure in a dialog (it is a sentence to read), and a short word when one
 * is done -- which names no file.
 */
@Composable
fun TransferHost(model: TransferViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.launch) {
        val intent = state.launch ?: return@LaunchedEffect
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            model.say(context.getString(R.string.files_no_app))
        }
        model.launched()
    }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            model.clear()
        }
    }
    state.running?.let { TransferDialog(it, onCancel = model::cancel) }
    state.error?.let { SaidDialog(it, onClose = model::clear) }
}

/** A transfer under way: what, how far, and Cancel. Drawn from [transfer] alone. */
@Composable
fun TransferDialog(transfer: Transfer, onCancel: () -> Unit) {
    val context = LocalContext.current
    val palette = LocalPalette.current
    AlertDialog(
        onDismissRequest = {},
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(transfer.text, style = MaterialTheme.typography.bodyLarge)
                if (transfer.waiting) Text(stringResource(R.string.files_waiting), style = MaterialTheme.typography.bodyMedium, color = palette.inkMuted)
                if (transfer.total > 0) {
                    LinearProgressIndicator(progress = { (transfer.done.toFloat() / transfer.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (transfer.done > 0) {
                    val done = Formatter.formatShortFileSize(context, transfer.done)
                    val line = if (transfer.total > 0) done + " / " + Formatter.formatShortFileSize(context, transfer.total) else done
                    Text(line, style = MaterialTheme.typography.bodySmall, color = palette.inkMuted)
                }
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A sentence the person has to read (a refusal, a failure), with one way on. */
@Composable
fun SaidDialog(text: String, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        text = { Text(text, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.close)) } },
    )
}
