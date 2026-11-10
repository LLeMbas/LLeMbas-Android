package eu.llembas.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import eu.llembas.android.ui.theme.Palette

/**
 * Syntax highlighting for code that is only read: the file viewer and a reply's code blocks.
 *
 * A small scanner of the app's own, not a library: what it has to tell apart is few -- comments,
 * strings, numbers, keywords, type names, and a markup language's tags -- and a highlighter that
 * is a parser of its own (Prism4j, which needs an annotation processor and has not moved since
 * 2019; or a WebView, which the app does not have) would be more to trust than the result is worth.
 * It never runs anything and never fails: a language it does not know, or text it does not
 * follow, is simply drawn plain. The colours are the theme's tokens, so it follows Shire and
 * Moria like everything else.
 *
 * The languages are the server's (`show`'s `language`, from the file's extension) and a fence's
 * info string in a reply (```kotlin), with the usual short names.
 */
enum class TokenKind { Comment, Text, Number, Keyword, Type, Tag, Attribute }

data class Token(val start: Int, val end: Int, val kind: TokenKind)

/** How one language is read: the scanner's few settings. */
private data class Spec(
    val keywords: Set<String> = emptySet(),
    val lineComments: List<String> = emptyList(),
    val blockComments: List<Pair<String, String>> = emptyList(),
    /** String delimiters, longest first ("""  before "). */
    val strings: List<String> = listOf("\"", "'"),
    val ignoreCase: Boolean = false,
    /** A capitalised identifier is a type (Kotlin, Java, Swift, Rust...). */
    val capitalisedTypes: Boolean = false,
    /** `<tag attr="...">`: markup (XML, HTML, Vue and Svelte templates). */
    val markup: Boolean = false,
    /** `key:` / `key =` at a line's start is the key (YAML, TOML). */
    val keys: Boolean = false,
    /** `$name` / `${...}` variables (shells, PHP). */
    val dollarVariables: Boolean = false,
)

private fun words(text: String): Set<String> = text.split(' ').filter { it.isNotBlank() }.toSet()

