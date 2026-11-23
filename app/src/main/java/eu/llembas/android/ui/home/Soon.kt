package eu.llembas.android.ui.home

import eu.llembas.android.api.ReplyFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Something done shortly after it was asked for, once for many askings: [work] runs when nobody
 * has asked for [pause] milliseconds, and at the latest [most] milliseconds after the first
 * asking -- so a stream of askings closer together than the pause (a team's replies starting
 * one after another) still gets its work, every [most], instead of none until it falls quiet.
 *
 * Work under way is never cancelled by a new asking; an asking that comes while it runs is
 * answered by one more run after it. [before] is what the work waits for before it starts (its
 * turn at the gate): an asking during that wait is still answered by the run it waits for.
 *
 * Everything here runs on [scope]'s one thread.
 */
class Soon(
    private val scope: CoroutineScope,
    private val pause: Long,
    private val most: Long,
    private val before: suspend () -> Unit = {},
    private val work: suspend () -> Unit,
) {
    private var quiet: Job? = null
    private var latest: Job? = null

    /** Whether a run is under way, from before its turn to its end. */
    private var running = false

    /** Whether the run under way has started its work: an asking from then on is newer than it. */
    private var started = false
    private var again = false

    fun ask() {
        if (running) {
            if (started) again = true
            return
        }
        quiet?.cancel()
        quiet = scope.launch {
            delay(pause)
            run()
        }
        if (latest?.isActive != true) {
            latest = scope.launch {
                delay(most)
                run()
            }
        }
    }

    /** What was asked for and has not started is dropped (nobody is looking any more); work under way finishes. */
    fun forget() {
        quiet?.cancel()
        latest?.cancel()
        quiet = null
        latest = null
        again = false
    }

    private fun run() {
        if (running) return
        val timers = listOf(quiet, latest)
        quiet = null
        latest = null
        started = false
        running = true
        // Not a child of either timer: cancelling them below must not reach the work.
        scope.launch {
            try {
                before()
                started = true
                work()
            } finally {
                running = false
                if (again) {
                    again = false
                    ask()
                }
            }
        }
        // One of the two is the caller; it has nothing left to do.
        timers.forEach { it?.cancel() }
    }
}

/**
 * Whether the instance says when a reply ends: the events route's `reply` frame with `live`
 * false, for a reply it had said was being written. One such frame is the proof, and from then
 * on the list's running marks are cleared by the frames alone. An instance from before that
 * never proves it, and the list is read again now and then instead ([runningPause]).
 */
class ReplyEnds {
    private val started = LinkedHashSet<String>()

    var said = false
        private set

    fun hear(frame: ReplyFrame) {
        if (frame.messageId.isBlank()) return
        if (frame.live) {
            started += frame.messageId
            if (started.size > REMEMBERED) started.remove(started.first())
        } else if (started.remove(frame.messageId)) {
            said = true
        }
    }

    private companion object {
        const val REMEMBERED = 64
    }
}

/**
 * How long to wait before the list is read again because a row shows a reply being written and
 * nothing has said it ended: 5, 10, 20, 40 seconds, then every minute ([round] counts the
 * reads since a row last showed one). A bounded rate, and only while such a row is on screen.
 */
fun runningPause(round: Int): Long = (5_000L shl round.coerceIn(0, 4)).coerceAtMost(60_000L)
