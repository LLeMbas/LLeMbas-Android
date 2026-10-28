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
 * The `show` card and the server gate. The card's shape is the server's own payload
 * (`services/tools/files.py` `_run_show` for a tree file, `services/shown.py` `payload` for a
 * device's) -- written out here because the server's contract fixtures do not carry a `show`
 * call yet. When they do, this moves into `ContractTest`.
 */
class ShowAndServerTest {
    private fun call(json: String) = LembasJson.decodeFromString(ToolCall.serializer(), json)

    @Test
    fun aTreeFileShowCard() {
        val c = call(
            """
            {"id": "call3", "name": "show", "status": "ok", "path": "/home/frodo/notes/road.md",
             "show": {"path": "/home/frodo/notes/road.md", "name": "road.md", "kind": "markdown",
                      "language": "", "line": 12, "node_id": "n1", "version_id": "v7",
                      "sha": "ab12", "size": 2048, "mime": "text/markdown"},
             "block": {"kind": "show", "title": "/home/frodo/notes/road.md"}}
            """,
        )
        val show = c.show!!
        assertEquals("road.md", show.name)
        assertEquals("markdown", show.viewKind)
        assertEquals(12, show.line)
        assertEquals(listOf("node" to "n1", "version" to "v7"), show.query)
    }

    @Test
    fun aDeviceFileShowCardNamesTheKeptCopy() {
        val c = call(
            """
            {"name": "show", "show": {"path": "src/main.rs", "name": "main.rs", "kind": "code",
             "language": "rust", "line": 0, "node_id": "", "version_id": "", "sha": "cd34",
             "size": 10, "mime": "text/plain", "source": "device", "shown_id": "s9",
             "url": "/api/chats/chat1/shown/s9"}}
            """,
        )
        assertEquals("device", c.show!!.source)
        assertEquals(listOf("shown" to "s9"), c.show!!.query)
        assertEquals("rust", c.show!!.language)
    }

    @Test
    fun anUnknownKindIsPlainText() {
        val c = call("""{"name": "show", "show": {"path": "x.svg", "kind": "svg"}}""")
        assertEquals("text", c.show!!.viewKind)
        assertNull(call("""{"name": "bash"}""").show)
    }

    private fun discovery(json: String) = LembasJson.decodeFromString(Discovery.serializer(), json)

    @Test
    fun onlyAServerOnTheTwoPointOhProtocolFits() {
        val two = """{"service": "lembas", "version": "2.0.0-beta.1", "protocol": 3, "protocols": [3],
            "app_api": 1, "scopes": ["app", "models"]}"""
        assertEquals(ServerFit.FITS, fit(discovery(two)))
        // 1.7: protocol 2, the app API already there -- and still refused.
        val old = """{"service": "lembas", "version": "1.7.3", "protocol": 2, "protocols": [1, 2],
            "app_api": 1, "scopes": ["app", "models"]}"""
        assertEquals(ServerFit.TOO_OLD, fit(discovery(old)))
        // A server with no protocol at all is older still.
        assertEquals(ServerFit.TOO_OLD, fit(discovery("""{"service": "lembas", "app_api": 1, "scopes": ["app"]}""")))
        assertEquals(ServerFit.TOO_NEW, fit(discovery(two.replace("\"app_api\": 1", "\"app_api\": 2"))))
        assertEquals(ServerFit.NOT_LEMBAS, fit(discovery("""{"service": "other", "protocol": 3}""")))
    }
}
