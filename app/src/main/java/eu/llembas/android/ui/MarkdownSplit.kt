package eu.llembas.android.ui

/**
 * A reply's markdown in the pieces the transcript draws differently: prose (Markwon, in a
 * TextView) and fenced code blocks (their own box, with the language and a Copy button). Only
 * a fence at the start of a line (at most three spaces in, as CommonMark has it) is taken out;
 * anything deeper -- a fence inside a list item -- stays in the prose for Markwon to draw.
 *
 * A fence that is not closed yet runs to the end: while a reply is being written, the code it
 * is in the middle of is already a code block, not markdown that changes shape when it closes.
 */
sealed interface Segment {
    data class Prose(val markdown: String) : Segment

    data class Code(val language: String, val code: String, val closed: Boolean = true) : Segment
}

private val OPENING = Regex("^ {0,3}(`{3,}|~{3,})\\s*([^`\\s]*).*$")

fun splitFences(markdown: String): List<Segment> {
    val out = mutableListOf<Segment>()
    val prose = StringBuilder()
    val lines = markdown.split("\n")
    var i = 0

    fun flushProse() {
        val text = prose.toString().trim('\n')
        if (text.isNotBlank()) out += Segment.Prose(text)
        prose.clear()
    }

    while (i < lines.size) {
        val open = OPENING.matchEntire(lines[i])
        if (open == null) {
            prose.append(lines[i]).append('\n')
            i++
            continue
        }
        flushProse()
        val fence = open.groupValues[1]
        val language = open.groupValues[2]
        val closing = Regex("^ {0,3}${Regex.escape(fence.substring(0, 1))}{${fence.length},}\\s*$")
        val code = mutableListOf<String>()
        i++
        var closed = false
        while (i < lines.size) {
            if (closing.matches(lines[i])) {
                closed = true
                i++
                break
            }
            code += lines[i]
            i++
        }
        out += Segment.Code(language, code.joinToString("\n"), closed)
    }
    flushProse()
    return out
}
