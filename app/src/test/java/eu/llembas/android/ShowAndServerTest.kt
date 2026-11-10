package eu.llembas.android

import eu.llembas.android.api.Discovery
import eu.llembas.android.api.LembasJson
import eu.llembas.android.api.ServerFit
import eu.llembas.android.api.ToolCall
import eu.llembas.android.api.fit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the server's contract fixtures cannot say: how the app reads a `show` card the server
 * never wrote (an unknown kind), and which servers it works with at all. The server's own `show`
 * cards are in `contract/tool_show.json` and read by `ContractTest`.
 */
class ShowAndServerTest {
    @Test
    fun aGeneratedPictureIsAnAttachmentIdAndASize() {
        // services/images/tool.py: `image: {id, width, height}` on the tool event; no URL at all.
        val call = LembasJson.decodeFromString(
            ToolCall.serializer(),
            """{"id":"t1","name":"image_generate","status":"ok","attachment_id":"att1",
               "image":{"id":"att1","width":1216,"height":832},"block":{"kind":"image","title":"a leaf"}}""",
        )
        assertEquals("att1", call.image!!.id)
        assertEquals(1216, call.image!!.width)
        assertEquals(832, call.image!!.height)
        assertNull(LembasJson.decodeFromString(ToolCall.serializer(), """{"name":"read"}""").image)
        assertEquals("image_generate", eu.llembas.android.ui.chat.IMAGE_TOOL)
    }

    private fun call(json: String) = LembasJson.decodeFromString(ToolCall.serializer(), json)

    @Test
    fun anUnknownKindIsPlainText() {
        val c = call("""{"name": "show", "show": {"path": "x.svg", "kind": "svg"}}""")
        assertEquals("text", c.show!!.viewKind)
        assertNull(call("""{"name": "bash"}""").show)
    }

    private fun discovery(json: String) = LembasJson.decodeFromString(Discovery.serializer(), json)

    @Test
    fun onlyAServerOnTheTwoPointOhProtocolAndAppApiTwoFits() {
        val two = """{"service": "lembas", "version": "2.0.0-beta.1", "protocol": 3, "protocols": [3],
            "app_api": 2, "scopes": ["app", "models"]}"""
        assertEquals(ServerFit.FITS, fit(discovery(two)))
        // A 2.0 `dev` server from before the show route and the files: protocol 3, app API 1.
        assertEquals(ServerFit.TOO_OLD, fit(discovery(two.replace("\"app_api\": 2", "\"app_api\": 1"))))
        // 1.7: protocol 2, the app API already there -- and still refused.
        val old = """{"service": "lembas", "version": "1.7.3", "protocol": 2, "protocols": [1, 2],
            "app_api": 1, "scopes": ["app", "models"]}"""
        assertEquals(ServerFit.TOO_OLD, fit(discovery(old)))
        // A server with no protocol at all is older still.
        assertEquals(ServerFit.TOO_OLD, fit(discovery("""{"service": "lembas", "app_api": 2, "scopes": ["app"]}""")))
        assertEquals(ServerFit.TOO_NEW, fit(discovery(two.replace("\"app_api\": 2", "\"app_api\": 3"))))
        assertEquals(ServerFit.NOT_LEMBAS, fit(discovery("""{"service": "other", "protocol": 3}""")))
    }
}
