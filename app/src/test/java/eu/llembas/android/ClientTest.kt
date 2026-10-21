package eu.llembas.android

import eu.llembas.android.api.ApiClient
import eu.llembas.android.api.ApiException
import eu.llembas.android.api.CardAnswer
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
}