private val C_FAMILY = words(
    "if else for while do switch case default break continue return goto sizeof typedef struct union enum " +
        "static const extern volatile register inline void char short int long float double signed unsigned " +
        "auto true false NULL nullptr",
)
private val CPP = C_FAMILY + words(
    "class namespace template typename public private protected virtual override final new delete this " +
        "using operator friend explicit constexpr noexcept try catch throw bool std",
)
private val JAVA = words(
    "abstract assert boolean break byte case catch char class const continue default do double else enum " +
        "extends final finally float for goto if implements import instanceof int interface long native new " +
        "package private protected public return short static strictfp super switch synchronized this throw " +
        "throws transient try void volatile while true false null var record sealed permits yield",
)
private val KOTLIN = words(
    "as break class continue do else false for fun if in interface is null object package return super this " +
        "throw true try typealias typeof val var when while by catch constructor delegate dynamic field file " +
        "finally get import init param property receiver set setparam where actual abstract annotation " +
        "companion const crossinline data enum expect external final infix inline inner internal lateinit " +
        "noinline open operator out override private protected public reified sealed suspend tailrec vararg value",
)
private val JS = words(
    "break case catch class const continue debugger default delete do else export extends finally for " +
        "function if import in instanceof new return super switch this throw try typeof var void while with " +
        "yield let static async await of true false null undefined from as get set",
)
private val TS = JS + words(
    "interface type enum implements declare namespace module public private protected readonly abstract " +
        "keyof infer is never unknown any string number boolean symbol bigint object satisfies",
)
private val PYTHON = words(
    "False None True and as assert async await break class continue def del elif else except finally for " +
        "from global if import in is lambda nonlocal not or pass raise return try while with yield match case self",
)
private val RUST = words(
    "as async await break const continue crate dyn else enum extern false fn for if impl in let loop match " +
        "mod move mut pub ref return self Self static struct super trait true type unsafe use where while",
)
private val GO = words(
    "break case chan const continue default defer else fallthrough for func go goto if import interface map " +
        "package range return select struct switch type var true false nil iota",
)
private val RUBY = words(
    "BEGIN END alias and begin break case class def defined do else elsif end ensure false for if in module " +
        "next nil not or redo rescue retry return self super then true undef unless until when while yield require",
)
private val PHP = words(
    "abstract and array as break callable case catch class clone const continue declare default do echo else " +
        "elseif empty enddeclare endfor endforeach endif endswitch endwhile extends final finally fn for foreach " +
        "function global goto if implements include instanceof insteadof interface isset list match namespace new " +
        "or print private protected public readonly require return static switch throw trait try unset use var " +
        "while yield true false null",
)
private val SWIFT = words(
    "associatedtype class deinit enum extension fileprivate func import init inout internal let open operator " +
        "private protocol public rethrows static struct subscript typealias var break case continue default defer " +
        "do else fallthrough for guard if in repeat return switch where while as catch false is nil super self Self " +
        "throw throws true try async await some any",
)
private val CSHARP = words(
    "abstract as base bool break byte case catch char checked class const continue decimal default delegate do " +
        "double else enum event explicit extern false finally fixed float for foreach goto if implicit in int " +
        "interface internal is lock long namespace new null object operator out override params private protected " +
        "public readonly ref return sbyte sealed short sizeof stackalloc static string struct switch this throw true " +
        "try typeof uint ulong unchecked unsafe ushort using virtual void volatile while var async await record",
)
private val DART = words(
    "abstract as assert async await break case catch class const continue covariant default deferred do dynamic " +
        "else enum export extends extension external factory false final finally for get if implements import in " +
        "interface is late library mixin new null on operator part required rethrow return set show static super " +
        "switch sync this throw true try typedef var void while with yield",
)
private val ZIG = words(
    "addrspace align allowzero and anyframe anytype asm async await break callconv catch comptime const continue " +
        "defer else enum errdefer error export extern fn for if inline noalias nosuspend noinline opaque or orelse " +
        "packed pub resume return linksection struct suspend switch test threadlocal try union unreachable usingnamespace " +
        "var volatile while true false null undefined",
)
private val SHELL = words(
    "if then else elif fi case esac for select while until do done in function return exit local export " +
        "readonly declare set unset shift source echo cd test true false",
)
private val SQL = words(
    "select from where and or not insert into values update set delete create table drop alter add index view " +
        "join left right inner outer full on as group by order having limit offset union all distinct case when then " +
        "else end null is in like between exists primary key foreign references default unique check begin commit " +
        "rollback transaction with returning asc desc count sum avg min max integer text real blob varchar",
)
private val LUA = words(
    "and break do else elseif end false for function goto if in local nil not or repeat return then true until while",
)
private val CSS = words("important inherit initial unset none auto")
private val LITERALS = words("true false null yes no on off")
private val DOCKER = words(
    "FROM RUN CMD LABEL EXPOSE ENV ADD COPY ENTRYPOINT VOLUME USER WORKDIR ARG ONBUILD STOPSIGNAL HEALTHCHECK SHELL AS",
)

private val C_LIKE = Spec(lineComments = listOf("//"), blockComments = listOf("/*" to "*/"))

