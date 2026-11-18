package eu.llembas.android

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import eu.llembas.android.api.BatchAnswer
import eu.llembas.android.api.FileContent
import eu.llembas.android.api.FileEntry
import eu.llembas.android.api.FileKinds
import eu.llembas.android.api.FileListing
import eu.llembas.android.api.FileVersions
import eu.llembas.android.api.FileView
import eu.llembas.android.api.LembasJson
import eu.llembas.android.api.TrashList
import eu.llembas.android.api.parseNotebook
import eu.llembas.android.api.parseTable
import eu.llembas.android.api.sampleFor
import eu.llembas.android.api.tableDelimiter
import eu.llembas.android.share.sharedNoteName
import eu.llembas.android.ui.files.FilesState
import eu.llembas.android.ui.files.TransferViewModel
import eu.llembas.android.ui.files.asZip
import eu.llembas.android.ui.files.outcome
import eu.llembas.android.ui.viewer.clampPan
import eu.llembas.android.ui.viewer.playTime
import kotlinx.serialization.KSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** What the file manager decides without a phone: the viewer for a kind, a table, a notebook, what is chosen, what a batch said. */
class FileLogicTest {
    private fun <T> read(name: String, serializer: KSerializer<T>): T = LembasJson.decodeFromString(
        serializer,
        requireNotNull(javaClass.classLoader!!.getResource("$name.json")) {
            "contract/$name.json is missing"
        }.readText(),
    )

    @Test
    fun thePinnedFilesContract() {
        val listing = read("files_listing", FileListing.serializer())
        assertEquals(listOf(null, "markdown", "image"), listing.entries.map { it.fileKind })
        assertNull(listing.copied)
        val copied = read("files_copied", FileListing.serializer())
        assertEquals("lembas (2).md", copied.copied!!.name)
        assertEquals("/home/frodo/lembas (2).md", copied.copied!!.path)

        val file = read("file_content", FileContent.serializer())
        assertEquals("markdown", file.fileKind)
        assertEquals(true, file.writable)
        assertEquals("ab".repeat(32), file.sha)
        val picture = read("file_content_withheld", FileContent.serializer())
        assertEquals("image", picture.fileKind)
        assertEquals(false, picture.writable)

        val trash = read("files_trash", TrashList.serializer())
        assertEquals("node5", trash.items.single().id)
        assertEquals("/home/frodo/old.md", trash.items.single().path)
        assertEquals(12L, trash.items.single().size)
        assertEquals("/home/frodo/old.md", read("files_restored", TrashList.serializer()).restored)
        assertEquals(1, read("files_trash_emptied", TrashList.serializer()).removed)

        val versions = read("file_versions", FileVersions.serializer())
        assertEquals(listOf("ver2", "ver1"), versions.versions.map { it.id })
        assertTrue(versions.versions[0].current)
        assertFalse(versions.versions[1].current)
        assertEquals("Frodo", versions.versions[1].by)
        assertNull(versions.versions[1].chatId)

        // A batch is answered path by path: one moved, one refused in the server's own sentence.
        val batch = read("files_batch", BatchAnswer.serializer())
        assertEquals(1, batch.done)
        assertEquals(1, batch.failed)
        assertEquals("/home/frodo/recipes/lembas.md", batch.results[0].to)
        val said = outcome(batch)
        assertEquals(1, said.done)
        assertEquals("Read-only: /home/frodo/map.png can be read in this session but not changed.", said.said)
        assertNull(outcome(batch.copy(failed = 0)).said)
        // Without a sentence, at least which one.
        assertEquals("map.png", outcome(batch.copy(results = batch.results.map { it.copy(error = null) })).said)
    }

    @Test
    fun aKindChoosesItsViewer() {
        for (kind in listOf("markdown", "code", "text", "html", "svg")) assertEquals(kind, FileView.Text, FileKinds.viewer(kind))
        assertEquals(FileView.Image, FileKinds.viewer("image"))
        assertEquals(FileView.Pdf, FileKinds.viewer("pdf"))
        assertEquals(FileView.Audio, FileKinds.viewer("audio"))
        assertEquals(FileView.Video, FileKinds.viewer("video"))
        assertEquals(FileView.Table, FileKinds.viewer("table"))
        assertEquals(FileView.Notebook, FileKinds.viewer("notebook"))
        for (kind in listOf("archive", "model3d", "binary", "something-new")) assertEquals(kind, FileView.Details, FileKinds.viewer(kind))
        // A server that does not say the kind is read as before: as text.
        assertEquals(FileView.Text, FileKinds.viewer(null))

        // Editing: a text kind (a table too) the person may change, and nothing withheld or unsaid.
        assertTrue(FileKinds.editable("markdown", writable = true, withheld = false))
        assertTrue(FileKinds.editable("svg", writable = true, withheld = false))
        assertTrue(FileKinds.editable("table", writable = true, withheld = false))
        assertFalse(FileKinds.editable("markdown", writable = false, withheld = false))
        assertFalse(FileKinds.editable("markdown", writable = null, withheld = false))
        assertFalse(FileKinds.editable("markdown", writable = true, withheld = true))
        assertFalse(FileKinds.editable("notebook", writable = true, withheld = false))
        assertFalse(FileKinds.editable("image", writable = true, withheld = false))
        assertFalse(FileKinds.editable(null, writable = true, withheld = false))
    }

