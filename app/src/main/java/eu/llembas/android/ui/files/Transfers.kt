package eu.llembas.android.ui.files

import android.app.Activity
import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.content.FileProvider
import eu.llembas.android.api.ApiClient
import eu.llembas.android.api.ApiException
import eu.llembas.android.api.FileKinds
import eu.llembas.android.api.FileListing
import eu.llembas.android.api.SNIFF_BYTES
import eu.llembas.android.api.openType
import eu.llembas.android.share.SharedSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * The copies of the account's files this phone holds for a moment, all in the app's private
 * cache and nowhere else: [viewing] for what a viewer needs as a file (a PDF's pages, a
 * picture's pixels), deleted when the viewer closes; [shared] for what another app is handed
 * through the FileProvider (Open with, Share). A shared copy cannot be deleted the moment it is
 * handed over, nor when the screen that made it closes or the app starts again: the other app
 * may still be reading it (a mail being written). So it goes by its age alone, [SHARED_KEPT_MS]
 * after it was written -- looked at when the app starts, when a screen with transfers closes and
 * before a new copy is made -- and all of them go at sign-out and on a switch of instance. No
 * storage permission is involved: what the person keeps, they save through the system's own
 * "Save to" page.
 */
object FileCache {
    private const val VIEWING = "viewing"
    private const val SHARED = "shared"

    /** How long a copy another app was handed is kept: an hour. */
    const val SHARED_KEPT_MS = 60 * 60 * 1000L

    private fun dir(context: Context, name: String): File = File(context.cacheDir, name).apply { mkdirs() }

    /** A new, empty place for one viewer's copy. */
    fun viewing(context: Context, name: String): File = File(dir(context, VIEWING), UUID.randomUUID().toString() + "-" + FileKinds.safeName(name, 80))

    /** A new place for a copy another app will read, under its own name in a folder of its own. */
    fun shared(context: Context, name: String): File =
        File(File(dir(context, SHARED), UUID.randomUUID().toString()).apply { mkdirs() }, FileKinds.safeName(name))

    /** Every copy, gone: at sign-out and on a switch of instance. */
    fun clear(context: Context) {
        for (name in listOf(VIEWING, SHARED)) runCatching { File(context.cacheDir, name).deleteRecursively() }
    }

    /** When the process starts: no viewer is open, so their copies go; the shared ones by their age. */
    fun atStart(context: Context) {
        runCatching { File(context.cacheDir, VIEWING).deleteRecursively() }
        sweepShared(context)
    }

    /** The shared copies older than [olderThanMs], and no other. */
    fun sweepShared(context: Context, olderThanMs: Long = SHARED_KEPT_MS) = sweep(File(context.cacheDir, SHARED), System.currentTimeMillis(), olderThanMs)

    /**
     * Deletes what is directly in [dir] and was last written more than [olderThanMs] before
     * [now]. A copy's folder is as old as the newest thing in it: a long download's folder was
     * made when it began.
     */
    fun sweep(dir: File, now: Long, olderThanMs: Long) {
        dir.listFiles()?.forEach { entry ->
            val written = maxOf(entry.lastModified(), entry.listFiles()?.maxOfOrNull { it.lastModified() } ?: 0L)
            if (now - written > olderThanMs) runCatching { entry.deleteRecursively() }
        }
    }

    /** The address another app is given for a [shared] copy: the app's FileProvider, read-only, for that one grant. */
    fun uri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
}

/** "Save to": the system's page for where a file goes (the Storage Access Framework), by a suggested name and type. */
class CreateFile : ActivityResultContract<CreateFile.Ask, Uri?>() {
    data class Ask(val name: String, val mime: String)

    override fun createIntent(context: Context, input: Ask): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType(input.mime.ifBlank { "application/octet-stream" })
        .putExtra(Intent.EXTRA_TITLE, input.name)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
}

/** A file on the phone, as the picker or another app's share named it. */
data class LocalFile(val uri: Uri, val name: String, val size: Long, val mime: String)

fun ContentResolver.describe(uri: Uri): LocalFile {
    var name: String? = null
    var size = -1L
    runCatching {
        query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
            }
        }
    }
    val said = name?.substringAfterLast('/')?.ifBlank { null } ?: uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "upload"
    return LocalFile(uri, said, size, getType(uri) ?: "application/octet-stream")
}

