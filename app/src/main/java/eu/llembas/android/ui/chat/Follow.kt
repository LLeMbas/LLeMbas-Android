package eu.llembas.android.ui.chat

import eu.llembas.android.api.Message

/*
 * Whether a list keeps to its end while something is written into it. The rule, the same for the
 * chat's transcript and the assistant's sheet: the view follows only while the person is at the
 * end; once they have scrolled away it stays exactly where they are, and coming back to the end
 * takes the following up again. Nothing here knows Compose, so the tests read it as it is.
 */

/** What happened to the transcript's rows between two draws. */
enum class TranscriptChange {
    /** Nothing to follow: no rows, or the same rows. */
    None,

    /** A chat shown for the first time, or another chat in the same place. */
    Opened,

    /** The person's own message is new at the end: sending always shows what was sent. */
    Sent,

    /** New rows at the end that the person did not write: a reply, the queue's next turn. */
    Appended,

    /** An older page was put in front; the end has not moved. */
    Older,

    /** Rows changed in place: a finished reply for the one being written, a transcript read again. */
    Changed,
}

/**
 * What changed from [before] to [after]. [before] is null when this draw is the first one this
 * list makes; [restored] then says the list had rows before (a rotation, a return to the
 * screen), so its place is the one it kept, not a chat being opened.
 */
fun transcriptChange(before: List<Message>?, after: List<Message>, restored: Boolean = false): TranscriptChange {
    if (after.isEmpty()) return TranscriptChange.None
    if (before == null) return if (restored) TranscriptChange.Changed else TranscriptChange.Opened
    val known = before.mapTo(HashSet()) { it.id }
    val firstKept = after.indexOfFirst { it.id in known }
    if (firstKept < 0) return TranscriptChange.Opened
    val added = after.subList(after.indexOfLast { it.id in known } + 1, after.size)
    return when {
        added.any { it.isUser && !it.machine } -> TranscriptChange.Sent
        added.isNotEmpty() -> TranscriptChange.Appended
        firstKept > 0 -> TranscriptChange.Older
        before == after -> TranscriptChange.None
        else -> TranscriptChange.Changed
    }
}

/**
 * Whether the view goes to the end after [change], given whether it was [following]. Opening a
 * chat and sending a message always do; an older page never does; everything else -- and a reply
 * growing, a keyboard opening -- only for a person who is at the end already.
 */
fun movesToEnd(change: TranscriptChange, following: Boolean): Boolean = when (change) {
    TranscriptChange.Opened, TranscriptChange.Sent -> true
    TranscriptChange.None, TranscriptChange.Older -> false
    TranscriptChange.Appended, TranscriptChange.Changed -> following
}

/**
 * Whether the view follows, once a scroll has come to rest [distance] pixels short of the end.
 * Within [slack] counts as the end: a line that arrived while the list was settling must not
 * read as "scrolled away".
 */
fun followsAt(distance: Int, slack: Int): Boolean = distance <= slack

/**
 * The row that keeps its place when an older page arrives while the list stands at its very
 * top: the first row there was before, by its place in [after]. Null when it is gone.
 */
fun keptRow(before: List<Message>, after: List<Message>): Int? {
    val first = before.firstOrNull() ?: return null
    return after.indexOfFirst { it.id == first.id }.takeIf { it >= 0 }
}
