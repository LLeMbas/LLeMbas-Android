package eu.llembas.android

import eu.llembas.android.api.AskFrame
import eu.llembas.android.api.DIFF_FOLD
import eu.llembas.android.api.DIFF_MOST
import eu.llembas.android.api.DeviceList
import eu.llembas.android.api.DiffLine
import eu.llembas.android.api.DiffSign
import eu.llembas.android.api.EditorModels
import eu.llembas.android.api.FileDiff
import eu.llembas.android.api.GeneratedMedia
import eu.llembas.android.api.LembasJson
import eu.llembas.android.api.MediaView
import eu.llembas.android.api.Raw
import eu.llembas.android.api.ToolCall
import eu.llembas.android.api.diffLines
import eu.llembas.android.api.hunks
import eu.llembas.android.api.view
import eu.llembas.android.ui.chat.DIFF_LINE_MOST
import eu.llembas.android.ui.chat.approvalTexts
import eu.llembas.android.ui.chat.diffRow
import eu.llembas.android.ui.chat.mediaKindWord
import eu.llembas.android.ui.chat.offersAlways
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the pin after the editor round brought: a file an agent showed in the chat, an edit's
 * diff on its approval card, and the shapes that are parsed and not drawn yet (a linked
 * computer's files, an editor's two models).
 */
class ShownMediaTest {
    private fun contract(name: String): String = requireNotNull(javaClass.classLoader!!.getResource("$name.json")) {
        "contract/$name.json is missing"
    }.readText()

    private fun shown(): List<ToolCall> {
        val calls = LembasJson.parseToJsonElement(contract("tool_media_shown")).jsonObject.getValue("calls")
        return LembasJson.decodeFromJsonElement(ListSerializer(ToolCall.serializer()), calls)
    }

    private fun media(kind: String, shown: Boolean = true) = GeneratedMedia(kind = kind, nodeId = "node1", name = "thing", shown = shown)

    // --- A file an agent showed ---------------------------------------------------------------------

    @Test
    fun aShownFileIsTheCallsMediaAndNeverAnAttachment() {
        val (picture, pdf, archive) = shown()
        listOf(picture, pdf, archive).forEach { call ->
            assertEquals("show", call.name)
            assertTrue(call.media!!.shown)
            // No `show` card and no attachment: the card is drawn from `media` alone.
            assertNull(call.show)
            assertNull(call.image)
            assertNull(call.mediaJob)
        }
        val image = picture.media!!
        assertEquals("image", image.kind)
        assertEquals(MediaView.Picture, image.view)
        assertEquals("chart.png", image.title)
        assertEquals(1280, image.width)
        assertEquals(720, image.height)
        // Its bytes are asked for by its id, wherever the file is now.
        assertEquals(Raw.Node("node8"), image.raw)
        assertEquals("/home/frodo/oven", image.folder)

        assertEquals(MediaView.Document, pdf.media!!.view)
        assertEquals("report-20260102-030405.pdf", pdf.media!!.title)
        assertEquals(90_000L, pdf.media!!.size)
        assertEquals("/home/frodo/Generated", pdf.media!!.folder)

        assertEquals(MediaView.Details, archive.media!!.view)
        assertEquals(Raw.Node("node10"), archive.media!!.raw)
    }

    @Test
    fun aGeneratedFileIsNotAShownOne() {
        val calls = LembasJson.parseToJsonElement(contract("tool_media")).jsonObject.getValue("calls")
        val made = LembasJson.decodeFromJsonElement(ListSerializer(ToolCall.serializer()), calls).mapNotNull { it.media }
        assertTrue(made.isNotEmpty())
        assertTrue(made.none { it.shown })
    }

    @Test
    fun everyKindHasACardAndAudioPlaysAsMusicDoes() {
        assertEquals(MediaView.Picture, media("image").view)
        assertEquals(MediaView.Sound, media("audio").view)
        assertEquals(media("music").view, media("audio").view)
        assertEquals(MediaView.Video, media("video").view)
        assertEquals(MediaView.Document, media("pdf").view)
        // What the phone has no viewer for in a chat is its details: a name, a kind, the ways to the file.
        listOf("model3d", "archive", "binary", "text", "hologram", "").forEach { assertEquals(it, MediaView.Details, media(it).view) }
    }

