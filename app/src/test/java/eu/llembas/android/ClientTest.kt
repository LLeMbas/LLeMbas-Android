package eu.llembas.android

import eu.llembas.android.api.ApiClient
import eu.llembas.android.api.ApiException
import eu.llembas.android.api.CardAnswer
import eu.llembas.android.api.ShowCard
import eu.llembas.android.api.normaliseBase
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun up() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun down() = server.shutdown()

    private fun client() = ApiClient(server.url("/").toString().trimEnd('/'), "lmb_test")

    @Test
    fun anAddressBecomesABase() {
        assertEquals("https://llm.example.org", normaliseBase(" llm.example.org/ "))
        assertEquals("http://10.1.2.3:8080", normaliseBase("http://10.1.2.3:8080"))
    }

    @Test
    fun everyCallCarriesTheToken() = runTest {
        server.enqueue(MockResponse().setBody("""{"api":1,"account":{"name":"Frodo","email":"f@shire.test"}}"""))
        assertEquals("Frodo", client().me().account.name)
        val request = server.takeRequest()
        assertEquals("/api/v2/me", request.path)
        assertEquals("Bearer lmb_test", request.getHeader("Authorization"))
    }

    @Test
    fun aRefusalIsTheServersOwnSentence() = runTest {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":"That question is no longer waiting for an answer."}"""))
        try {
            client().answer("c", "card", CardAnswer(verdict = "allow"))
            fail("a 409 must throw")
        } catch (e: ApiException) {
            assertEquals(409, e.status)
            assertEquals("That question is no longer waiting for an answer.", e.message)
        }
    }

    @Test
    fun aCardAnswerIsThePagesFormInJson() = runTest {
        server.enqueue(MockResponse().setBody("""{"answered":true}"""))
        client().answer("c1", "card1", CardAnswer(choices = mapOf("q0" to listOf("Tea"))))
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains(""""choices":{"q0":["Tea"]}"""))
    }

    @Test
    fun aShownFileIsTheShowRouteAsPlainText() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain; charset=utf-8").setBody("# Lembas\n"))
        server.enqueue(MockResponse().setBody("x"))
        val api = client()
        val tree = ShowCard(path = "/home/frodo/recipe.md", nodeId = "node1", versionId = "ver1")
        assertEquals("# Lembas\n", api.shownText("chat1", tree))
        assertEquals("/api/v2/chats/chat1/show?node=node1&version=ver1", server.takeRequest().path)
        api.shownText("chat1", ShowCard(path = "src/oven.py", source = "device", shownId = "shown1"))
        assertEquals("/api/v2/chats/chat1/show?shown=shown1", server.takeRequest().path)
    }

    @Test
    fun aShownFileTheServerWillNotGiveIsItsStatus() = runTest {
        server.enqueue(MockResponse().setResponseCode(415).setBody("""{"detail":"This is not a text file, so it is not shown here."}"""))
        try {
            client().shownText("chat1", ShowCard(nodeId = "n", versionId = "v"))
            fail("a 415 must throw")
        } catch (e: ApiException) {
            assertEquals(415, e.status)
            assertEquals("This is not a text file, so it is not shown here.", e.message)
        }
    }

    @Test
    fun theFilesRoutesTakeThePathAsAQuery() = runTest {
        server.enqueue(MockResponse().setBody("""{"path":"/home/frodo","parent":"/home","entries":[]}"""))
        server.enqueue(MockResponse().setBody("""{"path":"/home/frodo/a b.md","name":"a b.md","text":"hi"}"""))
        val api = client()
        assertEquals("/home", api.files("/home/frodo").parent)
        assertEquals("/api/v2/files?path=%2Fhome%2Ffrodo", server.takeRequest().path)
        assertEquals("hi", api.fileContent("/home/frodo/a b.md").text)
        assertEquals("/api/v2/files/content?path=%2Fhome%2Ffrodo%2Fa%20b.md", server.takeRequest().path)
    }
}
