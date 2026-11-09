package eu.llembas.android.ui

import android.content.Context
import eu.llembas.android.R
import eu.llembas.android.api.ApiException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * A failure in words a person can act on. The server's refusal is its own sentence; a phone
 * that cannot reach the instance at all -- no network, a name that does not resolve off the
 * home network, a server that is down -- is one sentence saying so, rather than
 * "Unable to resolve host" or "failed to connect to /…:443". A certificate the phone does not
 * trust keeps its own message: that one names what to fix.
 */
fun Context.explain(e: Throwable): String = when {
    e is ApiException -> e.message?.ifBlank { null } ?: getString(R.string.offline)
    unreachable(e) -> getString(R.string.offline)
    else -> e.message?.ifBlank { null } ?: e.javaClass.simpleName
}

/** Whether a failure means "the instance cannot be reached" rather than "it said no". */
fun unreachable(e: Throwable): Boolean = e is UnknownHostException || e is ConnectException || e is SocketTimeoutException || e is NoRouteToHostException