private val SPECS: Map<String, Spec> = buildMap {
    val js = C_LIKE.copy(keywords = JS, strings = listOf("\"", "'", "`"))
    val ts = C_LIKE.copy(keywords = TS, strings = listOf("\"", "'", "`"), capitalisedTypes = true)
    put("javascript", js)
    put("jsx", js)
    put("typescript", ts)
    put("tsx", ts)
    put("json", Spec(keywords = LITERALS, strings = listOf("\"")))
    put("python", Spec(keywords = PYTHON, lineComments = listOf("#"), strings = listOf("\"\"\"", "'''", "\"", "'"), capitalisedTypes = true))
    put("rust", C_LIKE.copy(keywords = RUST, strings = listOf("\""), capitalisedTypes = true))
    put("go", C_LIKE.copy(keywords = GO, strings = listOf("\"", "`", "'")))
    put("ruby", Spec(keywords = RUBY, lineComments = listOf("#"), strings = listOf("\"", "'"), capitalisedTypes = true))
    put("php", C_LIKE.copy(keywords = PHP, lineComments = listOf("//", "#"), dollarVariables = true, ignoreCase = true))
    put("java", C_LIKE.copy(keywords = JAVA, strings = listOf("\"\"\"", "\"", "'"), capitalisedTypes = true))
    put("kotlin", C_LIKE.copy(keywords = KOTLIN, strings = listOf("\"\"\"", "\"", "'"), capitalisedTypes = true))
    put("swift", C_LIKE.copy(keywords = SWIFT, strings = listOf("\"\"\"", "\""), capitalisedTypes = true))
    put("c", C_LIKE.copy(keywords = C_FAMILY))
    put("cpp", C_LIKE.copy(keywords = CPP, capitalisedTypes = true))
    put("csharp", C_LIKE.copy(keywords = CSHARP, capitalisedTypes = true))
    put("dart", C_LIKE.copy(keywords = DART, strings = listOf("\"\"\"", "'''", "\"", "'"), capitalisedTypes = true))
    put("zig", C_LIKE.copy(keywords = ZIG, strings = listOf("\"", "'")))
    val shell = Spec(keywords = SHELL, lineComments = listOf("#"), dollarVariables = true)
    put("bash", shell)
    put("fish", shell)
    put("css", C_LIKE.copy(keywords = CSS, lineComments = emptyList()))
    put("scss", C_LIKE.copy(keywords = CSS, dollarVariables = true))
    put("sql", Spec(keywords = SQL, lineComments = listOf("--"), blockComments = listOf("/*" to "*/"), strings = listOf("'", "\""), ignoreCase = true))
    put("yaml", Spec(keywords = LITERALS, lineComments = listOf("#"), keys = true))
    put("toml", Spec(keywords = LITERALS, lineComments = listOf("#"), strings = listOf("\"\"\"", "'''", "\"", "'"), keys = true))
    // Text between tags is prose: an apostrophe in it is not a string. Attribute values are read by markup().
    val markup = Spec(blockComments = listOf("<!--" to "-->"), strings = emptyList(), markup = true)
    put("xml", markup)
    put("html", markup)
    put("vue", markup)
    put("svelte", markup)
    put("lua", Spec(keywords = LUA, lineComments = listOf("--"), blockComments = listOf("--[[" to "]]"), strings = listOf("\"", "'")))
    put("dockerfile", Spec(keywords = DOCKER, lineComments = listOf("#")))
    put("makefile", Spec(lineComments = listOf("#"), dollarVariables = true, keys = true))
}

/** A fence's short names for the server's language names. */
private val ALIASES = mapOf(
    "js" to "javascript", "mjs" to "javascript", "cjs" to "javascript", "ts" to "typescript", "py" to "python",
    "python3" to "python", "rs" to "rust", "golang" to "go", "rb" to "ruby", "kt" to "kotlin", "kts" to "kotlin",
    "c++" to "cpp", "cc" to "cpp", "hpp" to "cpp", "h" to "c", "cs" to "csharp", "c#" to "csharp", "sh" to "bash",
    "shell" to "bash", "zsh" to "bash", "console" to "bash", "yml" to "yaml", "svg" to "xml", "htm" to "html",
    "docker" to "dockerfile", "make" to "makefile", "jsonc" to "json", "json5" to "json", "postgres" to "sql",
    "postgresql" to "sql", "sqlite" to "sql", "mysql" to "sql",
)

