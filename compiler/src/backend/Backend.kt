package newkotlin

/**
 * Compilation targets. The frontend (frontend/: lexer, parser, AST, types) is shared; each target has
 * its own backend module under backend/<target>/. The target is chosen once per compiler invocation
 * (`--target`), so generated code never contains any cross-target abstraction or indirection.
 */
enum class Target(val flag: String, val implemented: Boolean) {
    NATIVE("native", true),
    JS("js", false),
    WASM("wasm", false),
    JVM("jvm", false);

    companion object { fun parse(s: String): Target? = entries.firstOrNull { it.flag == s } }
}