    @Test
    fun everyKindTheServerNamesHasAWordAndAnUnknownOneIsStillAFile() {
        listOf("image", "audio", "video", "pdf", "model3d", "archive", "binary", "text").forEach { kind ->
            assertNotNull(kind, mediaKindWord(kind))
            assertTrue(kind, media(kind).knownKind)
        }
        assertEquals(R.string.media_audio, mediaKindWord("audio"))
        assertEquals(R.string.media_pdf, mediaKindWord("pdf"))
        assertEquals(R.string.media_archive, mediaKindWord("archive"))
        // No word of its own: the card says "File" for a shown one (`mediaKind`), never nothing.
        assertNull(mediaKindWord("hologram"))
        assertFalse(media("hologram").knownKind)
    }

    @Test
    fun aShownFileWithoutANameIsCalledByItsPath() {
        val nameless = GeneratedMedia(kind = "binary", path = "/home/frodo/Generated/dump-20260102.bin", shown = true)
        assertEquals("dump-20260102.bin", nameless.title)
        // Without an id it is asked for by its path.
        assertEquals(Raw.Path("/home/frodo/Generated/dump-20260102.bin"), nameless.raw)
    }

    // --- An edit's diff on its card -----------------------------------------------------------------

    private fun edit() = contractCard().items.single()

    private fun contractCard() = LembasJson.decodeFromString(AskFrame.serializer(), contract("event_ask_edit")).cards.single()

    @Test
    fun anEditsCardCarriesADiffPerFile() {
        val card = contractCard()
        assertEquals("approval", card.kind)
        val (changed, made, gone) = edit().diffs
        assertEquals("src/oven.py", changed.path)
        assertFalse(changed.made)
        assertFalse(changed.deleted)
        assertEquals("src/timer.py", made.path)
        assertNull(made.oldText)
        assertTrue(made.made)
        assertEquals("src/old.py", gone.path)
        assertTrue(gone.deleted)
        assertFalse(gone.made)
        // Allow and Deny are the card's usual ones: nothing of the diff goes back with the answer.
        assertTrue(offersAlways(card))
        assertTrue(approvalTexts(card.items, emptyMap()).isEmpty())
    }

    @Test
    fun aCardWithoutADiffHasNone() {
        val cards = LembasJson.decodeFromString(AskFrame.serializer(), contract("event_ask")).cards
        assertTrue(cards.flatMap { it.items }.all { it.diffs.isEmpty() })
    }

    @Test
    fun aChangedFileShowsWhatGoesAndWhatComes() {
        val view = edit().diffs[0].view()
        assertEquals(2, view.added)
        assertEquals(1, view.removed)
        assertEquals(
            listOf(
                DiffLine(DiffSign.Same, "def bake(loaf):"),
                DiffLine(DiffSign.Removed, "    return loaf.heat(220)"),
                DiffLine(DiffSign.Added, "    # <img src=x onerror=alert(1)>"),
                DiffLine(DiffSign.Added, "    return loaf.heat(240)"),
            ),
            view.lines,
        )
        // A model's words stay words: the line is written as it is, with its sign before it.
        assertEquals("+     # <img src=x onerror=alert(1)>", diffRow(view.lines[2]))
        assertEquals("-     return loaf.heat(220)", diffRow(view.lines[1]))
        assertEquals("  def bake(loaf):", diffRow(view.lines[0]))
    }

    @Test
    fun aNewFileIsAllAddedAndADeletedOneAllRemoved() {
        val (_, made, gone) = edit().diffs
        assertEquals(listOf(DiffLine(DiffSign.Added, "MINUTES = 40")), made.view().lines)
        assertEquals(1 to 0, made.view().let { it.added to it.removed })
        assertEquals(listOf(DiffLine(DiffSign.Removed, "x = 1")), gone.view().lines)
        assertEquals(0 to 1, gone.view().let { it.added to it.removed })
        // A deleted file's new text is not read, whatever it says.
        assertEquals(gone.view(), gone.copy(newText = "ignored\n").view())
    }

    @Test
    fun aChangeKeepsALittleAroundItAndLeavesTheRestOut() {
        val old = (1..40).map { "line $it" }
        val new = old.toMutableList().also {
            it[9] = "line ten"
            it[29] = "line thirty"
        }
        val all = diffLines(old, new)
        assertEquals(42, all.size)
        val cut = hunks(all)
        // Three lines before and after each change, and one mark for the lines between them.
        assertEquals(1, cut.count { it.sign == DiffSign.Gap })
        assertEquals("line 7", cut.first().text)
        assertEquals("line 33", cut.last().text)
        assertEquals(listOf("line 10", "line 30"), cut.filter { it.sign == DiffSign.Removed }.map { it.text })
        assertEquals(listOf("line ten", "line thirty"), cut.filter { it.sign == DiffSign.Added }.map { it.text })
        assertEquals(17, cut.size)
        assertEquals("⋯", diffRow(cut.first { it.sign == DiffSign.Gap }))
    }

