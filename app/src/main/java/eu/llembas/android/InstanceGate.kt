package eu.llembas.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * Whether a new request that carries the account's files may start: only while the app is in
 * front and the phone is unlocked. Work that goes on by itself -- a queue of uploads, the copies
 * for a share, a player's copy -- asks [pass] before each request. A request already on its way
 * is not touched, so what the person started finishes; the next one waits here, and goes on by
 * itself when the app is in front of an unlocked phone again.
 *
 * The process has one ([LembasApp.gate]). It hears of the app's activities starting and stopping
 * and of the screen going off and the phone being unlocked; [locked] is asked each time.
 */
class InstanceGate(private val locked: () -> Boolean) {
    private val started = AtomicInteger(0)
    private val state = MutableStateFlow(false)

    /** Whether a request may start now, as it was last looked at. */
    val open: StateFlow<Boolean> = state.asStateFlow()

    fun activityStarted() {
        started.incrementAndGet()
        look()
    }

    fun activityStopped() {
        started.updateAndGet { (it - 1).coerceAtLeast(0) }
        look()
    }

    /** Looks again (the screen went off, the phone was unlocked) and says whether a request may start. */
    fun look(): Boolean {
        val now = started.get() > 0 && !locked()
        state.value = now
        return now
    }

    /**
     * Returns when a request may start. [waiting] hears `true` when that is not now, and `false`
     * when the wait is over (also when it is given up), so a screen can say what it is waiting
     * for. While waiting the lock is looked at again every [AGAIN_MS]: an unlock nobody
     * announced must not leave a queue standing.
     */
    suspend fun pass(waiting: (Boolean) -> Unit = {}) {
        if (look()) return
        waiting(true)
        try {
            while (!look()) withTimeoutOrNull(AGAIN_MS) { state.first { it } }
        } finally {
            waiting(false)
        }
    }

    /**
     * [pass], for a wait that may last as long as the phone lies locked or the app is away: it
     * sleeps until the gate is told of a change (an activity starting, the unlock) instead of
     * looking at the lock every second, and only then takes its turn as [pass] does.
     */
    suspend fun passLater() {
        if (!look()) state.first { it }
        pass()
    }

    companion object {
        const val AGAIN_MS = 1_000L
    }
}

/**
 * [work], for as long as a request may start: something nobody asked for (a reply that began by
 * itself) is followed only while the app is in front of an unlocked phone. When the gate closes
 * the work is let go -- cancelled, with its connection -- and the answer is null; so it is when
 * the gate is closed already, and then nothing is started. [stays] says the work is the
 * person's own after all (they asked for it while it ran): that goes on behind the gate, as a
 * request under way does. The caller's own cancellation is not taken for a letting go.
 */
suspend fun <T : Any> InstanceGate.whileOpen(stays: () -> Boolean = { false }, work: suspend () -> T): T? {
    if (!look() && !stays()) return null
    return coroutineScope {
        val doing = async { work() }
        val watch = launch {
            open.first { !it && !stays() }
            doing.cancel()
        }
        try {
            doing.await()
        } catch (e: CancellationException) {
            // The caller's own end is the caller's; only the gate's is "let go".
            currentCoroutineContext().ensureActive()
            null
        } finally {
            watch.cancel()
        }
    }
}

/**
 * [send] for each of [items] from the one at [from], each after its [turn] at the gate, and
 * [done] hears how many are through after each. A failure leaves the count where it was, so
 * the same call with that count takes the queue up at the one that failed, not at the first.
 */
suspend fun <T> sendFrom(items: List<T>, from: Int, turn: suspend () -> Unit, done: (Int) -> Unit, send: suspend (T) -> Unit) {
    for (i in from.coerceAtLeast(0) until items.size) {
        turn()
        send(items[i])
        done(i + 1)
    }
}
