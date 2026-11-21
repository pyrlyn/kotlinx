package newkotlin

// Kept outside driver/ so the JVM build in mutation/jvm compiles exactly the pipeline the native driver runs.

/** `suspend fun main()`: renamed and run by a generated `fun main() = runBlocking { ... }` (same line numbers). */
private val SUSPEND_MAIN = Regex("""(?m)^suspend\s+fun\s+main\s*\(\s*\)""")

/** Source text to LLVM IR: lex, parse, merge the prelude and `Any` support, then generate code. */
fun compileToIr(src00: String): String {
    val src0 = if (SUSPEND_MAIN.containsMatchIn(src00)) SUSPEND_MAIN.replace(src00, "suspend fun kxSuspendMain()") + "\nfun main() = runBlocking { kxSuspendMain() }\n" else src00
    val src = withSerializable(src0); return compileGenerics(withAny(src, withPrelude(src, Parser(Lexer(src).lex()).program()))) }