/** Whether [language] is one this highlighter reads. */
fun highlights(language: String): Boolean = specOf(language) != null

private fun specOf(language: String): Spec? {
    val name = language.trim().lowercase()
    return SPECS[name] ?: ALIASES[name]?.let { SPECS[it] }
}

/** Larger than this is drawn plain: a megabyte of spans costs more than it helps. */
const val HIGHLIGHT_MAX_CHARS = 400_000

/** The tokens of [text] read as [language]: empty for a language not known, or a text too large. */
fun tokenize(text: String, language: String): List<Token> {
    val spec = specOf(language) ?: return emptyList()
    if (text.length > HIGHLIGHT_MAX_CHARS) return emptyList()
    return Scanner(text, spec).run()
}

private class Scanner(private val s: String, private val spec: Spec) {
    private val out = mutableListOf<Token>()
    private var i = 0
    private var lineStart = true

    fun run(): List<Token> {
        while (i < s.length) {
            val c = s[i]
            if (c == '\n') {
                lineStart = true
                i++
                continue
            }
            if (c == ' ' || c == '\t') {
                i++
                continue
            }
            val atLineStart = lineStart
            lineStart = false
            if (block() || line() || (spec.markup && markup()) || string() || (spec.keys && atLineStart && key()) ||
                (spec.dollarVariables && variable()) || number() || word()
            ) {
                continue
            }
            i++
        }
        return out
    }

    private fun add(start: Int, end: Int, kind: TokenKind) {
        if (end > start) out += Token(start, end, kind)
    }

    private fun block(): Boolean {
        for ((open, close) in spec.blockComments) {
            if (s.startsWith(open, i)) {
                val end = s.indexOf(close, i + open.length).let { if (it < 0) s.length else it + close.length }
                add(i, end, TokenKind.Comment)
                i = end
                return true
            }
        }
        return false
    }

    private fun line(): Boolean {
        for (mark in spec.lineComments) {
            // `#` in a shell word ("a#b") or `$#` is not a comment; one after a space or at a line's start is.
            if (s.startsWith(mark, i) && (mark != "#" || i == 0 || s[i - 1].isWhitespace())) {
                val end = s.indexOf('\n', i).let { if (it < 0) s.length else it }
                add(i, end, TokenKind.Comment)
                i = end
                return true
            }
        }
        return false
    }

    private fun string(): Boolean {
        for (quote in spec.strings) {
            if (!s.startsWith(quote, i)) continue
            // A single-character quote ends at the line's end if it is not closed: a stray
            // apostrophe ("don't" in a comment-less shell line) colours one line, not the file.
            var j = i + quote.length
            while (j < s.length) {
                if (s[j] == '\\' && quote.length == 1) {
                    j += 2
                    continue
                }
                if (s.startsWith(quote, j)) {
                    j += quote.length
                    break
                }
                if (s[j] == '\n' && quote.length == 1 && quote != "`") break
                j++
            }
            val end = j.coerceAtMost(s.length)
            add(i, end, TokenKind.Text)
            i = end
            return true
        }
        return false
    }