/**
 * A request body that reads [open]'s stream as it is sent: the file is never in memory whole.
 * [length] is its size when known (-1: sent chunked). [progress] hears how many bytes went.
 */
class StreamBody(
    private val type: MediaType?,
    private val length: Long,
    private val progress: (Long) -> Unit = {},
    private val open: () -> java.io.InputStream?,
) : RequestBody() {
    override fun contentType(): MediaType? = type

    override fun contentLength(): Long = length

    /** The stream is read once: a failed upload is said, not silently sent again from a stream that may not open twice. */
    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        val stream = open() ?: throw IOException("The file could not be opened.")
        stream.source().use { source ->
            var sent = 0L
            while (true) {
                val n = source.read(sink.buffer, 64 * 1024L)
                if (n < 0) break
                sent += n
                sink.emitCompleteSegments()
                progress(sent)
            }
        }
    }
}

/**
 * Upload one file of the phone into [dir], streamed from its content address -- opened through
 * [SharedSource], so an address that leads to this app's own files is refused whoever named it.
 */
suspend fun ApiClient.uploadFrom(context: Context, file: LocalFile, dir: String, progress: (Long) -> Unit = {}): FileListing =
    uploadFile(dir, file.name, StreamBody(file.mime.toMediaTypeOrNull(), file.size, progress) { SharedSource.open(context, file.uri) })

/**
 * Download what [request] answers into the document [to] the person chose. A download that
 * fails or is given up leaves no half file behind where the provider lets it be removed.
 */
suspend fun ApiClient.downloadTo(context: Context, request: okhttp3.Request, to: Uri, progress: (Long, Long) -> Unit = { _, _ -> }) {
    try {
        withContext(Dispatchers.IO) {
            val out = context.contentResolver.openOutputStream(to, "w") ?: throw IOException("The file could not be written.")
            out.use { download(request, it, progress = progress) }
        }
    } catch (e: Throwable) {
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(context.contentResolver, to) } }
        throw e
    }
}

/** A copy of what [request] answers in [file] (the private cache), at most [most] bytes. */
suspend fun ApiClient.copyInto(request: okhttp3.Request, file: File, most: Long = Long.MAX_VALUE, progress: (Long, Long) -> Unit = { _, _ -> }): File {
    try {
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            file.outputStream().use { download(request, it, most, progress) }
        }
    } catch (e: Throwable) {
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { runCatching { file.delete() } }
        throw e
    }
    return file
}

/** Hand [files] (cache copies) to another app: "Open with" for one, Share for one or several. Read-only, for this one grant. */
object Handover {
    /**
     * "Open with" for the copy [file]. What it is offered as is read from the copy itself
     * ([openType]: its first bytes, else its name's extension) -- never the type the instance
     * said, which for a file an agent showed is one more thing a model had a hand in.
     */
    fun view(context: Context, file: File): Intent {
        val uri = FileCache.uri(context, file)
        // A copy that cannot be read again is offered by its name alone.
        val head = try {
            file.inputStream().use { stream ->
                val bytes = ByteArray(SNIFF_BYTES)
                bytes.copyOf(stream.read(bytes).coerceAtLeast(0))
            }
        } catch (e: IOException) {
            ByteArray(0)
        }
        val named = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, openType(head, named)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(intent, null)
    }

    fun share(context: Context, files: List<File>, mime: String): Intent {
        val uris = files.map { FileCache.uri(context, it) }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.setType(mime.ifBlank { "*/*" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The chooser's own preview reads the address too: it is told through the clip.
        intent.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        return Intent.createChooser(intent, null)
    }
}

/** Whether a failure says the server has no such route at all (an older instance), rather than refusing. */
fun noSuchRoute(e: Throwable): Boolean = e is ApiException && (e.status == 405 || (e.status == 404 && e.message == "Not Found"))

/** [report], heard only every quarter megabyte and at the end: a transfer does not redraw the screen for every packet. */
fun stepped(report: (Long, Long) -> Unit): (Long, Long) -> Unit {
    var last = 0L
    return { done, total ->
        if (done - last >= 256 * 1024 || done == total) {
            last = done
            report(done, total)
        }
    }
}
