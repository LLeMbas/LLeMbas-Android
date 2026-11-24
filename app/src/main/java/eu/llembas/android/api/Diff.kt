package eu.llembas.android.api

// An edit's diff on its approval card, worked out on the phone from the two texts the card
// carries (`contract/event_ask_edit.json`). Lines only: what is drawn is text with a sign, and
// every step is bounded before it is taken, because both texts are a model's and a file's words
// of any size.

/** What a line of a diff is: in both texts, only in the new one, only in the old one, or the mark of lines left out between two changes. */
enum class DiffSign { Same, Added, Removed, Gap }

data class DiffLine(val sign: DiffSign, val text: String)

/**
 * One file's change as the card draws it: the changed lines with a little around them, and how
 * many were added and removed. [endings] says a change of line endings (`"LF" to "CRLF"`), which
 * is said once instead of as every line going and coming. [cut]: the texts were longer than what
 * a phone compares, and only their beginning was.
 */
data class DiffView(val lines: List<DiffLine>, val added: Int, val removed: Int, val endings: Pair<String, String>? = null, val cut: Boolean = false) {
    /** The lines drawn: the first [DIFF_FOLD] until the person opens it, and never more than [DIFF_MOST]. */
    fun shown(open: Boolean): List<DiffLine> = lines.take(if (open) DIFF_MOST else DIFF_FOLD)

    /** Whether there is more than the folded card shows. */
    val folds: Boolean get() = lines.size > DIFF_FOLD

    /** The lines not drawn even when open. */
    val beyond: Int get() = (lines.size - DIFF_MOST).coerceAtLeast(0)

    /** Whether the block says anything of the change at all; a card whose diffs say nothing keeps its arguments in view. */
    val says: Boolean get() = lines.isNotEmpty() || endings != null || cut
}

/** How many lines of a file's diff the card shows before it is opened. */
const val DIFF_FOLD = 12

/** The most lines of one file's diff the card lays out: a card is for deciding, not for reading a megabyte. */
const val DIFF_MOST = 400

/** The lines kept around a change. */
const val DIFF_CONTEXT = 3

/** Past this many pairs of lines the two middles are not compared: the old one is shown going and the new one coming. */
private const val DIFF_CELLS = 1_000_000L

/** The most lines of one text that are compared, and the most characters: what is past either is left out before any work is done. */
const val DIFF_TEXT_LINES = 2_000
const val DIFF_TEXT_CHARS = 200_000

/** A text's lines as they are compared, and whether the text went on past what is. */
data class TextLines(val lines: List<String>, val cut: Boolean)

/**
 * The lines of [text], at most [mostLines] of them from at most its first [mostChars]
 * characters: nothing past either bound is read, split or kept. The newline that ends the last
 * line starts no empty one, and a line is compared without the `\r` before its newline, so a
 * file whose line endings change is not a file whose every line changes.
 */
fun textLines(text: String, mostLines: Int = DIFF_TEXT_LINES, mostChars: Int = DIFF_TEXT_CHARS): TextLines {
    val end = minOf(text.length, mostChars)
    val lines = ArrayList<String>()
    var from = 0
    while (from < end && lines.size < mostLines) {
        val newline = text.indexOf('\n', from).let { if (it < 0 || it >= end) -1 else it }
        val to = if (newline < 0) end else newline
        lines += text.substring(from, if (to > from && text[to - 1] == '\r') to - 1 else to)
        from = if (newline < 0) end else newline + 1
    }
    return TextLines(lines, cut = from < text.length)
}

/** How [text]'s lines end, where it has any: `CRLF` when its first newline follows a `\r`, else `LF`; null for a text of one line. */
internal fun lineEnding(text: String): String? {
    val newline = text.indexOf('\n')
    return when {
        newline < 0 -> null
        newline > 0 && text[newline - 1] == '\r' -> "CRLF"
        else -> "LF"
    }
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

/**
 * This file's change for the card: a file that is made is all added, one that goes is all
 * removed. Bounded before it starts ([textLines]) and while it runs ([diffLines]); it is still
 * work for a background thread, never for a frame.
 */
fun FileDiff.view(): DiffView {
    val before = oldText.orEmpty()
    val after = if (deleted) "" else newText
    val old = textLines(before)
    val new = textLines(after)
    val all = diffLines(old.lines, new.lines)
    val from = lineEnding(before)
    val to = lineEnding(after)
    return DiffView(
        hunks(all),
        added = all.count { it.sign == DiffSign.Added },
        removed = all.count { it.sign == DiffSign.Removed },
        endings = if (from != null && to != null && from != to) from to to else null,
        cut = old.cut || new.cut,
    )
}

/** Whether an approval's item shows its `detail` (the call's arguments): always without diffs, and with them when none of them says anything. [views] is null while they are worked out. */
fun showsDetail(item: CardItem, views: List<DiffView>?): Boolean = item.diffs.isEmpty() || (views != null && views.none { it.says })

// Characters a person cannot see, or that would move or break a line: on a card where somebody
// decides, each is written as its code point.
private fun unseen(c: Char): Boolean = when (c.code) {
    in 0x00..0x1F, in 0x7F..0x9F -> true
    0x00AD, 0x061C, 0x180E, 0xFEFF -> true
    in 0x200B..0x200F, in 0x2028..0x202E, in 0x2060..0x206F -> true
    in 0xFFF9..0xFFFB -> true
    else -> false
}

/**
 * [text] with every invisible, direction-changing or line-breaking character written as
 * `<U+202E>`: a bidi override, a zero-width space, a line separator, a control. A tab is four
 * spaces. What is drawn reads as what is written.
 */
fun visibly(text: String): String {
    if (text.none { it == '\t' || unseen(it) }) return text
    return buildString(text.length + 16) {
        for (c in text) {
            when {
                c == '\t' -> append("    ")
                unseen(c) -> append("<U+").append(c.code.toString(16).uppercase().padStart(4, '0')).append('>')
                else -> append(c)
            }
        }
    }
}
