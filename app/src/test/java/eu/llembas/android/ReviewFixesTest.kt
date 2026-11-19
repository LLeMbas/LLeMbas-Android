package eu.llembas.android

import eu.llembas.android.api.ApiClient
import eu.llembas.android.api.ArchiveListing
import eu.llembas.android.api.FileContent
import eu.llembas.android.api.FileExists
import eu.llembas.android.api.FileKinds
import eu.llembas.android.api.FileNode
import eu.llembas.android.api.FileView
import eu.llembas.android.api.LembasJson
import eu.llembas.android.api.ModelInfo
import eu.llembas.android.api.SchedulingModel
import eu.llembas.android.api.Whereabouts
import eu.llembas.android.api.named
import eu.llembas.android.api.whereabouts
import eu.llembas.android.assist.AssistUiState
import eu.llembas.android.share.Kept
import eu.llembas.android.share.SharedSource
import eu.llembas.android.ui.files.FileCache
import eu.llembas.android.ui.scheduled.EditState
import eu.llembas.android.ui.scheduled.modelInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * What a review of the file manager found, each as the rule it became: nothing new is sent
 * from behind a lock, a share's addresses are not believed, a new file empties nothing, a kept
 * copy outlives a restart, a name fits a file system, a ref is never read as an id, a second
 * try goes on where the first stopped, and a transfer given up ends at once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewFixesTest {
    @get:Rule
    val folder = TemporaryFolder()

    // --- The gate ----------------------------------------------------------------------------

    @Test
    fun theRequestUnderWayFinishesAndTheNextWaitsForTheUnlock() = runTest {
        var locked = false
        val gate = InstanceGate { locked }
        gate.activityStarted()
        val started = mutableListOf<Int>()
        val waits = mutableListOf<Boolean>()
        val first = CompletableDeferred<Unit>()
        val queue = launch {
            sendFrom(listOf(1, 2, 3), 0, { gate.pass { waits += it } }, {}) { n ->
                started += n
                if (n == 1) first.await()
            }
        }
        runCurrent()
        assertEquals(listOf(1), started)

        // The phone locks while the first is on its way: it goes through, the second does not start.
        locked = true
        gate.look()
        first.complete(Unit)
        runCurrent()
        assertEquals(listOf(1), started)
        assertEquals(listOf(true), waits)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(listOf(1), started)

        // Unlocked, and nobody said so: the gate looks again by itself and the queue goes on.
        locked = false
        advanceTimeBy(InstanceGate.AGAIN_MS + 1)
        runCurrent()
        assertEquals(listOf(1, 2, 3), started)
        assertEquals(listOf(true, false), waits)
        queue.join()
    }

    @Test
    fun nothingStartsWhileTheAppIsNotInFront() = runTest {
        val gate = InstanceGate { false }
        val started = mutableListOf<String>()
        // No activity of the app is started: shut, although the phone is unlocked.
        assertFalse(gate.look())
        val waiting = launch {
            gate.pass()
            started += "sent"
        }
        runCurrent()
        assertTrue(started.isEmpty())
        gate.activityStarted()
        runCurrent()
        assertEquals(listOf("sent"), started)
        waiting.join()

        // In front but locked (a sheet over the lock screen) is shut too; stopped again is shut.
        var locked = true
        val over = InstanceGate { locked }
        over.activityStarted()
        assertFalse(over.open.value)
        locked = false
        assertTrue(over.look())
        over.activityStopped()
        assertFalse(over.open.value)
    }

    @Test
    fun aWaitGivenUpSaysItIsOver() = runTest {
        val gate = InstanceGate { true }
        gate.activityStarted()
        val waits = mutableListOf<Boolean>()
        val job = launch { gate.pass { waits += it } }
        runCurrent()
        assertEquals(listOf(true), waits)
        job.cancelAndJoin()
        assertEquals(listOf(true, false), waits)
    }

    // --- A second try ------------------------------------------------------------------------

    @Test
    fun aSecondTryGoesOnFromTheOneThatFailed() = runTest {
        val files = listOf("a.txt", "b.txt", "c.txt", "d.txt")
        val sent = mutableListOf<String>()
        var kept = Kept().into("/home/frodo")
        var refuse = true
        val send: suspend (String) -> Unit = { name ->
            if (name == "c.txt" && refuse) throw IOException("too large")
            sent += name
        }
        try {
            sendFrom(files, kept.done, {}, { kept = kept.copy(done = it) }, send)
            fail("the third is refused")
        } catch (e: IOException) {
            assertEquals(2, kept.done)
        }
        // Save again, into the same folder: the first two are not sent twice.
        refuse = false
        kept = kept.into("/home/frodo")
        sendFrom(files, kept.done, {}, { kept = kept.copy(done = it) }, send)
        assertEquals(listOf("a.txt", "b.txt", "c.txt", "d.txt"), sent)
        assertEquals(4, kept.done)
        // Another folder has none of them: from the first.
        assertEquals(0, kept.into("/home/frodo/recipes").done)
    }

    // --- A share's addresses -----------------------------------------------------------------

    @Test
    fun aShareMayNotNameThisAppsOwnFiles() {
        val own = setOf("eu.llembas.android.files", "eu.llembas.android.androidx-startup")
        // Another app's provider is what a share is.
        assertFalse(SharedSource.refused("content", "com.example.gallery.provider", own))
        assertFalse(SharedSource.refused("content", "media", own))
        // A path is read with this app's own rights; so is anything that is not a provider's address.
        assertTrue(SharedSource.refused("file", "", own))
        assertTrue(SharedSource.refused("file", null, own))
        assertTrue(SharedSource.refused("http", "example.org", own))
        assertTrue(SharedSource.refused(null, null, own))
        assertTrue(SharedSource.refused("content", "", own))
        // This app's own provider, however it is spelled: with a user before it, in capitals.
        assertTrue(SharedSource.refused("content", "eu.llembas.android.files", own))
        assertTrue(SharedSource.refused("content", "10@eu.llembas.android.files", own))
        assertTrue(SharedSource.refused("CONTENT", "EU.LLEMBAS.ANDROID.FILES", own))
        assertTrue(SharedSource.refused("content", "eu.llembas.android.androidx-startup", own))
    }

    @Test
    fun whatAnAddressOpensOntoMayNotBeInsideThisAppsData() {
        val roots = listOf("/data/user/0/eu.llembas.android", "/data/data/eu.llembas.android", "/storage/emulated/0/Android/data/eu.llembas.android/")
        assertTrue(SharedSource.inside("/data/user/0/eu.llembas.android/files/datastore/prefs.preferences_pb", roots))
        assertTrue(SharedSource.inside("/data/data/eu.llembas.android/cache/shared/x/notes.md", roots))
        assertTrue(SharedSource.inside("/storage/emulated/0/Android/data/eu.llembas.android/cache/a", roots))
        assertTrue(SharedSource.inside("/data/user/0/eu.llembas.android", roots))
        // Another app whose name begins as this one's does, a picture of the phone, a pipe.
        assertFalse(SharedSource.inside("/data/user/0/eu.llembas.android.debug/files/a", roots))
        assertFalse(SharedSource.inside("/storage/emulated/0/DCIM/map.jpg", roots))
        assertFalse(SharedSource.inside("pipe:[4711]", roots))
        assertFalse(SharedSource.inside("", roots))
        assertFalse(SharedSource.inside("/anything", listOf("")))
    }

    // --- A new file --------------------------------------------------------------------------

    @Test
    fun aNewFileIsNotAskedForWhereTheDirectoryHoldsTheName() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val api = ApiClient(server.url("/").toString().trimEnd('/'), "lmb_test")
            val listing = requireNotNull(javaClass.classLoader!!.getResource("files_listing.json")).readText()
            val content = requireNotNull(javaClass.classLoader!!.getResource("file_content.json")).readText()

            // An instance from before `create` would write over lembas.md: it is never asked to.
            server.enqueue(MockResponse().setBody(listing))
            try {
                api.newFile("/home/frodo", "lembas.md")
                fail("the name is taken")
            } catch (e: FileExists) {
                assertEquals(1, server.requestCount)
                assertEquals("GET", server.takeRequest().method)
            }

            // A free name: the directory is read, then the file is made with `create`.
            server.enqueue(MockResponse().setBody(listing))
            server.enqueue(MockResponse().setBody(content))
            assertEquals("/home/frodo/second-breakfast.md", api.newFile("/home/frodo", " second-breakfast.md "))
            assertEquals("GET", server.takeRequest().method)
            val put = server.takeRequest()
            assertEquals("PUT", put.method)
            val said = put.body.readUtf8()
            assertTrue(said, said.contains("\"create\":true"))
            assertTrue(said, said.contains("\"path\":\"/home/frodo/second-breakfast.md\""))
        } finally {
            server.shutdown()
        }
    }

    // --- Copies and names --------------------------------------------------------------------

    @Test
    fun aCopyAnotherAppWasHandedGoesByItsAgeAlone() {
        val shared = folder.newFolder("shared")
        val now = 10_000_000_000L
        val hour = FileCache.SHARED_KEPT_MS

        fun copy(name: String, folderAge: Long, fileAge: Long): File {
            val dir = File(shared, name).apply { mkdirs() }
            val file = File(dir, "report.pdf").apply { writeText("x") }
            file.setLastModified(now - fileAge)
            dir.setLastModified(now - folderAge)
            return dir
        }
        val fresh = copy("fresh", folderAge = 60_000, fileAge = 60_000)
        val old = copy("old", folderAge = hour + 60_000, fileAge = hour + 60_000)
        // A long download: its folder was made long ago, the file was written just now.
        val long = copy("long", folderAge = hour + 60_000, fileAge = 1_000)

        // What the start of the process and the closing of a screen do: the fresh copy is still there for the app that has it.
        FileCache.sweep(shared, now, hour)
        assertTrue(fresh.exists())
        assertTrue(File(fresh, "report.pdf").exists())
        assertFalse(old.exists())
        assertTrue(long.exists())
        // A folder that is not there is nothing to sweep.
        FileCache.sweep(File(shared, "none"), now, hour)
    }

    @Test
    fun aNameFitsAFileSystemInBytesAndKeepsItsExtension() {
        fun bytes(text: String) = text.toByteArray(Charsets.UTF_8).size

        // 120 letters of three bytes each were let through as 360 bytes: more than a file system takes.
        val long = "指".repeat(120) + ".pdf"
        val safe = FileKinds.safeName(long)
        assertTrue(bytes(safe).toString(), bytes(safe) <= FileKinds.NAME_MAX_BYTES)
        assertTrue(safe, safe.endsWith(".pdf"))
        assertTrue(safe, safe.startsWith("指"))
        assertEquals("指".repeat(65) + ".pdf", safe)

        // Half of a pair is not a character: a name of four-byte signs is cut between them.
        val signs = FileKinds.safeName("🗺".repeat(80) + ".png")
        assertTrue(bytes(signs) <= FileKinds.NAME_MAX_BYTES)
        assertEquals("🗺".repeat(49) + ".png", signs)

        // A last dot far from the end is not an extension: the name is cut as a whole.
        val sentence = FileKinds.safeName("a".repeat(100) + "." + "b".repeat(300))
        assertEquals(FileKinds.NAME_MAX_BYTES, bytes(sentence))
        // A smaller cap, for a name that gets a prefix of its own.
        assertTrue(bytes(FileKinds.safeName("ž".repeat(200) + ".md", 80)) <= 80)
        // A short name is left alone, as before.
        assertEquals("map.png", FileKinds.safeName("map.png"))
        assertEquals("a".repeat(196) + ".txt", FileKinds.safeName("a".repeat(196) + ".txt"))
    }

    // --- A ref is not an id ------------------------------------------------------------------

    // An aggregator serves a model whose id is spelled like another connection's ref.
    private val routed = ModelInfo(id = "studio/qwen", name = "Qwen", ref = "router/studio/qwen", provider = "router", connection = "Router")
    private val direct = ModelInfo(id = "qwen", name = "Qwen", ref = "studio/qwen", provider = "studio", connection = "Model Studio")

    @Test
    fun aRefWhoseModelIsGoneIsNotAnotherConnectionsModelOfThatId() {
        // Both there: the ref names the direct one.
        assertEquals(direct, listOf(routed, direct).named("studio/qwen", isRef = true))
        // The direct connection is gone: the ref names nothing here, not the aggregator's model called that.
        assertNull(listOf(routed).named("studio/qwen", isRef = true))
        assertNull(listOf(routed).named("", isRef = true))
        assertNull(listOf(routed).named(null, isRef = true))
        // A name of unknown kind (a setting from before refs) is still an id where no ref matches.
        assertEquals(routed, listOf(routed).named("studio/qwen", isRef = false))
        assertEquals(routed, listOf(routed).named("studio/qwen"))

        // The assistant's sheet on a continued chat that says its connection.
        val sheet = AssistUiState(models = listOf(routed), modelId = "studio/qwen", modelIsRef = true)
        assertNull(sheet.modelInfo)
        assertEquals(routed, sheet.copy(modelIsRef = false).modelInfo)
    }

    @Test
    fun aSchedulesOwnRefTicksItsRowOrNone() {
        val routedPlan = SchedulingModel(id = "studio/qwen", name = "Qwen", ref = "router/studio/qwen", connection = "Router")
        val directPlan = SchedulingModel(id = "qwen", name = "Qwen", ref = "studio/qwen", connection = "Model Studio")
        val read = EditState(models = listOf(routedPlan), model = "studio/qwen", modelIsRef = true)
        assertNull(read.modelInfo)
        assertEquals(directPlan, read.copy(models = listOf(routedPlan, directPlan)).modelInfo)
        // A row picked from the list is found as before.
        assertEquals(routedPlan, read.copy(model = routedPlan.key, modelIsRef = false).modelInfo)
        assertNull(listOf(routedPlan).named("studio/qwen", isRef = true))
        assertEquals(routedPlan, listOf(routedPlan).named("studio/qwen", isRef = false))
    }

    // --- Where a file is now, and what an archive holds ----------------------------------------

    private fun contract(name: String): String = requireNotNull(javaClass.classLoader!!.getResource("$name.json")) {
        "contract/$name.json is missing"
    }.readText()

    @Test
    fun openInFilesGoesWhereTheFileIsNow() = runBlocking {
        val node = LembasJson.decodeFromString(FileNode.serializer(), contract("file_node"))
        assertEquals("node6", node.id)
        assertEquals("video", node.fileKind)
        assertFalse(node.trashed)
        // Written to Generated/, moved to Clips since: Files opens at Clips.
        assertEquals(Whereabouts.Folder("/home/frodo/Clips"), whereabouts(node, "/home/frodo/Generated/video.mp4"))
        assertEquals(Whereabouts.Trashed, whereabouts(node.copy(trashed = true), "/home/frodo/Generated/video.mp4"))
        // An instance that cannot say where a node is: the folder it was written to, as before.
        assertEquals(Whereabouts.Folder("/home/frodo/Generated"), whereabouts(null, "/home/frodo/Generated/video.mp4"))
        assertEquals(Whereabouts.Folder("/home/frodo/Clips"), whereabouts(node.copy(parent = ""), "/home/frodo/Generated/video.mp4"))

        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody(contract("file_node")))
            val api = ApiClient(server.url("/").toString().trimEnd('/'), "lmb_test")
            assertEquals("/home/frodo/Clips", api.fileNode("node6").parent)
            val asked = server.takeRequest()
            assertEquals("GET", asked.method)
            assertEquals("/api/v2/files/node?id=node6", asked.path)
            assertEquals("Bearer lmb_test", asked.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun anArchiveSaysWhatIsInsideIt() {
        val file = LembasJson.decodeFromString(FileContent.serializer(), contract("file_content_archive"))
        assertEquals("archive", file.fileKind)
        // Still nothing to draw as text: the listing is beside the refusal, not instead of it.
        assertEquals(FileView.Details, FileKinds.viewer(file.fileKind))
        val archive = requireNotNull(file.archive)
        assertNull(archive.problem)
        assertFalse(archive.truncated)
        assertEquals(2, archive.total)
        assertEquals(listOf("maps/", "maps/shire.png"), archive.entries.map { it.name })
        assertEquals(listOf(true, false), archive.entries.map { it.dir })
        assertEquals(listOf(null, 2048L), archive.entries.map { it.size })
        // Every other kind, and an instance from before the listing, says nothing of one.
        assertNull(LembasJson.decodeFromString(FileContent.serializer(), contract("file_content")).archive)
        // No listing, and why: the instance's sentence is what is shown.
        val busy = LembasJson.decodeFromString(
            ArchiveListing.serializer(),
            """{"entries": [], "total": null, "truncated": false, "problem": "busy", "message": "Open it again shortly."}""",
        )
        assertEquals("busy", busy.problem)
        assertEquals("Open it again shortly.", busy.message)
    }

    // --- A transfer given up -----------------------------------------------------------------

    @Test
    fun aDownloadGivenUpDoesNotWaitForTheServer() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            // The server says more is coming and sends no more: the read waits.
            server.enqueue(MockResponse().setBody("0123456789").setHeader("Content-Length", "100000"))
            val api = ApiClient(server.url("/").toString().trimEnd('/'), "lmb_test")
            val began = CompletableDeferred<Unit>()
            val transfer = launch(Dispatchers.Default) {
                api.download(api.rawFile("/home/frodo/map.png"), ByteArrayOutputStream(), progress = { _, _ -> began.complete(Unit) })
            }
            withTimeout(10_000) { began.await() }
            val ended = withTimeoutOrNull(5_000) {
                transfer.cancelAndJoin()
                true
            }
            assertEquals("Cancel must end the read, not wait for its timeout", true, ended)
            assertTrue(transfer.isCancelled)
        } finally {
            server.shutdown()
        }
    }
}
