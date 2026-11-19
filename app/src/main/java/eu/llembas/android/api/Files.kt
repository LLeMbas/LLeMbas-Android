package eu.llembas.android.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException

// The person's files as a file manager needs them: the shapes of the trash, the versions and a
// batch (`contract/files_*.json`, `file_versions.json`, `file_save_conflict.json`), and what the
// app decides from a file's kind without a phone: which viewer, a table's rows, a notebook's cells.

/** One thing in the trash: restored or removed for good by its [id]. */
@Serializable
data class TrashItem(
    val id: String = "",
    val name: String = "",
    val path: String = "",
    val kind: String = "file",
    val size: Long = 0,
    @SerialName("trashed_at") val trashedAt: String? = null,
)

/** `GET /files/trash`, and what is left after a restore ([restored]: where it went back to) or a removal. */
@Serializable
data class TrashList(val items: List<TrashItem> = emptyList(), val restored: String? = null, val removed: Int = 0)

/** One version of a file, newest first in [FileVersions]; [current] is what the file holds now. */
@Serializable
data class FileVersion(
    val id: String = "",
    val sha: String = "",
    val size: Long = 0,
    val at: String? = null,
    val by: String? = null,
    @SerialName("chat_id") val chatId: String? = null,
    val current: Boolean = false,
)

@Serializable
data class FileVersions(val path: String = "", val sha: String = "", val versions: List<FileVersion> = emptyList())

/** One path of a batch: done ([ok], and where it is now) or refused, in the server's sentence. */
@Serializable
data class BatchResult(val path: String = "", val ok: Boolean = false, val to: String? = null, val error: String? = null)

/** `POST /files/batch`: never all-or-nothing, so [failed] is read even from a 200. */
@Serializable
data class BatchAnswer(val op: String = "", val to: String? = null, val results: List<BatchResult> = emptyList(), val done: Int = 0, val failed: Int = 0) {
    /** The server's sentences for what was refused, each once, at most [most]. */
    fun refusals(most: Int = 3): List<String> = results.filter { !it.ok }.mapNotNull { it.error?.ifBlank { null } }.distinct().take(most)
}

/**
 * A save the server refused because the file changed since it was opened (409, `conflict: true`):
 * its sentence, and the [sha] the file holds now. Nothing was written.
 */
class SaveConflict(message: String, val sha: String) : IOException(message)

/** The viewer a file gets, by the server's word for what it is. */
enum class FileView {
    /** Markdown, code, text, and the source of an HTML page or an SVG: drawn as text, editable. */
    Text,
    Image,
    Pdf,
    Audio,
    Video,
    Table,
    Notebook,

    /** Nothing to draw here: what the file is, and Download, Open with, Share. */
    Details,
}

object FileKinds {
    /** The kinds whose text the server hands over and the app may save back. */
    val TEXT = setOf("markdown", "code", "text", "html", "svg")

    /**
     * The viewer for a file of [kind]. A server that does not say the kind (null) is read as
     * before: as text, and the content answer says when it is not. An SVG is its source -- the
     * app has no WebView and draws nothing a file could script.
     */
    fun viewer(kind: String?): FileView = when (kind) {
        null, "", in TEXT -> FileView.Text
        "image" -> FileView.Image
        "pdf" -> FileView.Pdf
        "audio" -> FileView.Audio
        "video" -> FileView.Video
        "table" -> FileView.Table
        "notebook" -> FileView.Notebook
        else -> FileView.Details
    }

    /** Whether the app offers to edit it: a text kind (a table too, as its text) the person may change. */
    fun editable(kind: String?, writable: Boolean?, withheld: Boolean): Boolean = writable == true && !withheld && (kind in TEXT || kind == "table")

    /** Why [name] cannot be a file's or a folder's name, as the server would refuse it; false for a good one. */
    fun badName(name: String): Boolean = name.isBlank() || "/" in name || name.trim() in setOf(".", "..")

    /** [name] inside the directory [dir]. */
    fun child(dir: String, name: String): String = dir.trimEnd('/') + "/" + name.trim()

