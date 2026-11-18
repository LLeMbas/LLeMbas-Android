package eu.llembas.android

import eu.llembas.android.api.ApiClient
import eu.llembas.android.api.ApiException
import eu.llembas.android.api.Chat
import eu.llembas.android.api.MediaFor
import eu.llembas.android.api.ModelInfo
import eu.llembas.android.api.refusal
import eu.llembas.android.ui.chat.ChatState
import eu.llembas.android.ui.chat.Draft
import eu.llembas.android.ui.chat.MEDIA_DICTATION
import eu.llembas.android.ui.chat.MEDIA_SPEECH
import eu.llembas.android.ui.chat.fallbackToSay
import eu.llembas.android.ui.chat.mediaFor
import kotlinx.coroutines.test.runTest
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
 * Speech follows the chat's model (server 2.0.0-beta.15): a transcription or a speech request
 * says which chat it is for -- `chat_id`, or `chat_model` before the chat exists -- and never
 * both; another app's dictation says neither.
 */
class MediaForTest {
    private lateinit var server: MockWebServer

    @Before
    fun up() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun down() = server.shutdown()

    private fun client() = ApiClient(server.url("/").toString().trimEnd('/'), "lmb_test")

    @Test
    fun aChatThatExistsIsNamedByItsIdAlone() {
        assertEquals(MediaFor(chatId = "c1"), MediaFor.of("c1", "shire/pippin"))
        assertEquals(MediaFor(chatModel = "shire/pippin"), MediaFor.of(null, "shire/pippin"))
        assertEquals(MediaFor(chatModel = "shire/pippin"), MediaFor.of(" ", "shire/pippin"))
        assertEquals(MediaFor.DEFAULT, MediaFor.of(null, null))
        assertEquals(MediaFor.DEFAULT, MediaFor.of("", " "))
    }

    @Test
    fun theTranscriptionFieldsSayWhichChat() {
        assertEquals(listOf("chat_id" to "c1"), ApiClient.transcriptionFields(null, MediaFor.of("c1", "shire/pippin")))
        assertEquals(
            listOf("language" to "sk", "chat_model" to "shire/pippin"),
            ApiClient.transcriptionFields("sk", MediaFor.of(null, "shire/pippin")),
        )
        assertEquals(emptyList<Pair<String, String>>(), ApiClient.transcriptionFields(null, MediaFor.DEFAULT))
    }

    @Test
    fun theSpeechBodySaysWhichChat() {
        val inChat = ApiClient.speechBody("Hello", MediaFor.of("c1", "shire/pippin")).toString()
        assertTrue(inChat, inChat.contains("\"chat_id\":\"c1\""))
        assertFalse(inChat, inChat.contains("chat_model"))
        assertTrue(inChat, inChat.contains("\"input\":\"Hello\""))
        val before = ApiClient.speechBody("Hello", MediaFor.of(null, "shire/pippin")).toString()
        assertTrue(before, before.contains("\"chat_model\":\"shire/pippin\""))
        assertFalse(before, before.contains("chat_id"))
        val none = ApiClient.speechBody("Hello", MediaFor.DEFAULT).toString()
        assertFalse(none, none.contains("chat_"))
    }

