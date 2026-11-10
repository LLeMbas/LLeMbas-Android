package eu.llembas.android

import eu.llembas.android.actions.Args
import eu.llembas.android.actions.NavApp
import eu.llembas.android.actions.NavIntents
import eu.llembas.android.actions.PhoneActions
import eu.llembas.android.actions.Place
import eu.llembas.android.api.AskFrame
import eu.llembas.android.api.CardAnswer
import eu.llembas.android.api.LembasJson
import eu.llembas.android.api.PhoneResult
import eu.llembas.android.ui.chat.phoneFacts
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneActionsTest {
    private fun args(json: String) = Args(LembasJson.parseToJsonElement(json).jsonObject)

    @Test
    fun placesAreReadLeniently() {
        val a = args("""{"destination": {"name": "Rivendell", "lat": "47.1", "lon": 8.5}, "stops": ["Bree", {"query": "inn"}], "hour": "7"}""")
        assertEquals(Place("Rivendell", 47.1, 8.5), a.place("destination"))
        assertEquals(listOf(Place(query = "Bree"), Place(query = "inn")), a.places("stops"))
        assertEquals(7, a.int("hour"))
        assertNull(a.place("missing"))
    }

    @Test
    fun theNavigationAppIsTheOneInstalledOrAsked() {
        val both = listOf(NavApp.GoogleMaps, NavApp.OsmAnd)
        assertEquals(NavApp.OsmAnd, NavApp.chosen("", listOf(NavApp.OsmAnd)))
        // Both installed and nothing chosen: the card asks.
        assertNull(NavApp.chosen("", both))
        assertEquals(NavApp.GoogleMaps, NavApp.chosen("google", both))
        assertNull(NavApp.chosen(NavApp.PREF_ASK, both))
        // Chosen but since uninstalled: the one there is.
        assertEquals(NavApp.GoogleMaps, NavApp.chosen("osmand", listOf(NavApp.GoogleMaps)))
    }

    @Test
    fun aRouteForOsmAndIsGpxWithItsPointsInOrder() {
        val gpx = NavIntents.gpx(listOf(Place("Bree & Co", 1.0, 2.0), Place("Rivendell", 3.0, 4.0)))
        assertTrue(gpx.contains("<rtept lat=\"1.0\" lon=\"2.0\"><name>Bree &amp; Co</name></rtept><rtept lat=\"3.0\" lon=\"4.0\">"))
    }

    @Test
    fun clockTimesAndNumbersAreCheckedAgain() {
        assertEquals(7 to 5, PhoneActions.clock("07:05"))
        assertNull(PhoneActions.clock("24:00"))
        assertNull(PhoneActions.clock("7 am"))
        // A name the messaging app would have to find is left out: the person picks the contact.
        assertEquals(listOf("+421 900 123 456"), PhoneActions.smsNumbers(listOf("+421 900 123 456", "Sam")))
        assertTrue(PhoneActions.urlAllowed("HTTPS://example.org"))
        assertFalse(PhoneActions.urlAllowed("javascript:alert(1)"))
        assertFalse(PhoneActions.urlAllowed("https://"))
    }

    @Test
    fun theCardSaysWhatWillHappenFromTheContract() {
        val frame = requireNotNull(javaClass.classLoader!!.getResource("event_ask_phone.json")).readText()
        val cards = LembasJson.decodeFromString(AskFrame.serializer(), frame).cards
        assertEquals(listOf("phone_action"), cards.map { it.kind }.distinct())
        val phones = cards.map { requireNotNull(it.items.single().phone) }
        assertEquals("07:00 · mon tue wed thu fri · Work", phoneFacts(phones[0]))
        assertEquals("Bakery (48.894, 18.044) · Home (1 Bagshot Row, Hobbiton) · drive", phoneFacts(phones[1]))
        assertEquals("Bakery (Bakery, Bywater) · Home (1 Bagshot Row, Hobbiton)", phoneFacts(phones[2]))
        assertEquals("", phoneFacts(phones[3]))
        // The answer the server reads: {"result": {"status", "message"}}, nothing else.
        assertEquals(
            """{"result":{"status":"done","message":"ok"}}""",
            LembasJson.encodeToString(CardAnswer.serializer(), CardAnswer(result = PhoneResult("done", "ok"))),
        )
    }
}