    /** The directory a path is in; `/` for a path at the top. */
    fun parent(path: String): String = path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }

    /** The last name of a path. */
    fun name(path: String): String = path.trimEnd('/').substringAfterLast('/').ifBlank { path }

    /**
     * A name another app or a file system may be given: no separators, no control characters,
     * never empty, and at most [most] bytes as UTF-8 -- a file system counts a name in bytes
     * (255 of them), and a name in another script is three bytes a letter. A longer name loses
     * the end of its stem and keeps its extension, which is what says how to open it.
     */
    fun safeName(name: String, most: Int = NAME_MAX_BYTES): String {
        val kept = name.map { if (it == '/' || it == '\\' || it.isISOControl()) '_' else it }.joinToString("").trim().trim('.').ifBlank { "file" }
        if (utf8(kept) <= most) return kept
        val dot = kept.lastIndexOf('.')
        // An extension is short; a last dot far from the end is a sentence's, and the name is cut as a whole.
        val ext = if (dot > 0 && utf8(kept.substring(dot)) <= minOf(EXTENSION_MAX_BYTES, most / 2)) kept.substring(dot) else ""
        val stem = kept.substring(0, kept.length - ext.length)
        val room = most - utf8(ext)
        val cut = StringBuilder()
        var used = 0
        var i = 0
        while (i < stem.length) {
            // By code point: half of a pair is not a character.
            val point = stem.codePointAt(i)
            val bytes = utf8(String(Character.toChars(point)))
            if (used + bytes > room) break
            cut.appendCodePoint(point)
            used += bytes
            i += Character.charCount(point)
        }
        return cut.toString().trimEnd().trimEnd('.').ifBlank { "file" } + ext
    }

    private fun utf8(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    /** The longest name [safeName] gives, in bytes. */
    const val NAME_MAX_BYTES = 200
    private const val EXTENSION_MAX_BYTES = 24
}

/** One thing an archive holds: its [name] is the archive's own text (a path inside it), [size] null for a folder and where the archive does not say. */
@Serializable
data class ArchiveEntry(val name: String = "", val size: Long? = null, val dir: Boolean = false)

/**
 * What is inside an archive, listed by the instance and never unpacked: at most 2000 [entries];
 * [total] how many it holds where it says; [truncated] when there are more than are listed.
 * [problem] is null, or why there is no listing (`unreadable`, `unsupported`, `busy`), with the
 * instance's sentence for it in [message].
 */
@Serializable
data class ArchiveListing(
    val entries: List<ArchiveEntry> = emptyList(),
    val total: Int? = null,
    val truncated: Boolean = false,
    val problem: String? = null,
    val message: String = "",
)

/**
 * Where a file known by its id is now (`GET /files/node?id=`): [parent] is the directory to
 * list, [path] the file. [trashed]: it is in the trash, and [path] is where it lay.
 */
@Serializable
data class FileNode(
    val id: String = "",
    val path: String = "",
    val name: String = "",
    val parent: String = "",
    val kind: String = "",
    @SerialName("file_kind") val fileKind: String? = null,
    val trashed: Boolean = false,
)

/** Where "Open in Files" leads for a file a chat names by its id. */
sealed interface Whereabouts {
    /** The directory to open: where the file is now. */
    data class Folder(val path: String) : Whereabouts

    /** It is in the trash: no directory holds it. */
    data object Trashed : Whereabouts
}

/**
 * Where to go for a file last known at [written], from what the instance says of its node
 * ([node]; null from an instance that cannot say): the directory it is in now -- it may have
 * been renamed or moved -- else the one it was written to.
 */
fun whereabouts(node: FileNode?, written: String): Whereabouts = when {
    node == null -> Whereabouts.Folder(FileKinds.parent(written))
    node.trashed -> Whereabouts.Trashed
    else -> Whereabouts.Folder(node.parent.ifBlank { FileKinds.parent(node.path.ifBlank { written }) })
}

/** A table's first rows and columns; [cut] when the file has more than is laid out. */
data class Table(val rows: List<List<String>>, val columns: Int, val cut: Boolean)

/**
 * A CSV or TSV file as rows of cells: quoted cells, doubled quotes and line breaks inside quotes
 * as RFC 4180 has them. At most [maxRows] rows of at most [maxColumns] cells are kept, each cell
 * at most [maxCell] characters -- a table is for looking at, the text has all of it. [partial]:
 * the text is only the file's beginning, so its last line may be half a row and is left out.
 */
fun parseTable(text: String, delimiter: Char, maxRows: Int = 500, maxColumns: Int = 40, maxCell: Int = 200, partial: Boolean = false): Table {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var cut = partial
    var widest = 0
    var i = 0

    fun endCell() {
        if (row.size < maxColumns) row += cell.toString().take(maxCell) else cut = true
        cell.setLength(0)
    }

    fun endRow(): Boolean {
        endCell()
        if (row.size > 1 || row[0].isNotEmpty()) {
            rows += row
            widest = maxOf(widest, row.size)
        }
        row = mutableListOf()
        return rows.size < maxRows
    }

    var open = true
    while (i < text.length) {
        val c = text[i]
        when {
            quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                cell.append('"')
                i++
            }

            c == '"' && (quoted || cell.isEmpty()) -> quoted = !quoted

            !quoted && c == delimiter -> endCell()

            !quoted && (c == '\n' || c == '\r') -> {
                if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                if (!endRow()) {
                    cut = cut || i + 1 < text.length
                    open = false
                    break
                }
            }

            else -> if (cell.length <= maxCell) cell.append(c)
        }
        i++
    }
    // What is left after the last line break: a whole last row, unless the text was cut short.
    if (open && !partial && (cell.isNotEmpty() || row.isNotEmpty())) endRow()
    return Table(rows, widest, cut)
}

