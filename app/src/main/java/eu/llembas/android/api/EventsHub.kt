package eu.llembas.android.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.shareIn
import java.util.concurrent.ConcurrentHashMap

/**
 * One connection to a person's events route (`GET /events`) for everything that watches it --
 * the drawer's Reports badge, the Scheduled list -- instead of one each. It is open while
 * somebody watches and closed when nobody does.
 *
 * Every frame of the route is a whole state (`schedules`, `reports`), which the server sends at
 * once to a new connection. A watcher that joins a connection already open missed that, so it
 * is first told the last frame of each kind this connection carried.
 *
 * A route that refuses (an older server) ends quietly: its watchers hear nothing, as they did
 * when each asked for itself.
 */
class EventsHub(private val scope: CoroutineScope) {
    private class Line(val key: String, val frames: SharedFlow<Frame>, val latest: MutableMap<String, Frame>)

    private var line: Line? = null

    /** The frames of the route [key] names (an instance's id), opened by [open] when nobody watches it yet. */
    @Synchronized
    fun frames(key: String, open: () -> Flow<Frame>): Flow<Frame> {
        val now = line?.takeIf { it.key == key } ?: run {
            val latest = ConcurrentHashMap<String, Frame>()
            val shared = open()
                // A connection opened anew is told everything again: what the last one said is stale.
                .onStart { latest.clear() }
                .onEach { latest[it.event] = it }
                .catch { }
                .shareIn(scope, SharingStarted.WhileSubscribed(), replay = 0)
            Line(key, shared, latest).also { line = it }
        }
        return now.frames.onSubscription { now.latest.values.toList().forEach { emit(it) } }
    }
}
