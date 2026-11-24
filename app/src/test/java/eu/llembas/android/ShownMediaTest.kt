package eu.llembas.android

import eu.llembas.android.api.AskFrame
import eu.llembas.android.api.DIFF_FOLD
import eu.llembas.android.api.DIFF_MOST
import eu.llembas.android.api.DIFF_TEXT_CHARS
import eu.llembas.android.api.DIFF_TEXT_LINES
import eu.llembas.android.api.DeviceList
import eu.llembas.android.api.DiffLine
import eu.llembas.android.api.DiffSign
import eu.llembas.android.api.EditorModels
import eu.llembas.android.api.FileDiff
import eu.llembas.android.api.GeneratedMedia
import eu.llembas.android.api.LembasJson
import eu.llembas.android.api.MediaView
import eu.llembas.android.api.Raw
import eu.llembas.android.api.TextLines
import eu.llembas.android.api.ToolCall
import eu.llembas.android.api.diffLines
import eu.llembas.android.api.hunks
import eu.llembas.android.api.openType
import eu.llembas.android.api.showsDetail
import eu.llembas.android.api.sniffType
import eu.llembas.android.api.textLines
import eu.llembas.android.api.view
import eu.llembas.android.api.visibly
import eu.llembas.android.ui.chat.DIFF_LINE_MOST
import eu.llembas.android.ui.chat.approvalTexts
import eu.llembas.android.ui.chat.diffRow
import eu.llembas.android.ui.chat.mediaKindWord
import eu.llembas.android.ui.chat.offersAlways
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
@OptIn(ExperimentalCoroutinesApi::class)
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

        // Two middles too long to compare line against line are not compared: the old goes, the
        // new comes. They share every line, in the other order -- compared, some would be "same".
        val a = (1..1500).map { "line $it" }
        val both = diffLines(a, a.reversed())
        assertEquals(3000, both.size)
        assertTrue(both.none { it.sign == DiffSign.Same })
        assertTrue(both.take(1500).all { it.sign == DiffSign.Removed })
        assertTrue(both.drop(1500).all { it.sign == DiffSign.Added })
        // The same two, short enough, are compared: something is found to be the same.
        val few = (1..50).map { "line $it" }
        assertTrue(diffLines(few, few.reversed()).any { it.sign == DiffSign.Same })

        // One line of any length is one bounded line of the card; a tab is spaces.
        val row = diffRow(DiffLine(DiffSign.Added, "x".repeat(5000)))
        assertEquals(2 + DIFF_LINE_MOST + 1, row.length)
        assertEquals("  " + "    a", diffRow(DiffLine(DiffSign.Same, "\ta")))
    }

    @Test
    fun aTextIsCutBeforeItIsComparedNotAfter() {
        // Two megabytes of newlines: only the first lines are ever split off.
        val newlines = "\n".repeat(2_000_000)
        val cut = textLines(newlines)
        assertEquals(DIFF_TEXT_LINES, cut.lines.size)
        assertTrue(cut.cut)
        // One line of two megabytes: only its beginning is kept.
        val one = textLines("x".repeat(2_000_000))
        assertEquals(1, one.lines.size)
        assertEquals(DIFF_TEXT_CHARS, one.lines.single().length)
        assertTrue(one.cut)
        // A text within both bounds is whole, and says so.
        assertEquals(TextLines(listOf("a", "b"), cut = false), textLines("a\nb\n"))
        assertEquals(TextLines(listOf("a", "b"), cut = false), textLines("a\nb"))
        assertEquals(TextLines(listOf("a"), cut = true), textLines("a\nb\n", mostLines = 1))
        assertEquals(TextLines(listOf("a"), cut = true), textLines("a\nbcd", mostChars = 2))
        assertEquals(TextLines(listOf("a", "b"), cut = true), textLines("a\nbcd", mostChars = 3))

        // A new file of that size is a card of bounded size that says it is not all there.
        val huge = FileDiff(path = "huge.txt", oldText = null, newText = (1..100_000).joinToString("\n") { "row $it" }).view()
        assertTrue(huge.cut)
        assertEquals(DIFF_TEXT_LINES, huge.added)
        assertEquals(DIFF_TEXT_LINES, huge.lines.size)
        assertTrue(huge.says)
        assertFalse(edit().diffs[0].view().cut)
    }

    @Test
    fun aChangeOfLineEndingsIsSaidOnceNotAsEveryLine() {
        val crlf = FileDiff(path = "a.txt", oldText = "one\ntwo\nthree\n", newText = "one\r\ntwo\r\nthree\r\n").view()
        assertTrue(crlf.lines.isEmpty())
        assertEquals(0 to 0, crlf.added to crlf.removed)
        assertEquals("LF" to "CRLF", crlf.endings)
        assertTrue(crlf.says)
        // With a real change beside it, only that line goes and comes.
        val both = FileDiff(path = "a.txt", oldText = "one\r\ntwo\r\n", newText = "one\nTWO\n").view()
        assertEquals("CRLF" to "LF", both.endings)
        assertEquals(listOf(DiffSign.Same, DiffSign.Removed, DiffSign.Added), both.lines.map { it.sign })
        assertEquals(listOf("one", "two", "TWO"), both.lines.map { it.text })
        // The same endings, a new file, a file of one line: nothing to say of endings.
        assertNull(edit().diffs[0].view().endings)
        assertNull(edit().diffs[1].view().endings)
        assertNull(FileDiff(path = "p", oldText = "a", newText = "a\r\nb\r\n").view().endings)
    }

    @Test
    fun whatCannotBeSeenIsWrittenAsItsCodePoint() {
        // A direction override makes a line read as something it is not; a line separator breaks it in two.
        assertEquals("+ if (admin<U+202E>) {", diffRow(DiffLine(DiffSign.Added, "if (admin\u202E) {")))
        assertEquals("- a<U+2028>b", diffRow(DiffLine(DiffSign.Removed, "a\u2028b")))
        assertEquals("a<U+200B>b<U+FEFF><U+2066><U+0000><U+000D><U+0085>", visibly("a\u200Bb\uFEFF\u2066\u0000\r\u0085"))
        // A path is read too: it names what is changed.
        assertEquals("src/<U+202E>yp.nevo", visibly("src/\u202Eyp.nevo"))
        // Ordinary text, other scripts and wide characters are left as they are.
        listOf("def bake(loaf):", "žltý kôň — ünïcödé", "日本語 \uD83C\uDF5E", "").forEach { assertEquals(it, visibly(it)) }
    }

    @Test
    fun theArgumentsStayInViewWhenTheDiffsSayNothing() {
        val item = edit()
        // Diffs that say something stand in the arguments' place, once they are worked out and while they are.
        assertFalse(showsDetail(item, item.diffs.map { it.view() }))
        assertFalse(showsDetail(item, null))
        // The same text twice, or an entry with nothing in it: the card would say "+0 -0" and nothing else.
        val same = item.copy(diffs = listOf(FileDiff(path = "a.txt", oldText = "a\n", newText = "a\n"), FileDiff()))
        assertTrue(same.diffs.map { it.view() }.none { it.says })
        assertTrue(showsDetail(same, same.diffs.map { it.view() }))
        // A card without diffs is as it always was.
        assertTrue(showsDetail(item.copy(diffs = emptyList()), emptyList()))
        assertTrue(showsDetail(item.copy(diffs = emptyList()), null))
    }

    // --- The gate and the assistant's sheet ---------------------------------------------------------

    @Test
    fun theAssistantsSheetIsTheAppInFrontAndTheLockStillShutsTheGate() = runTest {
        var locked = false
        val gate = InstanceGate { locked }
        // No activity: the sheet is a session's window. A picture in it waits as a chat's does...
        val fetched = mutableListOf<String>()
        val picture = launch {
            gate.passLater()
            fetched += "picture"
        }
        runCurrent()
        assertTrue(fetched.isEmpty())
        // ...until the sheet shows, which no activity's start would ever say.
        gate.sessionShown()
        runCurrent()
        assertEquals(listOf("picture"), fetched)
        picture.join()
        // Shown again while up, hidden once: shut.
        gate.sessionShown()
        gate.sessionHidden()
        assertFalse(gate.open.value)

        // Over the lock screen the sheet opens nothing; the unlock does.
        locked = true
        gate.sessionShown()
        assertFalse(gate.open.value)
        locked = false
        assertTrue(gate.look())
        // An activity of the app in front keeps the gate open when the sheet goes.
        gate.activityStarted()
        gate.sessionHidden()
        assertTrue(gate.open.value)
        gate.activityStopped()
        assertFalse(gate.open.value)
    }

    @Test
    fun aSessionTornDownWithoutBeingHiddenLeavesTheGateShut() = runTest {
        val gate = InstanceGate { false }
        val sheet = SheetAtGate(gate)
        // Shown, then destroyed with no hide between them: the end is said all the same.
        sheet.shown()
        assertTrue(gate.open.value)
        sheet.gone()
        assertFalse(gate.open.value)
        assertFalse(gate.look())
        // Nothing asked for now starts until something of the app is in front again.
        val fetched = mutableListOf<String>()
        val picture = launch {
            gate.passLater()
            fetched += "picture"
        }
        runCurrent()
        assertTrue(fetched.isEmpty())
        // Hidden and then destroyed, or destroyed twice, is still one end; the next showing opens it.
        sheet.gone()
        sheet.gone()
        assertFalse(gate.open.value)
        sheet.shown()
        runCurrent()
        assertEquals(listOf("picture"), fetched)
        picture.join()
        // An activity in front is not undone by the sheet's end.
        gate.activityStarted()
        sheet.gone()
        assertTrue(gate.open.value)
    }

    // --- Open with ----------------------------------------------------------------------------------

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun aFileIsOfferedAsWhatItsBytesSayNotAsWhatItWasCalled() {
        val png = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val pdf = "%PDF-1.7\n".toByteArray()
        val zip = bytes(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)
        assertEquals("image/png", sniffType(png))
        assertEquals("application/pdf", sniffType(pdf))
        assertEquals("image/jpeg", sniffType(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals("video/mp4", sniffType(bytes(0, 0, 0, 0x20) + "ftypisom".toByteArray()))
        assertEquals("audio/wav", sniffType("RIFF".toByteArray() + bytes(0, 0, 0, 0) + "WAVE".toByteArray()))
        assertEquals("image/webp", sniffType("RIFF".toByteArray() + bytes(0, 0, 0, 0) + "WEBP".toByteArray()))
        assertNull(sniffType("just words".toByteArray()))
        assertNull(sniffType(ByteArray(0)))
        assertNull(sniffType(bytes(0x89)))

        // The bytes decide, whatever the name says.
        assertEquals("application/pdf", openType(pdf, "image/png"))
        assertEquals("image/png", openType(png, null))
        // No mark in the bytes: the name's extension, else anything.
        assertEquals("text/plain", openType("just words".toByteArray(), "text/plain"))
        assertEquals("*/*", openType("just words".toByteArray(), null))
        assertEquals("*/*", openType(ByteArray(0), ""))
        // A zip is what many documents are inside: a known extension is the better word there.
        assertEquals("application/epub+zip", openType(zip, "application/epub+zip"))
        assertEquals("application/zip", openType(zip, null))
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

    private fun fixture(name: String) = LembasJson.parseToJsonElement(contract(name)).jsonObject

    private fun kotlinx.serialization.json.JsonObject.word(key: String) = getValue(key).jsonPrimitive.content

    @Test
    fun aComputersFilesAndAMessagesEditorContextSayWhatTheApiSays() {
        // No screen and no model of these here yet. The fields a browser would stand on are
        // where App-API puts them, so a pin that moves them fails here.
        val roots = fixture("device_fs_roots")
        assertEquals("", roots.word("path"))
        assertTrue(roots.getValue("entries").jsonArray.isNotEmpty())

        val listing = fixture("device_fs_listing")
        assertEquals("/home/dev/project", listing.word("path"))
        val entries = listing.getValue("entries").jsonArray.map { it.jsonObject }
        val keys = setOf("name", "path", "kind", "size", "mtime", "updated_at", "file_kind", "link", "outside", "protected", "runs_code", "writable")
        entries.forEach { assertEquals(keys, it.keys) }
        assertTrue(entries.map { it.word("kind") }.containsAll(listOf("dir", "file")))
        assertTrue(entries.single { it.word("name") == "shared" }.getValue("outside").jsonPrimitive.boolean)
        assertTrue(entries.single { it.word("name") == ".env" }.getValue("protected").jsonPrimitive.boolean)

        val content = fixture("device_fs_content")
        assertTrue(content.word("sha").startsWith("sha256:"))
        assertTrue(content.word("text").isNotEmpty())
        val withheld = fixture("device_fs_content_withheld")
        assertEquals("", withheld.word("text"))
        assertTrue(withheld.getValue("withheld").jsonObject.word("reason") in setOf("not_text", "too_large"))

        val conflict = fixture("device_fs_conflict")
        assertEquals("changed", conflict.word("reason"))
        assertTrue(conflict.getValue("conflict").jsonPrimitive.boolean)
        assertTrue(conflict.word("sha").startsWith("sha256:"))
        assertEquals("no_trash", fixture("device_fs_no_trash").word("reason"))
        assertTrue(fixture("device_fs_written").word("sha").startsWith("sha256:"))

        val batch = fixture("device_fs_batch")
        assertFalse(batch.getValue("atomic").jsonPrimitive.boolean)
        assertTrue(batch.getValue("results").jsonArray.all { "ok" in it.jsonObject && "path" in it.jsonObject })

        val editor = fixture("message_editor")
        assertEquals("/home/dev/project/src/oven.py", editor.word("path"))
        assertEquals(2, editor.getValue("selection").jsonObject.getValue("start").jsonPrimitive.int)
        assertEquals("warning", editor.getValue("diagnostics").jsonArray.single().jsonObject.word("severity"))
    }
}
