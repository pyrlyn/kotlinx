package newkotlin

class CompileError(msg: String) : Exception(msg)

enum class K { ID, INT, CHAR, DBL, STR, OP, NL, EOF }

/** A `${...}` or `$name` piece inside a string literal; parsed later as an expression. */
class TemplateCode(val code: String, val line: Int)

class Tok(
    val k: K, val s: String, val line: Int,
    val num: Long = 0, val isLong: Boolean = false, val unsigned: Boolean = false,
    val dbl: Double = 0.0, val parts: List<Any> = emptyList(),
)

private val OPS = listOf(
    "?.", "?:", "..", "->", "++", "--", "&&", "||", "==", "!=", "<=", ">=", "+=", "-=", "*=", "/=", "%=", "::", "!!",
    "+", "-", "*", "/", "%", "<", ">", "=", "!", "(", ")", "{", "}", "[", "]", ",", ".", ":", ";", "?", "@",
)

private val DIAG = Regex("""<![A-Z_]+(\([^)]*\))?(, *[A-Z_]+(\([^)]*\))?)*!>""")

class Lexer(private val src: String, private var line: Int = 1) {
    private var i = 0
    private val out = ArrayList<Tok>()

    fun lex(): List<Tok> {
        while (i < src.length) {
            val c = src[i]
            when {
                c == '\n' -> { out.add(Tok(K.NL, "\n", line)); line++; i++ }
                c == ' ' || c == '\t' || c == '\r' -> i++
                src.startsWith("//", i) -> while (i < src.length && src[i] != '\n') i++
                src.startsWith("/*", i) -> {
                    val e = src.indexOf("*/", i + 2)
                    if (e < 0) throw CompileError("line $line: unterminated comment")
                    line += src.substring(i, e).count { it == '\n' }
                    i = e + 2
                }
                c.isDigit() -> number()
                c.isLetter() || c == '_' -> {
                    val st = i
                    while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) i++
                    out.add(Tok(K.ID, src.substring(st, i), line))
                }
                src.startsWith("\"\"\"", i) -> rawString()
                c == '"' -> string()
                // Diagnostic markup in upstream test data (`<!NAME!>expr<!>`) is not code.
                src.startsWith("<!>", i) -> i += 3
                c == '<' && DIAG.matchAt(src, i) != null -> i += DIAG.matchAt(src, i)!!.value.length
                src.startsWith("..<", i) -> { out.add(Tok(K.ID, "until", line)); i += 3 }
                c == '\'' -> char()
                else -> {
                    val op = OPS.firstOrNull { src.startsWith(it, i) }
                        ?: throw CompileError("line $line: unexpected character '$c'")
                    out.add(Tok(K.OP, op, line)); i += op.length
                }
            }
        }
        out.add(Tok(K.EOF, "<eof>", line))
        return out
    }

    private fun number() {
        val st = i
        if (src.startsWith("0x", i) || src.startsWith("0X", i)) {
            i += 2
            val ds = i
            while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_') && src[i] !in "uUL") i++
            val v = src.substring(ds, i).replace("_", "").toULong(16).toLong()
            suffix(v, st); return
        }
        while (i < src.length && (src[i].isDigit() || src[i] == '_')) i++
        val frac = i + 1 < src.length && src[i] == '.' && src[i + 1].isDigit()
        val exp = !frac && i + 1 < src.length && (src[i] == 'e' || src[i] == 'E') && (src[i + 1].isDigit() || src[i + 1] == '-' || src[i + 1] == '+')
        if (frac || exp) {
            if (frac) i++
            while (i < src.length && (src[i].isDigit() || src[i] == '_')) i++
            if (i < src.length && (src[i] == 'e' || src[i] == 'E')) {
                i++; if (src[i] == '+' || src[i] == '-') i++
                while (i < src.length && src[i].isDigit()) i++
            }
            val text = src.substring(st, i).replace("_", "")
            out.add(Tok(K.DBL, text, line, dbl = text.toDouble())); return
        }
        val digits = src.substring(st, i).replace("_", "")
        val v = digits.toULongOrNull()?.toLong() ?: throw CompileError("line $line: integer literal out of range: $digits")
        suffix(v, st)
    }

    private fun suffix(v: Long, st: Int) {
        var long = false; var unsigned = false
        if (i < src.length && (src[i] == 'u' || src[i] == 'U')) { unsigned = true; i++ }
        if (i < src.length && src[i] == 'L') { long = true; i++ }
        if (!unsigned && !long && v < 0) throw CompileError("line $line: integer literal out of range: ${src.substring(st, i)}")
        out.add(Tok(K.INT, src.substring(st, i), line, num = v, isLong = long, unsigned = unsigned))
    }

    private fun escape(): Char {
        val e = src[i + 1]; i += 2
        return when (e) {
            'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; '0' -> '\u0000'
            '\\', '"', '\'', '$' -> e
            else -> throw CompileError("line $line: unknown escape \\$e")
        }
    }

    private fun char() {
        i++
        val ch = if (src[i] == '\\') escape() else src[i++]
        if (src[i] != '\'') throw CompileError("line $line: bad char literal")
        i++
        out.add(Tok(K.CHAR, "'$ch'", line, num = ch.code.toLong()))
    }

    /** `"""..."""`: no escapes, may span lines; `$` templates as in ordinary strings. Extra closing quotes belong to the text. */
    private fun rawString() {
        i += 3
        val parts = ArrayList<Any>()
        val sb = StringBuilder()
        val startLine = line
        while (true) {
            if (i >= src.length) throw CompileError("line $startLine: unterminated string")
            val c = src[i]
            when {
                src.startsWith("\"\"\"", i) && !src.startsWith("\"\"\"\"", i) -> { i += 3; break }
                c == '$' && i + 1 < src.length && src[i + 1] == '{' -> {
                    var d = 1; var j = i + 2
                    while (d > 0) {
                        if (j >= src.length) throw CompileError("line $line: unterminated template")
                        if (src[j] == '{') d++ else if (src[j] == '}') d--
                        j++
                    }
                    if (sb.isNotEmpty()) { parts.add(sb.toString()); sb.clear() }
                    parts.add(TemplateCode(src.substring(i + 2, j - 1), line))
                    i = j
                }
                c == '$' && i + 1 < src.length && (src[i + 1].isLetter() || src[i + 1] == '_') -> {
                    var j = i + 1
                    while (j < src.length && (src[j].isLetterOrDigit() || src[j] == '_')) j++
                    if (sb.isNotEmpty()) { parts.add(sb.toString()); sb.clear() }
                    parts.add(TemplateCode(src.substring(i + 1, j), line))
                    i = j
                }
                else -> { if (c == '\n') line++; sb.append(c); i++ }
            }
        }
        if (sb.isNotEmpty() || parts.isEmpty()) parts.add(sb.toString())
        out.add(Tok(K.STR, "\"...\"", startLine, parts = parts))
    }

    private fun string() {
        i++
        val parts = ArrayList<Any>()
        val sb = StringBuilder()
        while (true) {
            if (i >= src.length) throw CompileError("line $line: unterminated string")
            val c = src[i]
            when {
                c == '"' -> { i++; break }
                c == '\\' -> sb.append(escape())
                c == '$' && i + 1 < src.length && src[i + 1] == '{' -> {
                    var d = 1; var j = i + 2
                    while (d > 0) {
                        if (j >= src.length) throw CompileError("line $line: unterminated template")
                        if (src[j] == '{') d++ else if (src[j] == '}') d--
                        j++
                    }
                    if (sb.isNotEmpty()) { parts.add(sb.toString()); sb.clear() }
                    parts.add(TemplateCode(src.substring(i + 2, j - 1), line))
                    i = j
                }
                c == '$' && i + 1 < src.length && (src[i + 1].isLetter() || src[i + 1] == '_') -> {
                    var j = i + 1
                    while (j < src.length && (src[j].isLetterOrDigit() || src[j] == '_')) j++
                    if (sb.isNotEmpty()) { parts.add(sb.toString()); sb.clear() }
                    parts.add(TemplateCode(src.substring(i + 1, j), line))
                    i = j
                }
                c == '\n' -> throw CompileError("line $line: newline in string")
                else -> { sb.append(c); i++ }
            }
        }
        if (sb.isNotEmpty() || parts.isEmpty()) parts.add(sb.toString())
        out.add(Tok(K.STR, "\"...\"", line, parts = parts))
    }
}