    private fun number(): Boolean {
        val c = s[i]
        if (!c.isDigit() && !(c == '.' && i + 1 < s.length && s[i + 1].isDigit())) return false
        if (i > 0 && (s[i - 1].isLetterOrDigit() || s[i - 1] == '_')) return false
        var j = i
        while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '.' || s[j] == '_')) j++
        add(i, j, TokenKind.Number)
        i = j
        return true
    }

    private fun word(): Boolean {
        val c = s[i]
        if (!c.isLetter() && c != '_' && c != '@') return false
        var j = i + 1
        while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '_')) j++
        val word = s.substring(i, j)
        val bare = word.removePrefix("@")
        when {
            word.startsWith("@") && word.length > 1 -> add(i, j, TokenKind.Attribute)

            (if (spec.ignoreCase) bare.lowercase() in spec.keywords || bare in spec.keywords else bare in spec.keywords) ->
                add(i, j, TokenKind.Keyword)

            spec.capitalisedTypes && bare.first().isUpperCase() && bare.any { it.isLowerCase() } -> add(i, j, TokenKind.Type)
        }
        i = j
        return true
    }

    private fun variable(): Boolean {
        if (s[i] != '$' || i + 1 >= s.length) return false
        var j = i + 1
        if (s[j] == '{') {
            j = s.indexOf('}', j).let { if (it < 0) s.length else it + 1 }
        } else {
            while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '_')) j++
        }
        if (j == i + 1) return false
        add(i, j, TokenKind.Attribute)
        i = j
        return true
    }

    /** `key:` (YAML) or `key =` (TOML, Make) as a line's first word. */
    private fun key(): Boolean {
        var j = i
        if (s[j] == '-' && j + 1 < s.length && s[j + 1] == ' ') return false
        while (j < s.length && (s[j].isLetterOrDigit() || s[j] in "_-.[]\"'")) j++
        if (j == i) return false
        var k = j
        while (k < s.length && s[k] == ' ') k++
        if (k < s.length && (s[k] == ':' || s[k] == '=') && (k + 1 >= s.length || s[k + 1] != '=')) {
            add(i, j, TokenKind.Type)
            i = j
            return true
        }
        return false
    }

    /** `<tag attr="value">` and `</tag>`: the tag's name, its attributes' names, the values as strings. */
    private fun markup(): Boolean {
        if (s[i] != '<' || i + 1 >= s.length) return false
        var j = i + 1
        if (s[j] == '/' || s[j] == '?' || s[j] == '!') j++
        val nameStart = j
        while (j < s.length && (s[j].isLetterOrDigit() || s[j] in "-_:.")) j++
        if (j == nameStart) return false
        add(nameStart, j, TokenKind.Tag)
        while (j < s.length && s[j] != '>') {
            when {
                s[j] == '"' || s[j] == '\'' -> {
                    val q = s[j]
                    val end = s.indexOf(q, j + 1).let { if (it < 0) s.length else it + 1 }
                    add(j, end, TokenKind.Text)
                    j = end
                }

                s[j].isLetter() -> {
                    val a = j
                    while (j < s.length && (s[j].isLetterOrDigit() || s[j] in "-_:@.")) j++
                    add(a, j, TokenKind.Attribute)
                }

                s[j] == '\n' -> {
                    j++
                }

                else -> j++
            }
        }
        i = (j + 1).coerceAtMost(s.length)
        return true
    }
}

/** The theme's colour for each kind of token. */
fun tokenStyle(kind: TokenKind, palette: Palette): SpanStyle = when (kind) {
    TokenKind.Comment -> SpanStyle(color = palette.inkFaint, fontStyle = FontStyle.Italic)
    TokenKind.Text -> SpanStyle(color = palette.success)
    TokenKind.Number -> SpanStyle(color = palette.warning)
    TokenKind.Keyword -> SpanStyle(color = palette.accent)
    TokenKind.Type -> SpanStyle(color = palette.leaf)
    TokenKind.Tag -> SpanStyle(color = palette.accent)
    TokenKind.Attribute -> SpanStyle(color = palette.warning)
}

/** [text] with its [tokens] in the theme's colours; the rest in [plain]. */
fun highlighted(text: String, tokens: List<Token>, palette: Palette, plain: Color = palette.ink): AnnotatedString = buildAnnotatedString {
    append(text)
    addStyle(SpanStyle(color = plain), 0, text.length)
    for (token in tokens) addStyle(tokenStyle(token.kind, palette), token.start, token.end.coerceAtMost(text.length))
}

/** [text] as [language], highlighted -- or plain, when the language is not one it reads. */
fun highlight(text: String, language: String, palette: Palette): AnnotatedString = highlighted(text, tokenize(text, language), palette)