/** The delimiter a table file is cut by: a tab for `.tsv` (or a first line with tabs and no commas), else a comma. */
fun tableDelimiter(name: String, text: String): Char {
    if (name.endsWith(".tsv", ignoreCase = true) || name.endsWith(".tab", ignoreCase = true)) return '\t'
    val first = text.lineSequence().firstOrNull().orEmpty()
    return if ('\t' in first && ',' !in first) {
        '\t'
    } else if (';' in first && ',' !in first) {
        ';'
    } else {
        ','
    }
}

/** One cell of a notebook, as text: its [source], and what it printed ([output], plain text only). */
data class NotebookCell(val kind: String, val source: String, val output: String = "", val count: Int? = null, val cut: Boolean = false)

/** A notebook's first cells; [more]: how many are not shown. Null from [parseNotebook]: not a notebook. */
data class Notebook(val cells: List<NotebookCell>, val more: Int = 0, val language: String = "")

/**
 * A Jupyter notebook, read-only, as text and nothing else: a cell's source, and of its outputs
 * only the plain text (a stream, `text/plain`, an error's name and value). HTML, pictures and
 * scripts in an output are not drawn and not kept. Null for a file that is not a notebook.
 */
fun parseNotebook(text: String, maxCells: Int = 200, maxChars: Int = 20_000): Notebook? {
    val root = runCatching { LembasJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
    val cells = root["cells"] as? JsonArray ?: return null

    fun words(of: JsonElement?): String = when (of) {
        is JsonPrimitive -> if (of.isString) of.content else ""
        is JsonArray -> of.joinToString("") { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content.orEmpty() }
        else -> ""
    }

    fun printed(cell: JsonObject): String = (cell["outputs"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.joinToString("") { out ->
        when ((out["output_type"] as? JsonPrimitive)?.content) {
            "stream" -> words(out["text"])
            "error" -> listOf(words(out["ename"]), words(out["evalue"])).filter { it.isNotBlank() }.joinToString(": ") + "\n"
            else -> words((out["data"] as? JsonObject)?.get("text/plain")).let { if (it.isEmpty() || it.endsWith("\n")) it else it + "\n" }
        }
    }.trimEnd('\n')

    val language = (((root["metadata"] as? JsonObject)?.get("kernelspec") as? JsonObject)?.get("language") as? JsonPrimitive)?.content
        ?: ((((root["metadata"] as? JsonObject)?.get("language_info") as? JsonObject)?.get("name")) as? JsonPrimitive)?.content
    val read = cells.mapNotNull { it as? JsonObject }.take(maxCells).map { cell ->
        val source = words(cell["source"])
        val output = printed(cell)
        NotebookCell(
            kind = (cell["cell_type"] as? JsonPrimitive)?.content ?: "code",
            source = source.take(maxChars),
            output = output.take(maxChars),
            count = (cell["execution_count"] as? JsonPrimitive)?.content?.toIntOrNull(),
            cut = source.length > maxChars || output.length > maxChars,
        )
    }
    return Notebook(read, more = (cells.size - read.size).coerceAtLeast(0), language = language.orEmpty())
}

/**
 * How much smaller a picture of [width] × [height] is decoded (a power of two, `inSampleSize`):
 * so its longer edge is at most about [edge] and it holds at most [pixels] pixels, whatever the
 * file claims. A picture of 30 000 × 30 000 is then a few megabytes in memory, not gigabytes.
 */
fun sampleFor(width: Int, height: Int, edge: Int = 2560, pixels: Long = 8_000_000): Int {
    if (width <= 0 || height <= 0) return 1
    var sample = 1
    while (maxOf(width, height) / sample > edge || (width.toLong() / sample) * (height.toLong() / sample) > pixels) sample *= 2
    return sample
}

/**
 * A file read a piece at a time, for a player that seeks: [fetch] gets the bytes `[from, to]`
 * (a `Range` request with the token in its header), a few blocks are kept, and a read is
 * answered from the block its position is in. [length] is the file's size as the server said it.
 */
class RangeReader(private val length: Long, private val block: Int = BLOCK, private val keep: Int = 6, private val fetch: (from: Long, to: Long) -> ByteArray) {
    private val blocks = object : LinkedHashMap<Long, ByteArray>(keep, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean = size > keep
    }

    /** Up to [size] bytes from [position] into [buffer] at [offset]; -1 at or past the end. */
    @Synchronized
    fun read(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0 || position >= length) return -1
        if (size <= 0) return 0
        val index = position / block
        val start = index * block
        val bytes = blocks[index] ?: fetch(start, minOf(start + block, length) - 1).also { blocks[index] = it }
        val within = (position - start).toInt()
        if (within >= bytes.size) return -1
        val n = minOf(size, bytes.size - within)
        System.arraycopy(bytes, within, buffer, offset, n)
        return n
    }

    @Synchronized
    fun close() = blocks.clear()

    companion object {
        const val BLOCK = 512 * 1024
    }
}