    @Test
    fun namesAndPaths() {
        assertTrue(FileKinds.badName(""))
        assertTrue(FileKinds.badName("  "))
        assertTrue(FileKinds.badName("a/b"))
        assertTrue(FileKinds.badName(".."))
        assertFalse(FileKinds.badName("notes.md"))
        assertEquals("/home/frodo/notes.md", FileKinds.child("/home/frodo/", " notes.md "))
        assertEquals("/notes.md", FileKinds.child("/", "notes.md"))
        assertEquals("/home/frodo", FileKinds.parent("/home/frodo/notes.md"))
        assertEquals("/", FileKinds.parent("/home"))
        assertEquals("notes.md", FileKinds.name("/home/frodo/notes.md"))
        assertEquals("recipes", FileKinds.name("/home/frodo/recipes/"))
        // What another app is told a file is called holds no way out of its folder.
        assertEquals("_.._etc_passwd", FileKinds.safeName("../../etc/passwd"))
        assertEquals("file", FileKinds.safeName(".."))
        assertEquals("file", FileKinds.safeName(" "))
        assertEquals("a_b", FileKinds.safeName("a\\b"))
        assertEquals("map.png", FileKinds.safeName("map.png"))

        assertFalse(asZip(listOf(FileEntry(name = "a.md", path = "/h/a.md"))))
        assertTrue(asZip(listOf(FileEntry(name = "r", path = "/h/r", kind = "dir"))))
        assertTrue(asZip(listOf(FileEntry(name = "a.md", path = "/h/a.md"), FileEntry(name = "b.md", path = "/h/b.md"))))
        assertEquals("recipes.zip", TransferViewModel.zipName(listOf("/home/frodo/recipes")))
        assertEquals("frodo.zip", TransferViewModel.zipName(listOf("/home/frodo/a.md", "/home/frodo/b.md")))
        assertEquals("files.zip", TransferViewModel.zipName(emptyList()))
        assertEquals("shared-20260102-030405.txt", sharedNoteName(LocalDateTime.of(2026, 1, 2, 3, 4, 5)))
    }

    @Test
    fun whatIsChosenInADirectory() {
        val listing = read("files_listing", FileListing.serializer())
        var state = FilesState(path = "/home/frodo").listed(listing)
        assertFalse(state.selecting)
        state = state.toggled("/home/frodo/lembas.md").toggled("/home/frodo/map.png")
        assertTrue(state.selecting)
        assertEquals(listOf("lembas.md", "map.png"), state.chosen.map { it.name })
        assertEquals(listOf("map.png"), state.toggled("/home/frodo/lembas.md").chosen.map { it.name })
        assertEquals(3, state.allSelected().selected.size)
        // The same directory read again keeps what is still there; another directory keeps nothing.
        val without = listing.copy(entries = listing.entries.filter { it.name != "map.png" })
        assertEquals(setOf("/home/frodo/lembas.md"), state.listed(without).selected)
        assertTrue(state.listed(listing.copy(path = "/home/frodo/recipes")).selected.isEmpty())
        // A new file is never written over one that is there.
        assertTrue(state.taken("lembas.md"))
        assertTrue(state.taken(" lembas.md "))
        assertFalse(state.taken("lembas.txt"))
    }

