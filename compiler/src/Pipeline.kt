package newkotlin

// Kept outside driver/ so the JVM build in mutation/jvm compiles exactly the pipeline the native driver runs.

/** Runs a `suspend fun main()` (renamed [SUSPEND_MAIN_NAME] by [renameSuspendMain]); appended after the program. */
private const val SUSPEND_MAIN_RUNNER = "fun main() = runBlocking { kxSuspendMain() }\n"
private const val SUSPEND_MAIN_NAME = "kxSuspendMain"

/**
 * Source text to LLVM IR: lex, apply the token-level rewrites (`suspend fun main()`, `@Serializable` serializers), parse,
 * merge the prelude and `Any` support, then generate code. The rewrites work on tokens, so string literals and
 * comments that merely contain `suspend fun main()` or `X.serializer()` are left alone.
 */
fun compileToIr(src: String): String {
    val previous = compilationContext
    compilationContext = CompilationContext()
    return try { compileInContext(src) } finally { compilationContext = previous }
}

private fun compileInContext(src: String): String {
    var toks = Lexer(src).lex()
    // Text whose words decide which prelude parts are merged (withPrelude / withAny scan it), with the generated code.
    var text = src
    renameSuspendMain(toks)?.let { renamed ->
        text = "$src\n$SUSPEND_MAIN_RUNNER"
        toks = appendSource(renamed, SUSPEND_MAIN_RUNNER, src)
    }
    withSerializable(text, toks)?.let { (rewritten, gen) ->
        toks = appendSource(rewritten, gen, text)
        text = "$text\n$gen"
    }
    return compileGenerics(withAny(text, withPrelude(text, Parser(toks).program())))
}

/**
 * Tokens of a top-level `suspend fun main()` at the start of a line renamed to [SUSPEND_MAIN_NAME]; null when there is
 * none. Line numbers are unchanged.
 */
private fun renameSuspendMain(toks: List<Tok>): List<Tok>? {
    var depth = 0; var hit = false
    val out = toks.toMutableList()
    for ((i, t) in toks.withIndex()) {
        if (t.k == K.OP && t.s == "{") depth++ else if (t.k == K.OP && t.s == "}") depth--
        if (depth != 0 || t.k != K.ID || t.s != "suspend" || (i > 0 && toks[i - 1].k != K.NL) || i + 4 >= toks.size) continue
        val f = toks[i + 1]; val m = toks[i + 2]; val l = toks[i + 3]; val r = toks[i + 4]
        if (f.k == K.ID && f.s == "fun" && m.k == K.ID && m.s == "main" && l.k == K.OP && l.s == "(" && r.k == K.OP && r.s == ")") {
            out[i + 2] = Tok(K.ID, SUSPEND_MAIN_NAME, m.line); hit = true
        }
    }
    return if (hit) out else null
}

/**
 * [toks] (ending with EOF) followed by the tokens of [code], which is numbered as if it were appended to [text] after a
 * newline (the line numbers the former text-level rewrite gave it).
 */
fun appendSource(toks: List<Tok>, code: String, text: String): List<Tok> {
    val line = text.count { it == '\n' } + 2
    return toks.dropLast(1) + Tok(K.NL, "\n", toks.last().line) + Lexer(code, line).lex()
}
