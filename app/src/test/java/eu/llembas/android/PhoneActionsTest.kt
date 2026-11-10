package eu.llembas.android

import eu.llembas.android.actions.Args
import eu.llembas.android.actions.NavApp
import eu.llembas.android.actions.NavIntents
import eu.llembas.android.actions.PhoneActions
import eu.llembas.android.actions.Place
import eu.llembas.android.api.LembasJson
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
    fun aBroadcastNeverNamesASystemAction() {
        assertTrue(PhoneActions.broadcastAllowed("net.dinglisch.android.tasker.ACTION_TASK"))
        assertTrue(PhoneActions.broadcastAllowed("lembas.lights_off"))
        assertFalse(PhoneActions.broadcastAllowed("android.intent.action.BOOT_COMPLETED"))
        assertFalse(PhoneActions.broadcastAllowed("com.android.something"))
        assertFalse(PhoneActions.broadcastAllowed("has spaces"))
    }
}