    @Test
    fun aTableIsCutIntoCells() {
        val table = parseTable("name,what\r\n\"Baggins, Frodo\",\"says \"\"hello\"\"\"\nSam,\"two\nlines\"\n\nlast,row", ',')
        assertEquals(
            listOf(listOf("name", "what"), listOf("Baggins, Frodo", "says \"hello\""), listOf("Sam", "two\nlines"), listOf("last", "row")),
            table.rows,
        )
        assertEquals(2, table.columns)
        assertFalse(table.cut)
        // Tabs, a ragged row, and a quote inside a bare cell left as it is.
        val tabs = parseTable("a\tb\tc\n1\t2\n5\" pipe\tx\ty\n", '\t')
        assertEquals(listOf(listOf("a", "b", "c"), listOf("1", "2"), listOf("5\" pipe", "x", "y")), tabs.rows)
        assertEquals(3, tabs.columns)
        // Caps: rows, columns and a cell's length; what was left out is said.
        val many = parseTable((1..10).joinToString("\n") { "$it,x,y,z" }, ',', maxRows = 3, maxColumns = 2, maxCell = 1)
        assertEquals(listOf(listOf("1", "x"), listOf("2", "x"), listOf("3", "x")), many.rows)
        assertTrue(many.cut)
        assertEquals("a", parseTable("abcdef,2", ',', maxCell = 1).rows[0][0])
        // Only the beginning of a long file: its last, half line is not shown as a row.
        val head = parseTable("a,b\n1,2\n3,", ',', partial = true)
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), head.rows)
        assertTrue(head.cut)
        assertTrue(parseTable("", ',').rows.isEmpty())

        assertEquals('\t', tableDelimiter("list.tsv", "a,b"))
        assertEquals('\t', tableDelimiter("list.csv", "a\tb\n1\t2"))
        assertEquals(';', tableDelimiter("list.csv", "a;b\n1;2"))
        assertEquals(',', tableDelimiter("list.csv", "a,b;c"))
    }

    @Test
    fun aNotebookIsItsCellsAsText() {
        val text = """
            {"metadata": {"kernelspec": {"language": "python"}}, "cells": [
              {"cell_type": "markdown", "source": ["# Waybread\n", "Notes."]},
              {"cell_type": "code", "execution_count": 3, "source": "print('hi')",
               "outputs": [
                 {"output_type": "stream", "text": ["hi\n"]},
                 {"output_type": "execute_result", "data": {"text/plain": "42", "text/html": "<b onclick=x>42</b>", "image/png": "AAAA"}},
                 {"output_type": "error", "ename": "ValueError", "evalue": "no"}
               ]},
              {"cell_type": "code", "execution_count": null, "source": []}
            ]}
        """.trimIndent()
        val notebook = parseNotebook(text)
        assertNotNull(notebook)
        assertEquals("python", notebook!!.language)
        assertEquals(listOf("markdown", "code", "code"), notebook.cells.map { it.kind })
        assertEquals("# Waybread\nNotes.", notebook.cells[0].source)
        assertEquals(3, notebook.cells[1].count)
        // Only what is plain text of an output is kept: no HTML, no picture.
        assertEquals("hi\n42\nValueError: no", notebook.cells[1].output)
        assertFalse(notebook.cells[1].output.contains("onclick"))
        assertNull(notebook.cells[2].count)
        assertEquals("", notebook.cells[2].source)
        assertEquals(0, notebook.more)

        val long = parseNotebook(text, maxCells = 1, maxChars = 4)!!
        assertEquals(2, long.more)
        assertEquals("# Wa", long.cells.single().source)
        assertTrue(long.cells.single().cut)
        // Not a notebook: nothing to draw as one.
        assertNull(parseNotebook("[1, 2]"))
        assertNull(parseNotebook("{\"cells\": 3}"))
        assertNull(parseNotebook("not json"))
        assertTrue(parseNotebook("{\"cells\": []}")!!.cells.isEmpty())
    }

    @Test
    fun aPictureIsDecodedSmallWhateverItClaims() {
        assertEquals(1, sampleFor(1920, 1080))
        assertEquals(2, sampleFor(4000, 3000))
        // 30 000 px square: at most a few megapixels are ever decoded.
        val sample = sampleFor(30_000, 30_000)
        assertTrue((30_000L / sample) * (30_000L / sample) <= 8_000_000)
        assertTrue(30_000 / sample <= 2560)
        // A ribbon: its long edge decides.
        assertTrue(100_000 / sampleFor(100_000, 10) <= 2560)
        assertEquals(1, sampleFor(0, 0))
    }

    @Test
    fun aZoomedPictureStaysInItsFrameAndAPlayerSaysItsTime() {
        val size = IntSize(1000, 500)
        // Not zoomed: it does not move. Zoomed twice: half its size either way, and no further.
        val still = clampPan(Offset(300f, -80f), 1f, size)
        assertEquals(0f, still.x, 0f)
        assertEquals(0f, still.y, 0f)
        val right = clampPan(Offset(900f, -80f), 2f, size)
        assertEquals(500f, right.x, 0f)
        assertEquals(-80f, right.y, 0f)
        val corner = clampPan(Offset(-900f, 400f), 2f, size)
        assertEquals(-500f, corner.x, 0f)
        assertEquals(250f, corner.y, 0f)

        assertEquals("0:00", playTime(0))
        assertEquals("0:00", playTime(-5))
        assertEquals("1:05", playTime(65_400))
        assertEquals("1:00:07", playTime(3_607_000))
    }
}
