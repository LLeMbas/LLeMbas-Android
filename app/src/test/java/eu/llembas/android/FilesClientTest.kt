package eu.llembas.android

import eu.llembas.android.api.ApiClient
import eu.llembas.android.api.ApiException
import eu.llembas.android.api.RangeReader
import eu.llembas.android.api.SaveConflict
import eu.llembas.android.ui.files.StreamBody
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** The file manager's calls, against a server that answers as the pinned contract says. */
class FilesClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun up() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun down() = server.shutdown()

    private fun client() = ApiClient(server.url("/").toString().trimEnd('/'), "lmb_test")

    private fun contract(name: String): String = requireNotNull(javaClass.classLoader!!.getResource("$name.json")) {
        "contract/$name.json is missing"
    }.readText()

    @Test
    fun anUploadIsStreamedIntoItsFolderAndSaysHowFarItIs() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody(contract("files_listing")))
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        val heard = mutableListOf<Long>()
        val body = StreamBody("application/octet-stream".toMediaType(), bytes.size.toLong(), { heard += it }) { ByteArrayInputStream(bytes) }
        val listing = client().uploadFile("/home/frodo", "big.bin", body)
        assertEquals("/home/frodo", listing.path)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v2/files/upload?path=%2Fhome%2Ffrodo", request.path)
        assertEquals("Bearer lmb_test", request.getHeader("Authorization"))
        val sent = request.body.readByteArray()
        val head = String(sent, 0, 400, Charsets.ISO_8859_1)
        assertTrue(head, head.contains("name=\"files\"; filename=\"big.bin\""))
        // Every byte went, in pieces, and the last word on progress is the whole file.
        assertTrue(sent.size > bytes.size)
        assertTrue(heard.size > 1)
        assertEquals(bytes.size.toLong(), heard.last())
        assertTrue(body.isOneShot())
    }

    @Test
    fun aFileOverTheLimitIsRefusedInTheServersSentence() = runTest {
        val sentence = "big.bin is larger than 50 MB, the most that can be uploaded here."
        server.enqueue(MockResponse().setResponseCode(413).setBody("""{"detail":"$sentence"}"""))
        try {
            client().uploadFile("/home/frodo", "big.bin", StreamBody(null, 3, {}) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) })
            fail("a 413 is a refusal")
        } catch (e: ApiException) {
            assertEquals(413, e.status)
            assertEquals(sentence, e.message)
        }
    }

    @Test
    fun aSaveSaysWhatItOpenedAndAConflictWritesNothing() = runTest {
        val api = client()
        server.enqueue(MockResponse().setBody(contract("file_content")))
        val saved = api.saveFile("/home/frodo/lembas.md", "# Lembas\n", base = "abab")
        assertEquals("markdown", saved.fileKind)
        assertEquals(true, saved.writable)
        val first = server.takeRequest()
        assertEquals("PUT", first.method)
        assertEquals("/api/v2/files/content", first.path)
        val said = first.body.readUtf8()
        assertTrue(said, said.contains("\"base\":\"abab\""))
        assertTrue(said, said.contains("\"path\":\"/home/frodo/lembas.md\""))
        assertFalse(said, said.contains("force"))

        // The file changed meanwhile: the server's sentence and what the file holds now.
        server.enqueue(MockResponse().setResponseCode(409).setBody(contract("file_save_conflict")))
        try {
            api.saveFile("/home/frodo/lembas.md", "mine", base = "abab")
            fail("a conflict is not a save")
        } catch (e: SaveConflict) {
            assertEquals("This file changed since you opened it. Reload it, or save anyway.", e.message)
            assertEquals("ab".repeat(32), e.sha)
        }
        server.takeRequest()

        // Save anyway says so; a new file says no base at all.
        server.enqueue(MockResponse().setBody(contract("file_content")))
        api.saveFile("/home/frodo/lembas.md", "mine", base = "abab", force = true)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"force\":true"))
        server.enqueue(MockResponse().setBody(contract("file_content")))
        api.saveFile("/home/frodo/new.md", "")
        assertFalse(server.takeRequest().body.readUtf8().contains("base"))

        // Any other 409 is a refusal like any other.
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":"Not now."}"""))
        try {
            api.saveFile("/home/frodo/lembas.md", "mine", base = "abab")
            fail("a refusal")
        } catch (e: ApiException) {
            assertEquals("Not now.", e.message)
        }
    }

    @Test
    fun aDownloadIsCopiedAsItComesWithTheTokenInItsHeaderOnly() = runTest {
        val api = client()
        server.enqueue(MockResponse().setBody("hello world"))
        val out = ByteArrayOutputStream()
        var last = 0L to 0L
        val done = api.download(api.rawFile("/home/frodo/map of the road.png"), out) { now, total -> last = now to total }
        assertEquals(11L, done)
        assertEquals("hello world", out.toString("UTF-8"))
        assertEquals(11L to 11L, last)
        val request = server.takeRequest()
        assertEquals("/api/v2/files/raw?path=%2Fhome%2Ffrodo%2Fmap%20of%20the%20road.png", request.path)
        assertEquals("Bearer lmb_test", request.getHeader("Authorization"))
        assertFalse(request.requestUrl.toString().contains("lmb_test"))
        assertNull(request.getHeader("Range"))

        // More than the caller will take is refused before it is copied.
        server.enqueue(MockResponse().setBody("hello world"))
        try {
            api.download(api.rawFile("/home/frodo/map.png"), ByteArrayOutputStream(), most = 4)
            fail("too much")
        } catch (e: ApiException) {
            assertEquals(413, e.status)
        }
    }

    @Test
    fun aPieceOfAFileIsARangeRequest() {
        val api = client()
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 4-7/11").setBody("o wo"))
        assertEquals("o wo", api.rawBytes("/home/frodo/song.mp3", 4, 7).toString(Charsets.UTF_8))
        val request = server.takeRequest()
        assertEquals("bytes=4-7", request.getHeader("Range"))
        assertEquals("Bearer lmb_test", request.getHeader("Authorization"))
        assertFalse(request.requestUrl.toString().contains("lmb_test"))

        // A server that ignores the range would hand the wrong bytes: refused, not played.
        server.enqueue(MockResponse().setBody("hello world"))
        try {
            api.rawBytes("/home/frodo/song.mp3", 4, 7)
            fail("the whole file is not the piece asked for")
        } catch (e: ApiException) {
            assertEquals(416, e.status)
        }
    }

    @Test
    fun aPlayerReadsBlockByBlockAndABlockIsFetchedOnce() {
        val file = ByteArray(10) { it.toByte() }
        val asked = mutableListOf<Pair<Long, Long>>()
        val reader = RangeReader(file.size.toLong(), block = 4, keep = 2) { from, to ->
            asked += from to to
            file.copyOfRange(from.toInt(), to.toInt() + 1)
        }
        val buffer = ByteArray(8)
        // A read ends at its block's end; the player asks again for the rest.
        assertEquals(4, reader.read(0, buffer, 0, 8))
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), buffer.copyOf(4))
        assertEquals(2, reader.read(2, buffer, 0, 8))
        assertEquals(listOf(0L to 3L), asked)
        // The last block is short, and the end is the end.
        assertEquals(2, reader.read(8, buffer, 3, 5))
        assertArrayEquals(byteArrayOf(8, 9), buffer.copyOfRange(3, 5))
        assertEquals(listOf(0L to 3L, 8L to 9L), asked)
        assertEquals(-1, reader.read(10, buffer, 0, 8))
        assertEquals(-1, reader.read(-1, buffer, 0, 8))
        assertEquals(0, reader.read(1, buffer, 0, 0))
        // A seek back to a block still kept asks nothing; one that fell out is asked for again.
        assertEquals(1, reader.read(3, buffer, 0, 8))
        assertEquals(2, asked.size)
        assertEquals(4, reader.read(4, buffer, 0, 8))
        assertEquals(4, reader.read(0, buffer, 0, 8))
        assertEquals(listOf(0L to 3L, 8L to 9L, 4L to 7L), asked)
        assertEquals(2, reader.read(8, buffer, 0, 8))
        assertEquals(listOf(0L to 3L, 8L to 9L, 4L to 7L, 8L to 9L), asked)
    }

    @Test
    fun aBatchTheTrashAndTheVersions() = runTest {
        val api = client()
        server.enqueue(MockResponse().setBody(contract("files_batch")))
        val answer = api.batchFiles("move", listOf("/home/frodo/lembas.md", "/home/frodo/map.png"), "/home/frodo/recipes")
        assertEquals(1, answer.done)
        assertEquals(1, answer.failed)
        val batch = server.takeRequest()
        assertEquals("/api/v2/files/batch", batch.path)
        val body = batch.body.readUtf8()
        assertTrue(body, body.contains("\"op\":\"move\""))
        assertTrue(body, body.contains("\"paths\":[\"/home/frodo/lembas.md\",\"/home/frodo/map.png\"]"))
        assertTrue(body, body.contains("\"to\":\"/home/frodo/recipes\""))

        server.enqueue(MockResponse().setBody(contract("files_trash_emptied")))
        assertEquals(1, api.removeTrashed().removed)
        val emptied = server.takeRequest()
        assertEquals("DELETE", emptied.method)
        assertEquals("/api/v2/files/trash", emptied.path)
        server.enqueue(MockResponse().setBody(contract("files_trash_emptied")))
        api.removeTrashed("node5")
        assertEquals("/api/v2/files/trash?id=node5", server.takeRequest().path)

        server.enqueue(MockResponse().setBody(contract("file_versions")))
        api.restoreVersion("/home/frodo/lembas.md", "ver1")
        val restored = server.takeRequest()
        assertEquals("/api/v2/files/versions/restore", restored.path)
        assertTrue(restored.body.readUtf8().contains("\"version\":\"ver1\""))

        // A zip is asked for by its paths, in the body: nothing of the account is in the address.
        val zip = api.zipFiles(listOf("/home/frodo/recipes"))
        assertEquals("POST", zip.method)
        assertTrue(zip.url.toString().endsWith("/api/v2/files/zip"))
        val asked = Buffer().also { zip.body!!.writeTo(it) }.readUtf8()
        assertEquals("""{"paths":["/home/frodo/recipes"]}""", asked)
    }
}
