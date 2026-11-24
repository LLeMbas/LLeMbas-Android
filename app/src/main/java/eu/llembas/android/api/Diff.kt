package eu.llembas.android.api

// An edit's diff on its approval card, worked out on the phone from the two texts the card
// carries (`contract/event_ask_edit.json`). Lines only: what is drawn is text with a sign, and
// every step is bounded, because both texts are a model's and a file's words of any size.

/** What a line of a diff is: in both texts, only in the new one, only in the old one, or the mark of lines left out between two changes. */
enum class DiffSign { Same, Added, Removed, Gap }

data class DiffLine(val sign: DiffSign, val text: String)

/** One file's change as the card draws it: the changed lines with a little around them, and how many were added and removed. */
data class DiffView(val lines: List<DiffLine>, val added: Int, val removed: Int) {
    /** The lines drawn: the first [DIFF_FOLD] until the person opens it, and never more than [DIFF_MOST]. */
    fun shown(open: Boolean): List<DiffLine> = lines.take(if (open) DIFF_MOST else DIFF_FOLD)

    /** Whether there is more than the folded card shows. */
    val folds: Boolean get() = lines.size > DIFF_FOLD

    /** The lines not drawn even when open. */
    val beyond: Int get() = (lines.size - DIFF_MOST).coerceAtLeast(0)
}

/** How many lines of a file's diff the card shows before it is opened. */
const val DIFF_FOLD = 12

/** The most lines of one file's diff the card lays out: a card is for deciding, not for reading a megabyte. */
const val DIFF_MOST = 400

/** The lines kept around a change. */
const val DIFF_CONTEXT = 3

/** Past this many pairs of lines the two middles are not compared: the old one is shown going and the new one coming. */
private const val DIFF_CELLS = 1_000_000L

/** A text's lines; the newline that ends the last one starts no empty line. */
internal fun textLines(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val lines = text.split('\n')
    return if (lines.last().isEmpty()) lines.dropLast(1) else lines
}

/**
 * [old] turned into [new], line by line: what both begin and end with is the same without being
 * compared, and the middle is the longest run of lines the two share -- or, where the middles
 * are too long to compare on a phone, the old middle removed and the new one added.
 */
fun diffLines(old: List<String>, new: List<String>): List<DiffLine> {
    var head = 0
    while (head < old.size && head < new.size && old[head] == new[head]) head++
    var tail = 0
    while (tail < old.size - head && tail < new.size - head && old[old.size - 1 - tail] == new[new.size - 1 - tail]) tail++
    val a = old.subList(head, old.size - tail)
    val b = new.subList(head, new.size - tail)
    val out = ArrayList<DiffLine>(old.size + b.size)
    for (i in 0 until head) out += DiffLine(DiffSign.Same, old[i])
    if (a.isEmpty() || b.isEmpty() || a.size.toLong() * b.size > DIFF_CELLS) {
        a.forEach { out += DiffLine(DiffSign.Removed, it) }
        b.forEach { out += DiffLine(DiffSign.Added, it) }
    } else {
        // shared[i][j]: how many lines the rest of a from i and the rest of b from j have in common, in order.
        val shared = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.size - 1 downTo 0) {
            for (j in b.size - 1 downTo 0) {
                shared[i][j] = if (a[i] == b[j]) shared[i + 1][j + 1] + 1 else maxOf(shared[i + 1][j], shared[i][j + 1])
            }
        }
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> {
                    out += DiffLine(DiffSign.Same, a[i])
                    i++
                    j++
                }

                shared[i + 1][j] >= shared[i][j + 1] -> out += DiffLine(DiffSign.Removed, a[i++])

                else -> out += DiffLine(DiffSign.Added, b[j++])
            }
        }
        while (i < a.size) out += DiffLine(DiffSign.Removed, a[i++])
        while (j < b.size) out += DiffLine(DiffSign.Added, b[j++])
    }
    for (i in old.size - tail until old.size) out += DiffLine(DiffSign.Same, old[i])
    return out
}

/** The changed lines of [lines] with [context] unchanged ones around each, and one [DiffSign.Gap] where lines between two changes are left out. */
fun hunks(lines: List<DiffLine>, context: Int = DIFF_CONTEXT): List<DiffLine> {
    val keep = BooleanArray(lines.size)
    lines.forEachIndexed { at, line ->
        if (line.sign != DiffSign.Same) for (k in maxOf(0, at - context)..minOf(lines.size - 1, at + context)) keep[k] = true
    }
    val out = ArrayList<DiffLine>()
    var left = false
    lines.forEachIndexed { at, line ->
        if (keep[at]) {
            if (left && out.isNotEmpty()) out += DiffLine(DiffSign.Gap, "")
            left = false
            out += line
        } else {
            left = true
        }
    }
    return out
}

/** This file's change for the card: a file that is made is all added, one that goes is all removed. */
fun FileDiff.view(): DiffView {
    val all = diffLines(textLines(oldText.orEmpty()), textLines(if (deleted) "" else newText))
    return DiffView(hunks(all), added = all.count { it.sign == DiffSign.Added }, removed = all.count { it.sign == DiffSign.Removed })
}