    @Test
    fun aTranscriptionCarriesTheChatAndReadsTheFallback() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"text":"Second breakfast"}""")
                .setHeader("X-Lembas-Source", "Bree%20%2B%20Inn").setHeader("X-Lembas-Fallback", "1"),
        )
        val heard = client().transcribe(ByteArray(64), media = MediaFor.of("c1", null))
        assertEquals("Second breakfast", heard.text)
        assertEquals("Bree + Inn", heard.fallback)
        val request = server.takeRequest()
        assertEquals("/v1/audio/transcriptions", request.path)
        val body = request.body.readUtf8()
        assertTrue(body, body.contains("name=\"chat_id\""))
        assertFalse(body, body.contains("chat_model"))
    }

    @Test
    fun anotherAppsDictationNamesNoChat() = runTest {
        server.enqueue(MockResponse().setBody("""{"text":"Elevenses"}""").setHeader("X-Lembas-Source", "stt%2Fsmall"))
        val heard = client().transcribe(ByteArray(64))
        assertEquals("Elevenses", heard.text)
        // The source is named, and it is the one expected: nothing to say.
        assertNull(heard.fallback)
        val body = server.takeRequest().body.readUtf8()
        assertFalse(body, body.contains("chat_id"))
        assertFalse(body, body.contains("chat_model"))
    }

    @Test
    fun speechCarriesTheModelOfAChatNotMadeYet() = runTest {
        server.enqueue(MockResponse().setBody("mp3"))
        val spoken = client().speech("Hello", MediaFor.of(null, "shire/pippin"))
        // An older server names no source at all.
        assertNull(spoken.fallback)
        assertEquals(3, spoken.audio.size)
        val request = server.takeRequest()
        assertEquals("/v1/audio/speech", request.path)
        assertTrue(request.body.readUtf8().contains("\"chat_model\":\"shire/pippin\""))
    }

    @Test
    fun aRefusedTranscriptionSaysTheServersSentence() = runTest {
        // `/v1` refuses in OpenAI's shape, not `/api/v2`'s `detail`.
        server.enqueue(
            MockResponse().setResponseCode(502)
                .setBody("""{"error":{"message":"The speech model did not answer.","type":"upstream_error","code":502}}"""),
        )
        try {
            client().transcribe(ByteArray(64), media = MediaFor.of("c1", null))
            fail("a 502 is a refusal")
        } catch (e: ApiException) {
            assertEquals(502, e.status)
            assertEquals("The speech model did not answer.", e.message)
        }
    }

    @Test
    fun aRefusalIsReadInBothShapes() {
        assertEquals("No.", refusal("""{"detail":"No."}"""))
        assertEquals("Too long.", refusal("""{"error":{"message":"Too long.","code":413}}"""))
        assertNull(refusal("""{"error":"plain"}"""))
        assertNull(refusal("<html>Bad gateway</html>"))
        assertNull(refusal(""))
    }

    @Test
    fun aFallbackIsNamedOnlyWhenTheServerSaysBoth() {
        assertEquals("Bree", ApiClient.fallbackSource("1", "Bree"))
        assertNull(ApiClient.fallbackSource(null, "Bree"))
        assertNull(ApiClient.fallbackSource("1", null))
        assertNull(ApiClient.fallbackSource("0", "Bree"))
        // A name that is not percent-encoding stays as it came.
        assertEquals("100%", ApiClient.fallbackSource("1", "100%"))
    }

    @Test
    fun theChatScreenNamesItsChatOrTheModelItWillBeOn() {
        val models = listOf(ModelInfo(id = "pippin", ref = "shire/pippin", default = true), ModelInfo(id = "merry", ref = "buckland/merry"))
        assertEquals(MediaFor(chatModel = "shire/pippin"), ChatState(models = models).mediaFor)
        assertEquals(MediaFor(chatModel = "buckland/merry"), ChatState(models = models, draft = Draft(model = "buckland/merry")).mediaFor)
        assertEquals(MediaFor(chatId = "c1"), ChatState(models = models, chat = Chat(id = "c1", model = "merry")).mediaFor)
        assertEquals(MediaFor.DEFAULT, ChatState().mediaFor)
    }

    @Test
    fun aFallbackIsSaidOncePerKind() {
        assertTrue(fallbackToSay(emptySet(), MEDIA_DICTATION))
        assertFalse(fallbackToSay(setOf(MEDIA_DICTATION), MEDIA_DICTATION))
        assertTrue(fallbackToSay(setOf(MEDIA_DICTATION), MEDIA_SPEECH))
    }
}
