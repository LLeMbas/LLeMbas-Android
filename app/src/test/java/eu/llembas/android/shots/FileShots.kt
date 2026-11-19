package eu.llembas.android.shots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import eu.llembas.android.api.FileEntry
import eu.llembas.android.api.FileVersion
import eu.llembas.android.api.Notebook
import eu.llembas.android.api.NotebookCell
import eu.llembas.android.api.TrashItem
import eu.llembas.android.api.parseTable
import eu.llembas.android.ui.files.FilesActions
import eu.llembas.android.ui.files.FilesContent
import eu.llembas.android.ui.files.FilesState
import eu.llembas.android.ui.files.FolderPickContent
import eu.llembas.android.ui.files.FolderPickState
import eu.llembas.android.ui.files.TrashActions
import eu.llembas.android.ui.files.TrashContent
import eu.llembas.android.ui.files.TrashState
import eu.llembas.android.ui.files.VersionsContent
import eu.llembas.android.ui.files.VersionsState
import eu.llembas.android.ui.viewer.DetailsBody
import eu.llembas.android.ui.viewer.EditorBody
import eu.llembas.android.ui.viewer.FileActions
import eu.llembas.android.ui.viewer.NotebookBody
import eu.llembas.android.ui.viewer.PlayerControls
import eu.llembas.android.ui.viewer.PlayerUi
import eu.llembas.android.ui.viewer.TableBody
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The file manager's pages and the viewers' bodies, each drawn from made-up state. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "xhdpi")
class FileShots {
    private val home = "/home/frodo"

    private fun file(name: String, kind: String, mime: String, size: Long) =
        FileEntry(name = name, path = "$home/$name", kind = "file", fileKind = kind, size = size, mime = mime)

    private val entries = listOf(
        FileEntry(name = "recipes", path = "$home/recipes", kind = "dir"),
        FileEntry(name = "The Fellowship's shared provisions", path = "$home/shared", kind = "mount"),
        file("lembas.md", "markdown", "text/markdown", 2_048),
        file("map-of-the-road.png", "image", "image/png", 3_400_000),
        file("provisions-for-a-very-long-journey-there-and-back-again.pdf", "pdf", "application/pdf", 780_000),
        file("walking-song.mp3", "audio", "audio/mpeg", 4_100_000),
        file("the-gates.mp4", "video", "video/mp4", 91_000_000),
        file("stores.csv", "table", "text/csv", 5_120),
        file("bake.ipynb", "notebook", "application/x-ipynb+json", 18_000),
        file("backup.zip", "archive", "application/zip", 12_000_000),
    )

    private val listed = FilesState(path = home, parent = "/home", entries = entries)

    @Test
    fun files() {
        shoot("50-files") { FilesContent(listed, object : FilesActions {}) }
        shoot("50-files", Phone.Small, fontScale = 1.5f) { FilesContent(listed, object : FilesActions {}) }
        val chosen = listed.copy(selected = setOf("$home/lembas.md", "$home/map-of-the-road.png"))
        shoot("50-files-selecting") { FilesContent(chosen, object : FilesActions {}) }
        shoot("50-files-selecting", Phone.Small, fontScale = 1.5f) { FilesContent(chosen, object : FilesActions {}) }
        shoot("50-files-empty") { FilesContent(FilesState(path = home, parent = "/home"), object : FilesActions {}) }
    }

    @Test
    fun folderPicker() {
        val state = FolderPickState(path = home, parent = "/home", folders = entries.filter { it.isFolder }, loading = false)
        shoot("51-folder-picker") { FolderPickContent("Move to which folder?", "Move here", state, onNewFolder = {}) }
        shoot("51-folder-picker", Phone.Small, fontScale = 1.5f) { FolderPickContent("Move to which folder?", "Move here", state, onNewFolder = {}) }
        shoot("51-folder-picker-empty") { FolderPickContent("Copy to which folder?", "Copy here", state.copy(folders = emptyList())) }
    }

    @Test
    fun trashAndVersions() {
        val trash = TrashState(
            items = listOf(
                TrashItem(id = "n1", name = "old-notes-from-the-first-attempt-at-the-recipe.md", path = "$home/recipes/old-notes.md", size = 1_200),
                TrashItem(id = "n2", name = "drafts", path = "$home/drafts", kind = "dir"),
            ),
            loading = false,
        )
        shoot("52-trash") { TrashContent(trash, object : TrashActions {}) }
        shoot("52-trash", Phone.Small, fontScale = 1.5f) { TrashContent(trash, object : TrashActions {}) }
        shoot("52-trash-empty") { TrashContent(TrashState(loading = false), object : TrashActions {}) }
        val versions = VersionsState(
            versions = listOf(
                FileVersion(id = "v3", size = 2_048, at = "2026-03-10T08:00:00+00:00", by = "Frodo", current = true),
                FileVersion(id = "v2", size = 1_900, at = "2026-03-09T17:30:00+00:00", by = "Samwise Gamgee of Bagshot Row"),
                FileVersion(id = "v1", size = 20, at = "2026-03-01T06:15:00+00:00"),
            ),
            loading = false,
        )
        shoot("53-versions") { VersionsContent("lembas.md", versions, onDownload = {}) }
        shoot("53-versions", Phone.Small, fontScale = 1.5f) { VersionsContent("lembas.md", versions, onDownload = {}) }
    }

    @Test
    fun viewers() {
        val csv = "what,how many,where it is kept\nlembas,12,\"the leaf wrappings, top shelf\"\nwater skins,3,by the door\nrope,1\n" +
            (1..30).joinToString("\n") { "item $it,$it,shelf ${it % 4}" }
        shoot("54-viewer-table") { TableBody(parseTable(csv, ',')) }
        shoot("54-viewer-table-cut", Phone.Small) { TableBody(parseTable(csv, ',', maxRows = 6)) }
        val notebook = Notebook(
            cells = listOf(
                NotebookCell("markdown", "# Baking\nHow many cakes for the road."),
                NotebookCell("code", "cakes = days * walkers\nprint(cakes)", output = "36", count = 1),
                NotebookCell("code", "plot(cakes)", count = 2, cut = true),
            ),
            more = 4,
            language = "python",
        )
        shoot("55-viewer-notebook") { NotebookBody(notebook) }
        shoot("55-viewer-notebook", Phone.Small, fontScale = 1.5f) { NotebookBody(notebook) }
        val details: @androidx.compose.runtime.Composable () -> Unit = {
            DetailsBody(
                "backup.zip",
                "archive · application/zip",
                12_000_000,
                "What is inside this kind of archive cannot be listed here.",
                object : FileActions {},
            )
        }
        shoot("56-viewer-details", content = details)
        shoot("56-viewer-details", Phone.Small, fontScale = 1.5f, content = details)
        shoot("57-viewer-player") {
            Column(Modifier.fillMaxSize()) {
                PlayerControls(PlayerUi(ready = true, playing = true, positionMs = 65_000, durationMs = 214_000), onToggle = {}, onSeek = {})
                PlayerControls(PlayerUi(), onToggle = {}, onSeek = {})
            }
        }
        shoot("58-viewer-editor") { EditorBody("# Lembas\n\nWrap it in a leaf.\nKeep it dry.\n", onChange = {}) }
    }
}