    @Test
    fun linesThatMovedAreComparedAsTheLongestSharedRun() {
        val lines = diffLines(listOf("a", "b", "c", "d"), listOf("a", "c", "d", "b"))
        assertEquals(
            listOf(
                DiffLine(DiffSign.Same, "a"),
                DiffLine(DiffSign.Removed, "b"),
                DiffLine(DiffSign.Same, "c"),
                DiffLine(DiffSign.Same, "d"),
                DiffLine(DiffSign.Added, "b"),
            ),
            lines,
        )
        // The same text is no change, and so no line on the card.
        assertTrue(FileDiff(path = "same.txt", oldText = "a\nb\n", newText = "a\nb\n").view().lines.isEmpty())
        // An empty file that is made adds nothing; one without a last newline keeps its last line.
        assertTrue(FileDiff(path = "empty", oldText = null, newText = "").view().lines.isEmpty())
        assertEquals(listOf("a", "b"), FileDiff(path = "p", oldText = null, newText = "a\nb").view().lines.map { it.text })
    }

    @Test
    fun aLongDiffIsFoldedAndAHugeOneIsBounded() {
        val long = FileDiff(path = "long.txt", oldText = null, newText = (1..1000).joinToString("\n") { "row $it" })
        val view = long.view()
        assertEquals(1000, view.added)
        assertTrue(view.folds)
        assertEquals(DIFF_FOLD, view.shown(open = false).size)
        assertEquals(DIFF_MOST, view.shown(open = true).size)
        assertEquals(1000 - DIFF_MOST, view.beyond)

        val short = edit().diffs[0].view()
        assertFalse(short.folds)
        assertEquals(short.lines, short.shown(open = false))
        assertEquals(0, short.beyond)

        // Two texts too long to compare line against line are not compared: the old goes, the new comes.
        val a = (1..1500).map { "old $it" }
        val b = (1..1500).map { "new $it" }
        val both = diffLines(a, b)
        assertEquals(3000, both.size)
        assertEquals(1500, both.count { it.sign == DiffSign.Removed })
        assertTrue(both.take(1500).all { it.sign == DiffSign.Removed })

        // One line of any length is one bounded line of the card; a tab is spaces.
        val row = diffRow(DiffLine(DiffSign.Added, "x".repeat(5000)))
        assertEquals(2 + DIFF_LINE_MOST + 1, row.length)
        assertEquals("      a", diffRow(DiffLine(DiffSign.Same, "\ta\r")))
    }

    // --- Parsed, and not drawn yet ------------------------------------------------------------------

    @Test
    fun aDeviceSaysWhetherItHandsItsFilesOver() {
        val (workstation, laptop) = LembasJson.decodeFromString(DeviceList.serializer(), contract("devices")).devices
        assertTrue(workstation.files)
        assertTrue(workstation.filesWritable)
        assertFalse(workstation.filesThatRunCode)
        assertFalse(laptop.files)
        assertFalse(laptop.filesWritable)
        // An instance from before the files says nothing, and that is "no".
        assertFalse(LembasJson.decodeFromString(DeviceList.serializer(), """{"devices":[{"id":"t1"}]}""").devices.single().files)
    }

    @Test
    fun anEditorsTwoModelsAreReadSetAndUnset() {
        val set = LembasJson.decodeFromString(EditorModels.serializer(), contract("editor"))
        assertEquals("local/qwen", set.autocomplete.model)
        assertEquals("person", set.autocomplete.source)
        assertEquals(listOf("local/qwen", "local/vision-small"), set.autocomplete.allowed)
        assertEquals("local/gpt-oss", set.main.model)

        val unset = LembasJson.decodeFromString(EditorModels.serializer(), contract("editor_unset"))
        assertNull(unset.autocomplete.model)
        assertNull(unset.autocomplete.source)
        assertNull(unset.autocomplete.allowed)
        assertNull(unset.main.model)
    }

    @Test
    fun theShapesNothingUsesYetAreRead() {
        // A linked computer's files and a message's editor context: no screen and no model of
        // them here yet, and the app's reader takes each as the server wrote it.
        listOf(
            "device_fs_roots", "device_fs_listing", "device_fs_stat", "device_fs_content", "device_fs_content_withheld", "device_fs_written",
            "device_fs_conflict", "device_fs_batch", "device_fs_deleted", "device_fs_no_trash", "device_fs_search", "device_fs_search_content",
            "message_editor",
        ).forEach { name -> assertTrue(name, LembasJson.parseToJsonElement(contract(name)).jsonObject.isNotEmpty()) }
    }
}
