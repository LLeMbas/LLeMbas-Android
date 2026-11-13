package eu.llembas.android.ui

/**
 * A reply's markdown in the pieces the transcript draws differently: prose (Markwon, in a
 * TextView) and fenced code blocks (their own box, with the language and a Copy button). Only
 * a fence at the start of a line (at most three spaces in, as CommonMark has it) is taken out;
 * anything deeper -- a fence inside a list item -- stays in the prose for Markwon to draw.
 *
 * A fence that is not closed yet runs to the end: while a reply is being written, the code it
 * is in the middle of is already a code block, not markdown that changes shape when it closes.
 *
 * **A page** (harness spec 3.8.0, `pages.md`: a whole HTML document in an `html-view` fence) is
 * not drawn on a phone and its source is never shown as the answer. Which fence is a page is the
 * server's to say: a block is one only in a reply written while pages were offered, and the
 * server then puts the one line `[page: Title]` in its place in the text it gives a client that
 * draws no pages. That line is what becomes [Segment.Page]. A fence is taken for a page here
 * only where the caller says pages are being written ([splitFences]' `pages`: the open step of
 * a reply as it streams, which the server has not turned into text yet); anywhere else an
 * `html-view` fence is the code block it is.
 */
sealed interface Segment {
    data class Prose(val markdown: String) : Segment

    data class Code(val language: String, val code: String, val closed: Boolean = true) : Segment

    data class Page(val title: String, val closed: Boolean = true) : Segment
}

/** `pages.fence`: the fence's word, exact. */
private const val PAGE_FENCE = "html-view"

/** `pages.title_max`. */
private const val PAGE_TITLE_MAX = 80

private val PAGE_OPENING = Regex("^ {0,3}(?:`{3,}|~{3,})$PAGE_FENCE(?:\\s+(.*))?$")

/** `pages.projection`, on a line of its own. */
private val PAGE_LINE = Regex("^\\s*\\[page: (.*)]\\s*$")

/** A page's title as written: white space collapsed, cut to the spec's length; empty when it has none. */
private fun pageTitle(raw: String?): String = raw.orEmpty().trim().replace(Regex("\\s+"), " ").take(PAGE_TITLE_MAX)

/**
 * A reply's text with every page replaced by the spec's one line, `[page: Title]`: what Copy,
 * Share and read-aloud take, so a page's source never leaves as "the answer". [untitled] names
 * a page without a title.
 */
fun pageProjection(markdown: String, untitled: String, pages: Boolean = false): String = splitFences(markdown, pages).joinToString("\n\n") { segment ->
    when (segment) {
        is Segment.Prose -> segment.markdown
        is Segment.Code -> "```${segment.language}\n${segment.code}\n```"
        is Segment.Page -> "[page: ${segment.title.ifBlank { untitled }}]"
    }
}

private val OPENING = Regex("^ {0,3}(`{3,}|~{3,})\\s*([^`\\s]*).*$")

fun splitFences(markdown: String, pages: Boolean = false): List<Segment> {
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
            val page = PAGE_LINE.matchEntire(lines[i])
            if (page != null) {
                flushProse()
                out += Segment.Page(pageTitle(page.groupValues[1]))
            } else {
                prose.append(lines[i]).append('\n')
            }
            i++
            continue
        }
        flushProse()
        val fence = open.groupValues[1]
        val language = open.groupValues[2]
        val page = if (pages) PAGE_OPENING.matchEntire(lines[i]) else null
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
        out += if (page != null) Segment.Page(pageTitle(page.groupValues[1]), closed) else Segment.Code(language, code.joinToString("\n"), closed)
    }
    flushProse()
    return out
}
