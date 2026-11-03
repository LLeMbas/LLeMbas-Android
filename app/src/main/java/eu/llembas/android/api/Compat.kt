package eu.llembas.android.api

/**
 * The app API this app reads: 2, the server's 2.0 (no canvas, no `knowledge_bases`, the `show`
 * card and its route, and the person's files). A server with a newer one is told about, not
 * guessed at; one with an older one is refused, because 1 has neither the show route nor the
 * files this app opens -- a 2.0 `dev` server from before the files routes still said 1.
 */
const val APP_API = 2

/**
 * The device-link protocol of LLeMbas 2.0. This app is built for 2.0 -- the file tree, the
 * access levels, the `show` card -- and a 1.x server (protocol 1 or 2) is refused with a
 * sentence rather than half-working. The protocol, not the version string, is the test: a
 * server's `dev` branch says 1.7.x until its first beta, and the protocol is what 2.0 raised on
 * purpose so that old clients can tell.
 */
const val MIN_PROTOCOL = 3

/** Whether this app can work with a server, from its discovery document. */
enum class ServerFit { FITS, NOT_LEMBAS, TOO_OLD, TOO_NEW }

fun fit(found: Discovery): ServerFit {
    val protocol = maxOf(found.protocol, found.protocols.maxOrNull() ?: 0)
    return when {
        found.service != "lembas" -> ServerFit.NOT_LEMBAS
        protocol < MIN_PROTOCOL || found.appApi < APP_API || "app" !in found.scopes -> ServerFit.TOO_OLD
        found.appApi > APP_API -> ServerFit.TOO_NEW
        else -> ServerFit.FITS
    }
}
