package eu.llembas.android

import eu.llembas.android.api.ApiClient
import eu.llembas.android.api.ApiException
import eu.llembas.android.api.FileExists
import eu.llembas.android.api.LembasJson
import eu.llembas.android.api.McpServerList
import eu.llembas.android.api.MediaJob
import eu.llembas.android.api.MediaJobList
import eu.llembas.android.api.MediaView
import eu.llembas.android.api.ModelInfo
import eu.llembas.android.api.ModelList
import eu.llembas.android.api.Raw
import eu.llembas.android.api.ToolCall
import eu.llembas.android.api.forChat
import eu.llembas.android.api.signInAddress
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Generated media, its jobs, a new file that must be new, what a model is for and the MCP
 * servers, as the pinned contract has them and as the client asks for them.
 */
class MediaContractTest {
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
    fun aCallCarriesItsFileOrItsJob() {
        val calls = LembasJson.parseToJsonElement(contract("tool_media")).jsonObject.getValue("calls")
        val (picture, video) = LembasJson.decodeFromJsonElement(ListSerializer(ToolCall.serializer()), calls)
        // A picture is an attachment as before, and a file of the tree as well.
        assertEquals("att1", picture.image!!.id)
        val media = picture.media!!
        assertEquals(MediaView.Picture, media.view)
        assertEquals("node8", media.nodeId)
        assertEquals("/home/frodo/Generated/image-20260102-030405.png", media.path)
        assertEquals("image-20260102-030405.png", media.title)
        assertEquals(180_000L, media.size)
        assertEquals(1024, media.width)
        assertEquals(0.0, media.seconds, 0.0)
        assertNull(picture.mediaJob)
        // A video is a job: the call says which, and that it was running when it returned.
        assertNull(video.media)
        assertNull(video.image)
        val job = MediaJob.of(video.mediaJob!!)
        assertEquals("mjob1", job.id)
        assertEquals("video", job.kind)
        assertTrue(job.underWay)
    }

    @Test
    fun theCardIsChosenByWhatWasAskedFor() {
        fun view(kind: String) = LembasJson.decodeFromString(eu.llembas.android.api.GeneratedMedia.serializer(), """{"kind":"$kind"}""").view
        assertEquals(MediaView.Picture, view("image"))
        assertEquals(MediaView.Picture, view("image_edit"))
        assertEquals(MediaView.Video, view("video"))
        assertEquals(MediaView.Sound, view("music"))
        assertEquals(MediaView.Details, view("model3d"))
        // A kind this app does not know is a file with details, never something played or drawn.
        assertEquals(MediaView.Details, view("hologram"))
    }

    @Test
    fun aJobUnderWayDoneAndFailed() {
        val running = LembasJson.decodeFromString(MediaJob.serializer(), contract("media_job"))
        assertTrue(running.underWay)
        assertFalse(running.unsuccessful)
        assertEquals(42.0, running.seconds, 0.0)
        assertNull(running.result)
        assertFalse(running.told)

        val done = LembasJson.decodeFromString(MediaJob.serializer(), contract("media_job_done"))
        assertFalse(done.underWay)
        assertFalse(done.unsuccessful)
        assertEquals(MediaView.Video, done.result!!.view)
        assertEquals("node7", done.result!!.nodeId)
        assertEquals(5.0, done.result!!.seconds, 0.0)
        assertEquals(0.1, done.cost!!.amount, 1e-9)
        assertTrue(done.told)

        val failed = LembasJson.decodeFromString(MediaJob.serializer(), contract("media_job_failed"))
        assertTrue(failed.unsuccessful)
        assertEquals("ComfyUI ran out of video memory in KSampler.", failed.error)

        assertEquals(listOf("running", "done", "failed"), LembasJson.decodeFromString(MediaJobList.serializer(), contract("media_jobs")).jobs.map { it.state })
        // A state this app does not know ends the following unless the server says it is still active.
        assertFalse(MediaJob(id = "j", state = "paused").underWay)
        assertTrue(MediaJob(id = "j", state = "paused", active = true).underWay)
    }

