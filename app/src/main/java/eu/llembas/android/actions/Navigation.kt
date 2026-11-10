package eu.llembas.android.actions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle

/** A place for a navigation app: coordinates when the model knows them, else words to search for. */
data class Place(val name: String = "", val lat: Double? = null, val lon: Double? = null, val query: String = "") {
    val hasCoordinates: Boolean get() = lat != null && lon != null
    val label: String get() = name.ifBlank { query.ifBlank { if (hasCoordinates) "$lat, $lon" else "" } }

    /** What a map app searches for: the words, else the name. */
    val searchText: String get() = query.ifBlank { name }
}

/** The navigation apps the phone actions drive. */
enum class NavApp(val id: String, val packages: List<String>) {
    GoogleMaps("google", listOf("com.google.android.apps.maps")),

    // OsmAnd: `net.osmand.plus` is OsmAnd+ and the F-Droid build (OsmAnd~); `net.osmand` the free one.
    OsmAnd("osmand", listOf("net.osmand.plus", "net.osmand", "net.osmand.dev")),
    ;

    /** The package of this app on the phone, if it is installed. */
    fun installed(context: Context): String? = packages.firstOrNull { isInstalled(context, it) }

    companion object {
        /**
         * Settings' choice among [installed]: the one named; by default whichever is installed, and
         * with both installed (or "ask"), null -- the card then offers each.
         */
        fun chosen(pref: String, installed: List<NavApp>): NavApp? = when (pref) {
            PREF_ASK -> null
            "" -> installed.singleOrNull()
            else -> installed.firstOrNull { it.id == pref } ?: installed.singleOrNull()
        }

        fun installedApps(context: Context): List<NavApp> = entries.filter { it.installed(context) != null }

        const val PREF_ASK = "ask"

        private fun isInstalled(context: Context, pkg: String): Boolean = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(pkg, 0)
            }
        }.isSuccess
    }
}

/** How a person travels, as the action names it. */
enum class Travel(val id: String, val google: String, val googleNav: String, val osmand: String) {
    Drive("drive", "driving", "d", "car"),
    Walk("walk", "walking", "w", "pedestrian"),
    Bike("bike", "bicycling", "b", "bicycle"),
    Transit("transit", "transit", "d", "public_transport"),
    ;

    companion object {
        fun of(id: String?): Travel = entries.firstOrNull { it.id == id } ?: Drive
    }
}

/** One request to a navigation app: an [Intent], or why it cannot be done in that app. */
sealed interface NavStep {
    data class Launch(val intent: Intent) : NavStep

    data class Unsupported(val reason: String) : NavStep
}

/**
 * What OsmAnd itself says about the route, when the person allowed LLeMbas to ask
 * (OsmAnd › Plugins › … › the app's "Location" access for its API): the phone's position and the
 * route's destination. Null when not allowed -- the actions then use what the model gave.
 */
data class OsmAndInfo(val lat: Double?, val lon: Double?, val destination: Place?) {
    companion object {
        fun of(extras: Bundle?): OsmAndInfo? {
            extras ?: return null
            fun d(key: String) = if (extras.containsKey(key)) extras.getDouble(key) else null
            val dLat = d("destination_lat")
            val dLon = d("destination_lon")
            return OsmAndInfo(d("lat"), d("lon"), if (dLat != null && dLon != null) Place(lat = dLat, lon = dLon) else null)
        }
    }
}

/**
 * The intents, per app. Google Maps: a route to a place with up to a few stops (its `dir` URL),
 * a search near here (`geo:0,0?q=`); it cannot take a stop into a route already running, nor be
 * told to stop -- adding a stop there starts the route again through the stop. OsmAnd: its intent
 * API (`osmand.api://`, a URI protocol; no OsmAnd code in this app): navigate, a route through
 * stops as a GPX passed as data, a search near a position, stop navigation.
 */
object NavIntents {
    private const val GOOGLE_STOPS = 9

    /** [replace]: a route already running is replaced without the map app asking first (OsmAnd asks otherwise). */
    fun navigate(app: NavApp, pkg: String, destination: Place, stops: List<Place>, travel: Travel, info: OsmAndInfo?, replace: Boolean = false): NavStep =
        when (app) {
            NavApp.GoogleMaps -> google(pkg, destination, stops, travel)
            NavApp.OsmAnd -> osmand(pkg, destination, stops, travel, info, if (replace) "true" else "false")
        }

    /** A stop on the way to the route's destination ([destination] from the model, else OsmAnd's own). */
    fun addStop(app: NavApp, pkg: String, stop: Place, destination: Place?, travel: Travel, info: OsmAndInfo?, noDestination: String): NavStep {
        val to = destination?.takeIf { it.hasCoordinates || it.searchText.isNotBlank() } ?: info?.destination ?: return NavStep.Unsupported(noDestination)
        // Planned again from here: the route being replaced is the one the person asked to change.
        return navigate(app, pkg, to, listOf(stop), travel, info, replace = true)
    }

