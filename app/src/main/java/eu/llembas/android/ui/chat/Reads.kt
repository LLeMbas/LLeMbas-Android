package eu.llembas.android.ui.chat

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The reads of one thing (a chat's transcript) that several pieces of news ask for, one at a
 * time and none twice for the same news. Whoever learns something that needs a read takes a
 * [mark] at that moment -- before waiting for anything -- and asks [since] it; a read that began
 * after the mark and came back has seen what the mark's news was about, so the asker's own is
 * not made. A burst of frames, or a return to the front that several watchers notice, is then
 * one read; news that arrives while a read is under way is newer than that read, and gets its own.
 */
class Reads {
    private val one = Mutex()

    /** How many reads have begun. */
    private var begun = 0L

    /** The last read that came back with an answer, by its number. */
    private var answered = 0L

    /** The moment some news was learned: every read that begins from now on covers it. */
    fun mark(): Long = begun

    /**
     * [read], unless a read that began after [mark] has already answered, or [wanted] says -- once
     * it is this asker's turn -- that nothing is asked for any more. [read] says whether it got
     * an answer; one that did not covers nobody. True when [read] ran.
     */
    suspend fun since(mark: Long, wanted: () -> Boolean = { true }, read: suspend () -> Boolean): Boolean = one.withLock {
        if (answered > mark || !wanted()) return@withLock false
        val mine = ++begun
        if (read()) answered = mine
        true
    }
}