    @Test
    fun theJobsAreAskedForReadAndCancelled() = runTest {
        val api = client()
        server.enqueue(MockResponse().setBody(contract("media_jobs")))
        assertEquals(3, api.mediaJobs("chat1").size)
        assertEquals("/api/v2/media/jobs?chat_id=chat1", server.takeRequest().path)
        server.enqueue(MockResponse().setBody(contract("media_jobs")))
        api.mediaJobs("chat1", active = true)
        assertEquals("/api/v2/media/jobs?chat_id=chat1&active=true", server.takeRequest().path)

        server.enqueue(MockResponse().setBody(contract("media_job")))
        assertEquals("mjob1", api.mediaJob("mjob1").id)
        val read = server.takeRequest()
        assertEquals("GET", read.method)
        assertEquals("/api/v2/media/jobs/mjob1", read.path)
        assertEquals("Bearer lmb_test", read.getHeader("Authorization"))

        server.enqueue(MockResponse().setBody(contract("media_job_failed")))
        assertEquals("failed", api.cancelMediaJob("mjob3").state)
        val cancel = server.takeRequest()
        assertEquals("DELETE", cancel.method)
        assertEquals("/api/v2/media/jobs/mjob3", cancel.path)

        // Somebody else's job, or one that is gone: the server's sentence.
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"That job no longer exists."}"""))
        try {
            api.mediaJob("mjob9")
            fail("a 404 is no job")
        } catch (e: ApiException) {
            assertEquals("That job no longer exists.", e.message)
        }
    }

    @Test
    fun bytesAreAskedForByPathByVersionByNodeAndByAttachment() {
        val api = client()
        fun path(what: Raw, inline: Boolean = false) = api.raw(what, inline = inline).url.let { it.encodedPath + "?" + it.encodedQuery }
        assertEquals("/api/v2/files/raw?path=%2Fhome%2Ffrodo%2Fa.mp4", path(Raw.Path("/home/frodo/a.mp4")))
        assertEquals("/api/v2/files/raw?path=%2Fhome%2Ffrodo%2Fa.md&version=ver3", path(Raw.Path("/home/frodo/a.md", "ver3")))
        assertEquals("/api/v2/files/raw?node=node7&inline=1", path(Raw.Node("node7"), inline = true))
        assertEquals("/api/v2/attachments/att1", api.raw(Raw.Attached("att1")).url.encodedPath)
        // The token is in the header of each, and in the address of none.
        for (what in listOf(Raw.Path("/a"), Raw.Node("node7"), Raw.Attached("att1"))) {
            val request = api.raw(what, range = 0L..9L)
            assertEquals("Bearer lmb_test", request.header("Authorization"))
            assertEquals("bytes=0-9", request.header("Range"))
            assertFalse(request.url.toString().contains("lmb_test"))
        }
        // A piece of a file by its id is the same Range request a piece by its path is.
        server.enqueue(MockResponse().setResponseCode(206).setBody("0123456789"))
        assertEquals("0123456789", api.rawBytes(Raw.Node("node7"), 10, 19).toString(Charsets.UTF_8))
        val piece = server.takeRequest()
        assertEquals("/api/v2/files/raw?node=node7", piece.path)
        assertEquals("bytes=10-19", piece.getHeader("Range"))
    }

    @Test
    fun aNewFileIsNeverWrittenOverAnother() = runTest {
        val api = client()
        server.enqueue(MockResponse().setResponseCode(201).setBody(contract("file_content")))
        api.saveFile("/home/frodo/new.md", "", create = true)
        val said = server.takeRequest().body.readUtf8()
        assertTrue(said, said.contains("\"create\":true"))
        assertFalse(said, said.contains("base"))

        server.enqueue(MockResponse().setResponseCode(409).setBody(contract("file_create_exists")))
        try {
            api.saveFile("/home/frodo/lembas.md", "", create = true)
            fail("a name that is taken makes no file")
        } catch (e: FileExists) {
            assertEquals("There is already something called lembas.md here.", e.message)
        }
        server.takeRequest()
        // A save that is not a new file does not say `create`.
        server.enqueue(MockResponse().setBody(contract("file_content")))
        api.saveFile("/home/frodo/lembas.md", "x", base = "abab")
        assertFalse(server.takeRequest().body.readUtf8().contains("create"))
    }

    @Test
    fun onlyAModelToChatWithIsOffered() = runTest {
        // The pinned lists say `chat` for every model; a list that named another kind loses it here.
        assertTrue(LembasJson.decodeFromString(ModelList.serializer(), contract("models")).models.all { it.capability == "chat" && it.forChat })
        val mixed = """{"models": [
            {"id": "qwen", "capability": "chat"},
            {"id": "embedder", "capability": "embedding"},
            {"id": "painter", "capability": "image"},
            {"id": "old"},
            {"id": "novel", "capability": "something-new"}], "subagents_cap": 4}"""
        val list = LembasJson.decodeFromString(ModelList.serializer(), mixed)
        assertEquals(listOf("qwen", "old"), list.models.forChat().map { it.id })
        assertEquals(listOf("qwen", "old"), list.forChat().models.map { it.id })
        assertEquals(4, list.forChat().subagentsCap)
        // What the pickers and the assistant's setting are filled from is already narrowed.
        val api = client()
        server.enqueue(MockResponse().setBody(mixed))
        assertEquals(listOf("qwen", "old"), api.models().map(ModelInfo::id))
        server.enqueue(MockResponse().setBody(mixed))
        assertEquals(listOf("qwen", "old"), api.agentModelList("dev1").models.map(ModelInfo::id))
    }

    @Test
    fun theMcpServersAndWhoHasToSignIn() = runTest {
        val servers = LembasJson.decodeFromString(McpServerList.serializer(), contract("mcp_servers")).servers
        assertEquals(listOf("Tracker", "Mail", "Notes", "Wiki"), servers.map { it.label })
        assertEquals(listOf("ready", "signed_in", "signed_out", "needs_signin"), servers.map { it.auth.state })
        assertEquals(listOf(false, false, true, true), servers.map { it.auth.needsSignIn })
        assertEquals("frodo@example.org", servers[1].auth.account)
        assertTrue(servers[1].auth.renews)
        // One's own sign-in has a page; a shared one has none for a person who is no administrator.
        assertEquals("https://lembas.example.org/settings/mcp/mcp3/sign-in", servers[2].auth.signinUrl)
        assertTrue(servers[3].auth.shared)
        assertNull(servers[3].auth.signinUrl)

        server.enqueue(MockResponse().setBody(contract("mcp_servers")))
        assertEquals(4, client().mcpServers().size)
        assertEquals("/api/v2/mcp", server.takeRequest().path)
    }

    @Test
    fun aSignInOpensAPageOfTheInstanceAndNothingElse() {
        val base = "https://lembas.example.org"
        val page = "/settings/mcp/mcp3/sign-in"
        assertEquals(base + page, signInAddress(base, base + page))
        assertEquals(base + page, signInAddress(base, page))
        assertEquals(base + page, signInAddress("$base/", page))
        // The instance named itself otherwise (another name, another port): the same page, on the instance the app knows.
        assertEquals(base + page, signInAddress(base, "https://other.example.net$page"))
        assertEquals("$base$page?x=1", signInAddress(base, "http://lembas.example.org:8080$page?x=1"))
        assertEquals("http://lembas.example.org:8080$page", signInAddress("http://lembas.example.org:8080", "http://lembas.example.org:8080$page"))
        // Nothing that is not a page, and never a host hidden in a path or before an @.
        assertNull(signInAddress(base, null))
        assertNull(signInAddress(base, "  "))
        assertNull(signInAddress(base, "javascript:alert(1)"))
        assertNull(signInAddress(base, "intent://settings#Intent;end"))
        assertEquals("$base/settings", signInAddress(base, "//other.example.net/settings"))
        assertEquals("$base/settings", signInAddress(base, "https://lembas.example.org@other.example.net/settings"))
        // The app's token is in none of them: there is no place for one.
        assertFalse(signInAddress(base, base + page)!!.contains("token"))
    }
}