    /**
     * [alongRoute] searches near the running route; OsmAnd is given the phone's position for both
     * (its search ranks along the route itself while one runs), Google Maps searches near here.
     */
    fun searchNearby(app: NavApp, pkg: String, query: String, info: OsmAndInfo?, alongRoute: Boolean = false): NavStep = when {
        app == NavApp.OsmAnd && info?.lat != null && info.lon != null -> NavStep.Launch(
            osmandUri(
                pkg,
                "navigate_search",
                "dest_search_query" to query,
                "search_lat" to info.lat.toString(),
                "search_lon" to info.lon.toString(),
                "show_search_results" to "true",
                "force" to "true",
            ),
        )

        else -> NavStep.Launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query))).setPackage(pkg))
    }

    fun stop(app: NavApp, pkg: String, unsupported: String): NavStep = when (app) {
        NavApp.OsmAnd -> NavStep.Launch(osmandUri(pkg, "stop_navigation"))
        NavApp.GoogleMaps -> NavStep.Unsupported(unsupported)
    }

    /** OsmAnd's own account of the route (needs the person's grant inside OsmAnd). */
    fun osmandInfo(pkg: String): Intent = osmandUri(pkg, "get_info")

    private fun google(pkg: String, destination: Place, stops: List<Place>, travel: Travel): NavStep {
        if (stops.isEmpty()) {
            val q = if (destination.hasCoordinates) "${destination.lat},${destination.lon}" else destination.searchText
            return NavStep.Launch(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(q)}&mode=${travel.googleNav}")).setPackage(pkg))
        }
        val url = Uri.parse("https://www.google.com/maps/dir/").buildUpon()
            .appendQueryParameter("api", "1")
            .appendQueryParameter("destination", googlePlace(destination))
            .appendQueryParameter("waypoints", stops.take(GOOGLE_STOPS).joinToString("|") { googlePlace(it) })
            .appendQueryParameter("travelmode", travel.google)
            .appendQueryParameter("dir_action", "navigate")
            .build()
        return NavStep.Launch(Intent(Intent.ACTION_VIEW, url).setPackage(pkg))
    }

    private fun googlePlace(p: Place) = if (p.hasCoordinates) "${p.lat},${p.lon}" else p.searchText

    private fun osmand(pkg: String, destination: Place, stops: List<Place>, travel: Travel, info: OsmAndInfo?, force: String): NavStep {
        val route = stops + destination
        return when {
            // Through stops: OsmAnd follows a route given as GPX, which needs every point's coordinates.
            stops.isNotEmpty() && route.all { it.hasCoordinates } -> NavStep.Launch(
                osmandUri(pkg, "navigate_gpx", "force" to force).putExtra("data", gpx(route)),
            )

            stops.isNotEmpty() -> NavStep.Unsupported("")

            destination.hasCoordinates -> NavStep.Launch(
                osmandUri(
                    pkg,
                    "navigate",
                    "dest_lat" to destination.lat.toString(),
                    "dest_lon" to destination.lon.toString(),
                    "dest_name" to destination.label,
                    "profile" to travel.osmand,
                    "force" to force,
                ),
            )

            // Words only: OsmAnd searches them near the phone when it says where the phone is,
            // else its own search opens with them (geo:0,0?q=).
            info?.lat != null && info.lon != null -> NavStep.Launch(
                osmandUri(
                    pkg,
                    "navigate_search",
                    "dest_search_query" to destination.searchText,
                    "search_lat" to info.lat.toString(),
                    "search_lon" to info.lon.toString(),
                    "profile" to travel.osmand,
                    "force" to force,
                ),
            )

            else -> NavStep.Launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(destination.searchText))).setPackage(pkg))
        }
    }

    private fun osmandUri(pkg: String, command: String, vararg params: Pair<String, String>): Intent {
        val uri = Uri.Builder().scheme("osmand.api").authority(command).apply { params.forEach { (k, v) -> appendQueryParameter(k, v) } }.build()
        return Intent(Intent.ACTION_VIEW, uri).setPackage(pkg)
    }

    /** A route as GPX 1.1: its points in order, named. */
    fun gpx(points: List<Place>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"LLeMbas\" xmlns=\"http://www.topografix.com/GPX/1/1\"><rte>")
        for (p in points) {
            append("<rtept lat=\"${p.lat}\" lon=\"${p.lon}\"><name>")
            append(p.label.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
            append("</name></rtept>")
        }
        append("</rte></gpx>\n")
    }
}
